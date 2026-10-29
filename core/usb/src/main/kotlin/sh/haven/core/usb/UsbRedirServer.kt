package sh.haven.core.usb

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Userspace `usbredir` TCP server ([UsbRedirProtocol], default port 4000) backed
 * by [UsbBroker] (#613).
 *
 * Complements [UsbIpServer] (port 3240):
 *  - **Remote QEMU/KVM over SSH**: when a connection profile forwards a phone USB
 *    device, Haven exposes it on loopback port 4000 and reverse-forwards `-R
 *    127.0.0.1:4000:127.0.0.1:4000`, so a remote QEMU guest attaches it directly
 *    in userspace (`-chardev socket,id=usbredir0,host=127.0.0.1,port=4000 -device
 *    usb-redir,chardev=usbredir0`) with **no root or `vhci_hcd` kernel module**
 *    required on the remote host.
 *  - **Local QEMU inside proot**: proot guests on the phone cannot use `usbip`
 *    because the Android kernel lacks `vhci_hcd`, but local `qemu-system-*`
 *    connects directly to `127.0.0.1:4000` over `usbredir`.
 *
 * Threading model:
 *  - One accept thread owns the [ServerSocket].
 *  - Each client connection has one reader loop plus per-endpoint single-thread
 *    executor lanes so transfers within an endpoint stay ordered while control,
 *    bulk IN, bulk OUT, and interrupt IN lanes do not block one another.
 */
@Singleton
class UsbRedirServer @Inject constructor(
    private val broker: UsbBroker,
) {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    var exportedDevice: String? = null
        private set

    @Volatile
    var boundPort: Int = 0
        private set

    @Volatile
    var boundAddress: String? = null
        private set

    private val clients = AtomicInteger(0)
    private val clientSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    val isRunning: Boolean
        get() = synchronized(lock) { serverSocket != null && exportedDevice != null }

    val exportedDeviceName: String?
        get() = exportedDevice

    val activeClients: Int
        get() = clients.get()

    val clientCount: Int
        get() = clients.get()

    /**
     * Export [deviceName] (must already be opened via [UsbBroker.openDevice]) on
     * `[bindAddress]:[port]` (default `127.0.0.1:4000`, or `0.0.0.0` when
     * [bindAddress] is null). Pass `port = 0` for an ephemeral port in tests.
     * Returns the actual bound port.
     */
    fun start(
        deviceName: String,
        port: Int = UsbRedirProtocol.DEFAULT_PORT,
        bindAddress: String? = "127.0.0.1",
    ): Int = synchronized(lock) {
        require(broker.isOpen(deviceName)) {
            "USB device '$deviceName' must be opened via UsbBroker.openDevice before exporting"
        }
        val effectiveBind = bindAddress ?: "0.0.0.0"
        val existing = serverSocket
        if (existing != null && !existing.isClosed &&
            boundAddress == effectiveBind && (port == 0 || port == boundPort)
        ) {
            exportedDevice = deviceName
            Log.i(TAG, "usbredir server updated exported device -> $deviceName on $effectiveBind:$boundPort")
            return boundPort
        }
        stopLocked()
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(
                if (bindAddress != null) InetSocketAddress(InetAddress.getByName(bindAddress), port)
                else InetSocketAddress(port),
                BACKLOG,
            )
        }
        val actualPort = ss.localPort
        serverSocket = ss
        exportedDevice = deviceName
        boundPort = actualPort
        boundAddress = effectiveBind

        acceptThread = thread(name = "usbredir-accept-$actualPort", isDaemon = true) {
            Log.i(TAG, "usbredir server listening on $bindAddress:$actualPort for $deviceName")
            while (!Thread.currentThread().isInterrupted) {
                val client = try {
                    ss.accept()
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                thread(name = "usbredir-client-${client.inetAddress.hostAddress}", isDaemon = true) {
                    serveClient(client)
                }
            }
            Log.i(TAG, "usbredir accept loop exited")
        }
        actualPort
    }

    /** Stop the server and close all active client connections. Idempotent. */
    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        exportedDevice = null
        boundPort = 0
        boundAddress = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        val snapshot = clientSockets.toList()
        clientSockets.clear()
        snapshot.forEach { runCatching { it.close() } }
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun serveClient(client: Socket) {
        clients.incrementAndGet()
        clientSockets.add(client)
        try {
            client.use { sock ->
                sock.tcpNoDelay = true
                val dev = exportedDevice ?: return
                if (!broker.isOpen(dev)) return
                val raw = runCatching { broker.rawDescriptors(dev) }.getOrNull() ?: return
                if (raw.size < 18) return
                serveStream(
                    input = sock.getInputStream().buffered(),
                    output = sock.getOutputStream(),
                    rawDescriptors = raw,
                    backend = BrokerBackend(broker, dev),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "usbredir client session ended: ${e.message}")
        } finally {
            clientSockets.remove(client)
            clients.decrementAndGet()
        }
    }

    companion object {
        private const val TAG = "UsbRedirServer"
        const val DEFAULT_PORT = UsbRedirProtocol.DEFAULT_PORT
        private const val BACKLOG = 4
        private const val TRANSFER_TIMEOUT_MS = 5_000
        private const val IN_POLL_MS = 250

        /**
         * Run a single `usbredir` session over arbitrary [input]/[output] streams
         * against [backend]. Used both by TCP clients of [UsbRedirServer] and by
         * unit tests with in-memory streams.
         */
        fun serveStream(
            input: InputStream,
            output: OutputStream,
            rawDescriptors: ByteArray,
            backend: UsbIpBackend,
            ourCaps: Int = UsbRedirProtocol.OUR_CAPS,
            transferTimeoutMs: Int = TRANSFER_TIMEOUT_MS,
            inPollMs: Int = IN_POLL_MS,
        ) {
            var desc = UsbRedirProtocol.parseDescriptors(rawDescriptors)
            val din = DataInputStream(input)
            val dout = DataOutputStream(output)
            val writeLock = Any()

            fun sendPacket(bytes: ByteArray) {
                synchronized(writeLock) {
                    dout.write(bytes)
                    dout.flush()
                }
            }

            // 1. Queue our usb_redir_hello (12-byte header before cap negotiation)
            //    and read the peer's usb_redir_hello.
            sendPacket(UsbRedirProtocol.encodeHello(caps = ourCaps))
            val firstPkt = try {
                UsbRedirProtocol.readPacket(din, use64BitIds = false)
            } catch (e: Exception) {
                Log.w(TAG, "failed reading peer hello: ${e.message}")
                return
            } ?: return
            if (firstPkt.type != UsbRedirProtocol.TYPE_HELLO) {
                Log.w(TAG, "expected TYPE_HELLO (0), got type=${firstPkt.type}")
                return
            }
            val peerHello = UsbRedirProtocol.decodeHello(firstPkt.payload)
            val negotiatedCaps = ourCaps and peerHello.caps
            val use64BitIds = UsbRedirProtocol.hasCap(negotiatedCaps, UsbRedirProtocol.CAP_64BITS_IDS)

            // 2. Announce interface_info, ep_info, and device_connect using the
            //    negotiated capabilities and header format.
            synchronized(writeLock) {
                dout.write(UsbRedirProtocol.encodeInterfaceInfo(desc, use64BitIds))
                dout.write(UsbRedirProtocol.encodeEpInfo(desc, negotiatedCaps, use64BitIds))
                dout.write(UsbRedirProtocol.encodeDeviceConnect(desc, negotiatedCaps, use64BitIds))
                dout.flush()
            }

            // 3. Per-endpoint executor lanes and cancellation / interrupt / iso state.
            val lanes = ConcurrentHashMap<Int, java.util.concurrent.ExecutorService>()
            fun laneFor(endpointAddress: Int): java.util.concurrent.ExecutorService =
                lanes.getOrPut(endpointAddress and 0xFF) { Executors.newSingleThreadExecutor() }

            val cancelled = ConcurrentHashMap.newKeySet<Long>()
            val interruptReceivingActive = ConcurrentHashMap.newKeySet<Int>()
            val isoStreams = ConcurrentHashMap<Int, AutoCloseable>()
            val altSettings = ConcurrentHashMap<Int, Int>()
            var activeConfig = desc.configurationValue

            try {
                while (backend.isAlive()) {
                    val pkt = try {
                        UsbRedirProtocol.readPacket(din, use64BitIds)
                    } catch (_: Exception) {
                        break
                    } ?: break

                    when (pkt.type) {
                        UsbRedirProtocol.TYPE_RESET -> {
                            interruptReceivingActive.clear()
                            isoStreams.values.forEach { runCatching { it.close() } }
                            isoStreams.clear()
                        }

                        UsbRedirProtocol.TYPE_SET_CONFIGURATION -> {
                            val cfg = if (pkt.payload.isNotEmpty()) pkt.payload[0].toInt() and 0xFF else activeConfig
                            activeConfig = cfg
                            sendPacket(
                                UsbRedirProtocol.encodeConfigurationStatus(
                                    id = pkt.id,
                                    status = UsbRedirProtocol.STATUS_SUCCESS,
                                    configuration = activeConfig,
                                    use64BitIds = use64BitIds,
                                ),
                            )
                        }

                        UsbRedirProtocol.TYPE_GET_CONFIGURATION -> {
                            sendPacket(
                                UsbRedirProtocol.encodeConfigurationStatus(
                                    id = pkt.id,
                                    status = UsbRedirProtocol.STATUS_SUCCESS,
                                    configuration = activeConfig,
                                    use64BitIds = use64BitIds,
                                ),
                            )
                        }

                        UsbRedirProtocol.TYPE_SET_ALT_SETTING -> {
                            val iface = if (pkt.payload.isNotEmpty()) pkt.payload[0].toInt() and 0xFF else 0
                            val alt = if (pkt.payload.size >= 2) pkt.payload[1].toInt() and 0xFF else 0
                            val status = try {
                                backend.setInterfaceAltSetting(iface, alt)
                                UsbRedirProtocol.STATUS_SUCCESS
                            } catch (e: Exception) {
                                Log.w(TAG, "setInterfaceAltSetting(iface=$iface, alt=$alt) failed: ${e.message}")
                                UsbRedirProtocol.STATUS_STALLED
                            }
                            if (status == UsbRedirProtocol.STATUS_SUCCESS) {
                                altSettings[iface] = alt
                                desc = desc.withAltSetting(iface, alt)
                                synchronized(writeLock) {
                                    // Match libusbredirhost: send updated ep_info for the new
                                    // alternate setting before alt_setting_status so the guest
                                    // sees the newly active isochronous/interrupt endpoints.
                                    dout.write(UsbRedirProtocol.encodeEpInfo(desc, negotiatedCaps, use64BitIds))
                                    dout.write(
                                        UsbRedirProtocol.encodeAltSettingStatus(
                                            id = pkt.id,
                                            status = status,
                                            interfaceNumber = iface,
                                            alt = alt,
                                            use64BitIds = use64BitIds,
                                        ),
                                    )
                                    dout.flush()
                                }
                            } else {
                                sendPacket(
                                    UsbRedirProtocol.encodeAltSettingStatus(
                                        id = pkt.id,
                                        status = status,
                                        interfaceNumber = iface,
                                        alt = altSettings[iface] ?: 0,
                                        use64BitIds = use64BitIds,
                                    ),
                                )
                            }
                        }

                        UsbRedirProtocol.TYPE_GET_ALT_SETTING -> {
                            val iface = if (pkt.payload.isNotEmpty()) pkt.payload[0].toInt() and 0xFF else 0
                            val alt = altSettings[iface] ?: 0
                            sendPacket(
                                UsbRedirProtocol.encodeAltSettingStatus(
                                    id = pkt.id,
                                    status = UsbRedirProtocol.STATUS_SUCCESS,
                                    interfaceNumber = iface,
                                    alt = alt,
                                    use64BitIds = use64BitIds,
                                ),
                            )
                        }

                        UsbRedirProtocol.TYPE_START_INTERRUPT_RECEIVING -> {
                            val ep = if (pkt.payload.isNotEmpty()) pkt.payload[0].toInt() and 0xFF else 0
                            sendPacket(
                                UsbRedirProtocol.encodeInterruptReceivingStatus(
                                    id = pkt.id,
                                    status = UsbRedirProtocol.STATUS_SUCCESS,
                                    endpoint = ep,
                                    use64BitIds = use64BitIds,
                                ),
                            )
                            if (interruptReceivingActive.add(ep)) {
                                val epIdx = UsbRedirProtocol.endpointIndex(ep)
                                val mps = desc.endpoints.getOrNull(epIdx)?.maxPacketSize?.takeIf { it > 0 } ?: 64
                                laneFor(ep).execute {
                                    while (ep in interruptReceivingActive &&
                                        !Thread.currentThread().isInterrupted &&
                                        backend.isAlive()
                                    ) {
                                        val data = try {
                                            backend.bulk(ep or 0x80, ByteArray(0), mps, inPollMs)
                                        } catch (_: Exception) {
                                            ByteArray(0)
                                        }
                                        if (data.isNotEmpty() && ep in interruptReceivingActive) {
                                            val msg = UsbRedirProtocol.encodeInterruptPacket(
                                                id = 0L,
                                                endpoint = ep or 0x80,
                                                status = UsbRedirProtocol.STATUS_SUCCESS,
                                                actualLength = data.size,
                                                inData = data,
                                                use64BitIds = use64BitIds,
                                            )
                                            runCatching { sendPacket(msg) }
                                        }
                                    }
                                }
                            }
                        }

                        UsbRedirProtocol.TYPE_STOP_INTERRUPT_RECEIVING -> {
                            val ep = if (pkt.payload.isNotEmpty()) pkt.payload[0].toInt() and 0xFF else 0
                            interruptReceivingActive.remove(ep)
                            sendPacket(
                                UsbRedirProtocol.encodeInterruptReceivingStatus(
                                    id = pkt.id,
                                    status = UsbRedirProtocol.STATUS_SUCCESS,
                                    endpoint = ep,
                                    use64BitIds = use64BitIds,
                                ),
                            )
                        }

                        UsbRedirProtocol.TYPE_START_ISO_STREAM -> {
                            val startIso = UsbRedirProtocol.decodeStartIsoStream(pkt.payload)
                            val ep = startIso.endpoint
                            isoStreams.remove(ep)?.let { runCatching { it.close() } }
                            if ((ep and 0x80) != 0) {
                                val epEntry = desc.resolveIsoEndpoint(ep)
                                val mps = epEntry?.maxPacketSize?.takeIf { it > 0 } ?: 1024
                                val earlyFrames = ArrayList<ByteArray>()
                                var statusSent = false
                                val streamLock = Any()
                                val status = try {
                                    val handle = backend.startIsoInStream(
                                        endpointAddress = ep,
                                        packetSize = mps,
                                        pktsPerUrb = startIso.pktsPerUrb,
                                        noUrbs = startIso.noUrbs,
                                    ) { frameStatus, frameData ->
                                        val isoPkt = UsbRedirProtocol.encodeIsoPacket(
                                            id = 0L,
                                            endpoint = ep,
                                            status = frameStatus,
                                            actualLength = frameData.size,
                                            inData = frameData,
                                            use64BitIds = use64BitIds,
                                        )
                                        synchronized(streamLock) {
                                            if (!statusSent) {
                                                earlyFrames.add(isoPkt)
                                                return@startIsoInStream
                                            }
                                        }
                                        runCatching { sendPacket(isoPkt) }
                                    }
                                    isoStreams[ep] = handle
                                    UsbRedirProtocol.STATUS_SUCCESS
                                } catch (e: Exception) {
                                    Log.w(TAG, "startIsoInStream(ep=0x${ep.toString(16)}) failed: ${e.message}")
                                    UsbRedirProtocol.STATUS_INVAL
                                }
                                synchronized(streamLock) {
                                    sendPacket(
                                        UsbRedirProtocol.encodeIsoStreamStatus(
                                            id = pkt.id,
                                            status = status,
                                            endpoint = ep,
                                            use64BitIds = use64BitIds,
                                        ),
                                    )
                                    statusSent = true
                                    if (status == UsbRedirProtocol.STATUS_SUCCESS) {
                                        for (frame in earlyFrames) {
                                            runCatching { sendPacket(frame) }
                                        }
                                    }
                                    earlyFrames.clear()
                                }
                            } else {
                                sendPacket(
                                    UsbRedirProtocol.encodeIsoStreamStatus(
                                        id = pkt.id,
                                        status = UsbRedirProtocol.STATUS_SUCCESS,
                                        endpoint = ep,
                                        use64BitIds = use64BitIds,
                                    ),
                                )
                            }
                        }

                        UsbRedirProtocol.TYPE_STOP_ISO_STREAM -> {
                            val ep = if (pkt.payload.isNotEmpty()) pkt.payload[0].toInt() and 0xFF else 0
                            isoStreams.remove(ep)?.let { runCatching { it.close() } }
                            sendPacket(
                                UsbRedirProtocol.encodeIsoStreamStatus(
                                    id = pkt.id,
                                    status = UsbRedirProtocol.STATUS_SUCCESS,
                                    endpoint = ep,
                                    use64BitIds = use64BitIds,
                                ),
                            )
                        }

                        UsbRedirProtocol.TYPE_ALLOC_BULK_STREAMS,
                        UsbRedirProtocol.TYPE_FREE_BULK_STREAMS,
                        -> {
                            val eps = if (pkt.payload.size >= 4) UsbRedirProtocol.getIntLE(pkt.payload, 0) else 0
                            sendPacket(
                                UsbRedirProtocol.encodeBulkStreamsStatus(
                                    id = pkt.id,
                                    endpoints = eps,
                                    noStreams = 0,
                                    status = UsbRedirProtocol.STATUS_INVAL,
                                    use64BitIds = use64BitIds,
                                ),
                            )
                        }

                        UsbRedirProtocol.TYPE_START_BULK_RECEIVING,
                        UsbRedirProtocol.TYPE_STOP_BULK_RECEIVING,
                        -> {
                            val streamId = if (pkt.payload.size >= 4) UsbRedirProtocol.getIntLE(pkt.payload, 0) else 0
                            val epOffset = if (pkt.type == UsbRedirProtocol.TYPE_START_BULK_RECEIVING) 8 else 4
                            val ep = if (pkt.payload.size > epOffset) pkt.payload[epOffset].toInt() and 0xFF else 0
                            sendPacket(
                                UsbRedirProtocol.encodeBulkReceivingStatus(
                                    id = pkt.id,
                                    streamId = streamId,
                                    endpoint = ep,
                                    status = UsbRedirProtocol.STATUS_INVAL,
                                    use64BitIds = use64BitIds,
                                ),
                            )
                        }

                        UsbRedirProtocol.TYPE_CANCEL_DATA_PACKET -> {
                            cancelled.add(pkt.id)
                        }

                        UsbRedirProtocol.TYPE_CONTROL_PACKET -> {
                            val ctrl = UsbRedirProtocol.decodeControlPacket(pkt.id, pkt.payload)
                            laneFor(0).execute {
                                val reply = bridgeControl(ctrl, backend, transferTimeoutMs, use64BitIds)
                                synchronized(writeLock) {
                                    if (!cancelled.remove(ctrl.id)) {
                                        runCatching { dout.write(reply); dout.flush() }
                                    }
                                }
                            }
                        }

                        UsbRedirProtocol.TYPE_BULK_PACKET -> {
                            val bulk = UsbRedirProtocol.decodeBulkPacket(pkt.id, pkt.payload, negotiatedCaps)
                            val isMassStorage = desc.isMassStorageEndpoint(bulk.endpoint)
                            laneFor(bulk.endpoint).execute {
                                val reply = if (bulk.isIn && !isMassStorage) {
                                    pollBulkIn(bulk, backend, cancelled, inPollMs, negotiatedCaps, use64BitIds)
                                } else {
                                    bridgeBulk(bulk, backend, transferTimeoutMs, negotiatedCaps, use64BitIds)
                                }
                                synchronized(writeLock) {
                                    val wasCancelled = cancelled.remove(bulk.id)
                                    if (reply != null && !wasCancelled) {
                                        runCatching { dout.write(reply); dout.flush() }
                                    }
                                }
                            }
                        }

                        UsbRedirProtocol.TYPE_ISO_PACKET -> {
                            val iso = UsbRedirProtocol.decodeIsoPacket(pkt.id, pkt.payload)
                            if (!iso.isIn && iso.data.isNotEmpty()) {
                                laneFor(iso.endpoint).execute {
                                    runCatching { backend.isoOut(iso.endpoint, iso.data) }
                                }
                            }
                        }

                        UsbRedirProtocol.TYPE_INTERRUPT_PACKET -> {
                            val intr = UsbRedirProtocol.decodeInterruptPacket(pkt.id, pkt.payload)
                            laneFor(intr.endpoint).execute {
                                val reply = if (intr.isIn) {
                                    pollInterruptIn(intr, backend, cancelled, inPollMs, use64BitIds)
                                } else {
                                    bridgeInterruptOut(intr, backend, transferTimeoutMs, use64BitIds)
                                }
                                synchronized(writeLock) {
                                    val wasCancelled = cancelled.remove(intr.id)
                                    if (reply != null && !wasCancelled) {
                                        runCatching { dout.write(reply); dout.flush() }
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                interruptReceivingActive.clear()
                isoStreams.values.forEach { runCatching { it.close() } }
                isoStreams.clear()
                lanes.values.forEach { it.shutdownNow() }
            }
        }

        /** Execute a single control transfer and encode the `usb_redir_control_packet` response. */
        fun bridgeControl(
            ctrl: UsbRedirProtocol.ControlPacket,
            backend: UsbIpBackend,
            timeoutMs: Int,
            use64BitIds: Boolean,
        ): ByteArray = try {
            val inData = if (ctrl.requestType == 0x80 && ctrl.request == 0x06 && (ctrl.value ushr 8) == 0x02) {
                // GET_DESCRIPTOR(CONFIGURATION): strip composite CCID on FIDO keys so host
                // pcscd cannot starve FIDO; all other devices (mass storage, CDC, etc.) pass through.
                val full = backend.control(0x80, 0x06, ctrl.value, ctrl.index, ByteArray(0), maxOf(ctrl.length, 0xFF), timeoutMs)
                UsbIpServer.filterToFidoInterface(full).let { it.copyOf(minOf(it.size, ctrl.length)) }
            } else if ((ctrl.requestType == 0x00 && ctrl.request == 0x09) ||
                (ctrl.requestType == 0x02 && ctrl.request == 0x01 && ctrl.value == 0)
            ) {
                // SET_CONFIGURATION or CLEAR_FEATURE(ENDPOINT_HALT): forward best-effort
                // (Android's usbdevfs may reject SET_CONFIGURATION because the kernel
                // already configured the device on attach).
                runCatching {
                    backend.control(ctrl.requestType, ctrl.request, ctrl.value, ctrl.index, ctrl.data, ctrl.length, timeoutMs)
                }.getOrDefault(ByteArray(0))
            } else {
                backend.control(ctrl.requestType, ctrl.request, ctrl.value, ctrl.index, ctrl.data, ctrl.length, timeoutMs)
            }
            val actualLen = if (ctrl.isIn) inData.size else ctrl.data.size
            UsbRedirProtocol.encodeControlReply(
                req = ctrl,
                status = UsbRedirProtocol.STATUS_SUCCESS,
                actualLength = actualLen,
                inData = if (ctrl.isIn) inData else ByteArray(0),
                use64BitIds = use64BitIds,
            )
        } catch (e: Exception) {
            Log.w(TAG, "control id=${ctrl.id} req=0x${ctrl.request.toString(16)} failed: ${e.message}")
            UsbRedirProtocol.encodeControlReply(
                req = ctrl,
                status = UsbRedirProtocol.STATUS_STALLED,
                actualLength = 0,
                inData = ByteArray(0),
                use64BitIds = use64BitIds,
            )
        }

        /** Execute a single bounded bulk transfer (OUT, or Mass-Storage IN). */
        fun bridgeBulk(
            bulk: UsbRedirProtocol.BulkPacket,
            backend: UsbIpBackend,
            timeoutMs: Int,
            negotiatedCaps: Int,
            use64BitIds: Boolean,
        ): ByteArray = try {
            val inData = backend.bulk(bulk.endpoint, bulk.data, bulk.length, timeoutMs)
            val actualLen = if (bulk.isIn) inData.size else bulk.data.size
            UsbRedirProtocol.encodeBulkReply(
                req = bulk,
                status = UsbRedirProtocol.STATUS_SUCCESS,
                actualLength = actualLen,
                inData = if (bulk.isIn) inData else ByteArray(0),
                negotiatedCaps = negotiatedCaps,
                use64BitIds = use64BitIds,
            )
        } catch (e: Exception) {
            Log.w(TAG, "bulk id=${bulk.id} ep=0x${bulk.endpoint.toString(16)} failed: ${e.message}")
            UsbRedirProtocol.encodeBulkReply(
                req = bulk,
                status = UsbRedirProtocol.STATUS_STALLED,
                actualLength = 0,
                inData = ByteArray(0),
                negotiatedCaps = negotiatedCaps,
                use64BitIds = use64BitIds,
            )
        }

        /** Poll a standing non-mass-storage bulk IN transfer in [pollMs] slices until data or cancellation. */
        fun pollBulkIn(
            bulk: UsbRedirProtocol.BulkPacket,
            backend: UsbIpBackend,
            cancelled: Set<Long>,
            pollMs: Int,
            negotiatedCaps: Int,
            use64BitIds: Boolean,
        ): ByteArray? {
            while (bulk.id !in cancelled && !Thread.currentThread().isInterrupted && backend.isAlive()) {
                val data = try {
                    backend.bulk(bulk.endpoint, ByteArray(0), bulk.length, pollMs)
                } catch (_: Exception) {
                    ByteArray(0)
                }
                if (data.isNotEmpty()) {
                    return UsbRedirProtocol.encodeBulkReply(
                        req = bulk,
                        status = UsbRedirProtocol.STATUS_SUCCESS,
                        actualLength = data.size,
                        inData = data,
                        negotiatedCaps = negotiatedCaps,
                        use64BitIds = use64BitIds,
                    )
                }
            }
            return null
        }

        /** Execute an interrupt OUT transfer. */
        fun bridgeInterruptOut(
            intr: UsbRedirProtocol.InterruptPacket,
            backend: UsbIpBackend,
            timeoutMs: Int,
            use64BitIds: Boolean,
        ): ByteArray = try {
            backend.bulk(intr.endpoint, intr.data, intr.length, timeoutMs)
            UsbRedirProtocol.encodeInterruptPacket(
                id = intr.id,
                endpoint = intr.endpoint,
                status = UsbRedirProtocol.STATUS_SUCCESS,
                actualLength = intr.data.size,
                inData = ByteArray(0),
                use64BitIds = use64BitIds,
            )
        } catch (e: Exception) {
            Log.w(TAG, "interrupt OUT id=${intr.id} ep=0x${intr.endpoint.toString(16)} failed: ${e.message}")
            UsbRedirProtocol.encodeInterruptPacket(
                id = intr.id,
                endpoint = intr.endpoint,
                status = UsbRedirProtocol.STATUS_STALLED,
                actualLength = 0,
                inData = ByteArray(0),
                use64BitIds = use64BitIds,
            )
        }

        /** Poll an explicit interrupt IN request in [pollMs] slices until data or cancellation. */
        fun pollInterruptIn(
            intr: UsbRedirProtocol.InterruptPacket,
            backend: UsbIpBackend,
            cancelled: Set<Long>,
            pollMs: Int,
            use64BitIds: Boolean,
        ): ByteArray? {
            while (intr.id !in cancelled && !Thread.currentThread().isInterrupted && backend.isAlive()) {
                val data = try {
                    backend.bulk(intr.endpoint, ByteArray(0), intr.length, pollMs)
                } catch (_: Exception) {
                    ByteArray(0)
                }
                if (data.isNotEmpty()) {
                    return UsbRedirProtocol.encodeInterruptPacket(
                        id = intr.id,
                        endpoint = intr.endpoint,
                        status = UsbRedirProtocol.STATUS_SUCCESS,
                        actualLength = data.size,
                        inData = data,
                        use64BitIds = use64BitIds,
                    )
                }
            }
            return null
        }
    }
}
