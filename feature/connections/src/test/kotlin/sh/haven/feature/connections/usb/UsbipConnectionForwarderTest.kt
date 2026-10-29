package sh.haven.feature.connections.usb

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbDeviceInfo
import sh.haven.core.usb.UsbIpServer
import sh.haven.core.usb.UsbRedirServer

class UsbipConnectionForwarderTest {

    private val SID = "sid-1"

    private fun yubikey(hasPermission: Boolean = true) = UsbDeviceInfo(
        deviceName = "/dev/bus/usb/001/002",
        vendorId = 0x1050, productId = 0x0406, deviceClass = 0,
        manufacturerName = "Yubico", productName = "YubiKey FIDO+CCID",
        serialNumber = null, hasPermission = hasPermission, isOpen = false,
        interfaces = emptyList(),
    )

    /** A SshSessionManager whose getSession(SID) yields a session backed by [client]. */
    private fun sessionsFor(client: SshClient): SshSessionManager {
        val sessions = mockk<SshSessionManager>(relaxed = true)
        val session = mockk<SshSessionManager.SessionState>(relaxed = true)
        every { session.client } returns client
        every { sessions.getSession(SID) } returns session
        return sessions
    }

    @Test
    fun `attach exports the device over usbip and usbredir and registers non-critical reverse forwards`() = runTest {
        val broker = mockk<UsbBroker>(relaxed = true)
        val server = mockk<UsbIpServer>(relaxed = true)
        val redirServer = mockk<UsbRedirServer>(relaxed = true)
        val client = mockk<SshClient>(relaxed = true)
        val sessions = sessionsFor(client)
        every { broker.listDevices() } returns listOf(yubikey())
        coEvery { broker.openDevice(any()) } returns yubikey()
        every { broker.isOpen(any()) } returns true
        every { server.start(any(), any(), any()) } returns 3240
        every { redirServer.start(any(), any(), any()) } returns 4000
        coEvery { client.execCommand(any()) } returns ExecResult(0, "rc=0", "")

        val handle = UsbipConnectionForwarder(broker, server, redirServer, sessions).attach(SID, "1050:0406") {}

        assertEquals("1-2", handle?.busid) // "/dev/bus/usb/001/002" -> "1-2"
        assertEquals(3240, handle?.remotePort)
        assertEquals(4000, handle?.redirPort)
        verify { server.start(eq("/dev/bus/usb/001/002"), any(), eq("127.0.0.1")) }
        verify { redirServer.start(eq("/dev/bus/usb/001/002"), any(), eq("127.0.0.1")) }
        val fwd = slot<List<SshSessionManager.PortForwardInfo>>()
        verify { sessions.applyPortForwards(SID, capture(fwd)) }
        assertEquals(2, fwd.captured.size)
        val usbipRule = fwd.captured[0]
        assertEquals(SshSessionManager.PortForwardType.REMOTE, usbipRule.type)
        assertEquals(3240, usbipRule.bindPort)
        assertEquals("usbip-export", usbipRule.ruleId)
        assertEquals(false, usbipRule.critical) // never fails the user's SSH (re)connect
        val redirRule = fwd.captured[1]
        assertEquals(SshSessionManager.PortForwardType.REMOTE, redirRule.type)
        assertEquals(4000, redirRule.bindPort)
        assertEquals("usbredir-export", redirRule.ruleId)
        assertEquals(false, redirRule.critical)
        verify { sessions.setOnReconnected(eq(SID), any()) } // reconnect re-attach registered
        coVerify { client.execCommand(match { it.contains("usbip attach") && it.contains("1-2") }) }
    }

    @Test
    fun `the registered reconnect hook re-runs the host usbip attach`() = runTest {
        val broker = mockk<UsbBroker>(relaxed = true)
        val server = mockk<UsbIpServer>(relaxed = true)
        val redirServer = mockk<UsbRedirServer>(relaxed = true)
        val client = mockk<SshClient>(relaxed = true)
        val sessions = sessionsFor(client)
        every { broker.listDevices() } returns listOf(yubikey())
        coEvery { broker.openDevice(any()) } returns yubikey()
        every { broker.isOpen(any()) } returns true
        every { server.start(any(), any(), any()) } returns 3240
        every { redirServer.start(any(), any(), any()) } returns 4000
        coEvery { client.execCommand(any()) } returns ExecResult(0, "rc=0", "")
        val hook = slot<suspend () -> Unit>()
        every { sessions.setOnReconnected(eq(SID), capture(hook)) } returns Unit

        UsbipConnectionForwarder(broker, server, redirServer, sessions).attach(SID, "1050:0406") {}
        // Simulate a reconnect firing the hook → host attach runs again.
        hook.captured.invoke()

        coVerify(exactly = 2) { client.execCommand(match { it.contains("usbip attach") }) }
    }

    @Test
    fun `attach returns null and logs when the device is not attached`() = runTest {
        val broker = mockk<UsbBroker>(relaxed = true)
        every { broker.listDevices() } returns emptyList()
        var logged = false

        val handle = UsbipConnectionForwarder(broker, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
            .attach(SID, "1050:0406") { logged = true }

        assertNull(handle)
        assertTrue(logged)
    }

    @Test
    fun `teardown clears the reconnect hook, removes the forwards, stops the servers, and closes the device`() = runTest {
        val broker = mockk<UsbBroker>(relaxed = true)
        val server = mockk<UsbIpServer>(relaxed = true)
        val redirServer = mockk<UsbRedirServer>(relaxed = true)
        val sessions = mockk<SshSessionManager>(relaxed = true)
        val handle = UsbipConnectionForwarder.Handle("/dev/bus/usb/001/002", "1-2", 3240, 4000)

        UsbipConnectionForwarder(broker, server, redirServer, sessions).teardown(SID, handle) {}

        verify { sessions.setOnReconnected(SID, null) }
        val fwds = mutableListOf<SshSessionManager.PortForwardInfo>()
        verify(exactly = 2) { sessions.removePortForward(eq(SID), capture(fwds)) }
        assertEquals(listOf("usbip-export", "usbredir-export"), fwds.map { it.ruleId })
        assertEquals(listOf(3240, 4000), fwds.map { it.bindPort })
        verify { server.stop() }
        verify { redirServer.stop() }
        verify { broker.closeDevice("/dev/bus/usb/001/002") }
    }
}
