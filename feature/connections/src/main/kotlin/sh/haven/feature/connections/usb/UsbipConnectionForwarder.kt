package sh.haven.feature.connections.usb

import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbIpServer
import sh.haven.core.usb.UsbRedirServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copyable QEMU command-line snippet for attaching a forwarded USB device via
 * SPICE `usbredir` over the SSH reverse forward (`127.0.0.1:4000` on the remote
 * host, #613).
 */
const val SSH_USBREDIR_QEMU_ARGS =
    "-device qemu-xhci -chardev socket,id=usbredir0,host=127.0.0.1,port=4000 -device usb-redir,chardev=usbredir0"

/**
 * Auto-forwards a phone-attached USB device to a connected SSH host over both
 * USB/IP (`:3240`) and SPICE `usbredir` (`:4000`, #613). On connect: resolve
 * the profile's VID:PID to the live device, open it, start the userspace
 * [UsbIpServer] and [UsbRedirServer] on loopback, register remote port-forwards
 * for both ports, and best-effort `usbip attach` on the remote so the device
 * appears there as a real node (or can be attached directly to a remote QEMU/KVM
 * guest over `usbredir` without root on the host). On teardown: drop both
 * forwards, stop both servers, and close the device.
 *
 * The `-R` rules are registered through [SshSessionManager.applyPortForwards] (not a raw
 * `setPortForwardingR`) so they land in the session's `activeForwards` and a silent
 * SSH reconnect re-binds them automatically — important for the remote-over-4G use
 * case where the tunnel drops often. They are non-critical: a failed re-bind never
 * fails the user's SSH (re)connect, the export is just reported down. A
 * per-session reconnect hook re-runs the host `usbip attach` each time the tunnel
 * comes back, since the device node on the host vanishes when the tunnel drops.
 *
 * One forwarded device at a time. [server] and [redirServer] are the shared DI
 * singletons, so this never fights the MCP `start_usbip_export` /
 * `start_usbredir_export` paths for the ports.
 */
@Singleton
class UsbipConnectionForwarder @Inject constructor(
    private val broker: UsbBroker,
    private val server: UsbIpServer,
    private val redirServer: UsbRedirServer,
    private val sessions: SshSessionManager,
) {
    /** A live forward, returned by [attach] and handed back to [teardown]. */
    data class Handle(
        val deviceName: String,
        val busid: String,
        val remotePort: Int,
        val redirPort: Int = UsbRedirServer.DEFAULT_PORT,
    )

    /**
     * Attached phone USB devices as `(vidPid, label)` for the connection-settings
     * picker. Product names need USB permission, so an un-permissioned device
     * shows its VID:PID alone.
     */
    fun availableDevices(): List<Pair<String, String>> =
        broker.listDevices().map { d ->
            d.vidPid to (d.productName?.let { "$it (${d.vidPid})" } ?: d.vidPid)
        }

    /**
     * Resolve [vidPid] (e.g. "1050:0406") to a live phone device and forward it to
     * SSH session [sessionId]. Returns the handle once the export + remote tunnel
     * are up (the remote `usbip attach` is best-effort — it needs passwordless
     * sudo for usbip on the host), or null if no such device is attached /
     * permission is denied. [log] receives human-readable progress for the
     * connection log.
     */
    suspend fun attach(sessionId: String, vidPid: String, log: suspend (String) -> Unit): Handle? {
        val dev = broker.listDevices().firstOrNull { it.vidPid.equals(vidPid, ignoreCase = true) }
        val label = dev?.productName ?: vidPid
        if (dev == null) {
            log("USB forward: no $vidPid device attached to the phone — skipped")
            return null
        }
        if (!dev.hasPermission && !broker.requestPermission(dev.deviceName)) {
            log("USB forward: USB permission denied for $label")
            return null
        }
        return try {
            broker.openDevice(dev.deviceName)
            val port = server.start(dev.deviceName, bindAddress = LOOPBACK)
            val redirPort = redirServer.start(dev.deviceName, bindAddress = LOOPBACK)
            val busid = busidOf(dev.deviceName)
            // Register the -R rules via the session manager so they land in activeForwards
            // and a silent reconnect re-binds them. Non-critical: never fail the SSH
            // (re)connect on a USB bind failure.
            sessions.applyPortForwards(sessionId, listOf(usbForward(port), usbRedirForward(redirPort)))
            // Re-run the host-side attach on every reconnect (the host's device
            // node went away when the tunnel dropped).
            sessions.setOnReconnected(sessionId) {
                reattachOnHost(sessionId, dev.deviceName, busid, redirPort, label, log)
            }
            reattachOnHost(sessionId, dev.deviceName, busid, redirPort, label, log)
            Handle(dev.deviceName, busid, port, redirPort)
        } catch (e: Exception) {
            log("USB forward: failed for $label — ${e.message}")
            sessions.setOnReconnected(sessionId, null)
            runCatching { server.stop() }
            runCatching { redirServer.stop() }
            runCatching { broker.closeDevice(dev.deviceName) }
            null
        }
    }

    /** Tear down a forward established by [attach]. Best-effort; never throws. */
    suspend fun teardown(sessionId: String, handle: Handle, log: suspend (String) -> Unit) {
        // Drop the reconnect hook first so a reconnect racing teardown can't re-attach.
        sessions.setOnReconnected(sessionId, null)
        // Removing the remote forward closes the usbip socket, which makes the
        // remote vhci detach the device on its own — no host sudo needed, and we
        // don't touch any other usbip devices the host may have. Routed through
        // the session manager so it uses the live (post-reconnect) client and
        // drops the rule from activeForwards.
        runCatching { sessions.removePortForward(sessionId, usbForward(handle.remotePort)) }
        runCatching { sessions.removePortForward(sessionId, usbRedirForward(handle.redirPort)) }
        runCatching { server.stop() }
        runCatching { redirServer.stop() }
        runCatching { broker.closeDevice(handle.deviceName) }
        log("USB forward: ${handle.busid} torn down")
    }

    /**
     * Best-effort `usbip attach` on the host for [busid]. Re-resolves the live
     * session client (so it works on the post-reconnect client too) and no-ops if
     * the session is gone or the device was detached during a tunnel outage.
     */
    private suspend fun reattachOnHost(
        sessionId: String,
        deviceName: String,
        busid: String,
        redirPort: Int,
        label: String,
        log: suspend (String) -> Unit,
    ) {
        val client = sessions.getSession(sessionId)?.client ?: run {
            log("USB forward: no live session for $label — host attach skipped")
            return
        }
        if (!broker.isOpen(deviceName)) {
            log("USB forward: $label no longer attached to the phone — host attach skipped")
            return
        }
        val attach = runCatching {
            client.execCommand(
                "(sudo -n modprobe vhci_hcd 2>/dev/null; " +
                    "sudo -n usbip attach -r $LOOPBACK -b $busid) >/dev/null 2>&1; echo rc=$?",
            )
        }.getOrNull()
        if (attach?.stdout?.contains("rc=0") == true) {
            log("USB forward: $label attached on the remote (busid $busid; QEMU usbredir on 127.0.0.1:$redirPort)")
        } else {
            log(
                "USB forward: export + tunnel up for $label (USB/IP busid $busid, QEMU usbredir 127.0.0.1:$redirPort); " +
                    "run `sudo usbip attach -r 127.0.0.1 -b $busid` on the host or attach QEMU to 127.0.0.1:$redirPort",
            )
        }
    }

    /** The non-critical REMOTE forward rule for the usbip [port] (loopback both ends). */
    private fun usbForward(port: Int) = SshSessionManager.PortForwardInfo(
        ruleId = USB_RULE_ID,
        type = SshSessionManager.PortForwardType.REMOTE,
        bindAddress = LOOPBACK,
        bindPort = port,
        targetHost = LOOPBACK,
        targetPort = port,
        critical = false,
        selfHealOnBindFailure = false,
    )

    /** The non-critical REMOTE forward rule for the usbredir [port] (loopback both ends). */
    private fun usbRedirForward(port: Int) = SshSessionManager.PortForwardInfo(
        ruleId = USBREDIR_RULE_ID,
        type = SshSessionManager.PortForwardType.REMOTE,
        bindAddress = LOOPBACK,
        bindPort = port,
        targetHost = LOOPBACK,
        targetPort = port,
        critical = false,
        selfHealOnBindFailure = false,
    )

    /** "/dev/bus/usb/001/002" -> "1-2" (the usbip busid; leading zeros stripped). */
    private fun busidOf(deviceName: String): String {
        val parts = deviceName.trimEnd('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val dev = parts.lastOrNull()?.toIntOrNull() ?: 1
        return "$bus-$dev"
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val USB_RULE_ID = "usbip-export"
        private const val USBREDIR_RULE_ID = "usbredir-export"
    }
}
