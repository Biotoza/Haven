package sh.haven.core.tunnel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.db.entities.typeEnum
import sh.haven.core.data.repository.TunnelConfigRepository
import java.io.IOException

/**
 * The shared-key fight guard: a system VPN (the official WireGuard app)
 * holding a tunnel config's address means Haven must NOT run its own WG
 * device on that key — both flap. Covered: the config address parse, the
 * factory refuse, the resolver's fall-through to direct dial, and the
 * dial-stall bounce that re-resolves the endpoint on a roamed/dead path.
 */
class WireGuardConflictTest {

    private val sampleConfig = """
        [Interface]
        PrivateKey = ABCDEF=
        Address = 10.1.0.2/32, fd00::2/128
        MTU = 1280

        [Peer]
        PublicKey = AAAA=
        Endpoint = wg.example.com:51820
        AllowedIPs = 0.0.0.0/0, ::/0
    """.trimIndent()

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    // -- address parse -------------------------------------------------------

    @Test
    fun `parses interface addresses only, not peer blocks`() {
        val addrs = SystemVpnAddressProbe.parseInterfaceAddresses(sampleConfig)
        assertEquals(2, addrs.size)
        assertEquals("10.1.0.2", addrs[0].hostAddress)
        assertEquals("fd00:0:0:0:0:0:0:2", addrs[1].hostAddress)
    }

    @Test
    fun `peer address is never confused with interface address`() {
        val cfg = """
            [Interface]
            PrivateKey = ABCDEF=
            Address = 10.1.0.2
            [Peer]
            Address = 10.9.9.9
        """.trimIndent()
        val addrs = SystemVpnAddressProbe.parseInterfaceAddresses(cfg)
        assertEquals(listOf("10.1.0.2"), addrs.map { it.hostAddress })
    }

    @Test
    fun `empty and malformed inputs yield empty list`() {
        assertTrue(SystemVpnAddressProbe.parseInterfaceAddresses("").isEmpty())
        assertTrue(
            SystemVpnAddressProbe.parseInterfaceAddresses("[Peer]\nAllowedIPs = 0.0.0.0/0").isEmpty(),
        )
    }

    // -- factory refuse ------------------------------------------------------

    @Test
    fun `factory refuses the wg tunnel when a system vpn holds the address`() {
        val probe = mockk<SystemVpnAddressProbe> {
            every { collidingVpnInterface(any()) } returns "tun0"
        }
        val factory = DefaultTunnelFactory(
            mockk(), okhttp3.OkHttpClient(), TunnelAuthBroker(mockk()), probe,
        )
        val ex = org.junit.Assert.assertThrows(WireGuardSystemVpnConflict::class.java) {
            factory.create(
                TunnelConfig(
                    id = "wg-cfg",
                    label = "home",
                    type = TunnelConfigType.WIREGUARD.name,
                    configText = sampleConfig.toByteArray(),
                ),
            )
        }
        assertEquals("tun0", ex.vpnInterface)
        assertTrue(ex.message!!.contains("system VPN"))
    }

    @Test
    fun `resolver falls through to direct dial when acquire refuses on conflict`() = kotlinx.coroutines.test.runTest {
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } throws
                WireGuardSystemVpnConflict("tun0", "10.1.0.2")
        }
        val resolver = TunnelResolver(mgr)
        // No tunnel → null → the caller dials directly (riding the system VPN).
        assertNull(resolver.dial(profile(tunnelConfigId = "tid"), "192.168.0.180", 22, 30_000))
        assertNull(resolver.socketFactory(profile(tunnelConfigId = "tid")))
    }

    @Test
    fun `resolver proxy fallback still applies after conflict`() = kotlinx.coroutines.test.runTest {
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } throws
                WireGuardSystemVpnConflict("tun0", "10.1.0.2")
        }
        val resolver = TunnelResolver(mgr)
        val p = profile(tunnelConfigId = "tid").copy(
            proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080,
        )
        // Conflict skips the tunnel; the legacy proxy columns still route —
        // assert the constructed JSch proxy variant without real network I/O.
        val proxy = resolver.jschProxy(p)
        assertNotNull(proxy)
    }

    // -- tunnel manager + conflict ------------------------------------------

    @Test
    fun `acquire surfaces the conflict and caches nothing`() = kotlinx.coroutines.test.runTest {
        val repo = mockk<TunnelConfigRepository> {
            coEvery { getById("cfg-x") } returns TunnelConfig(
                id = "cfg-x", label = "cfg", type = TunnelConfigType.WIREGUARD.name,
                configText = byteArrayOf(),
            )
        }
        val breaking = mockk<TunnelFactory> {
            every { create(any()) } throws WireGuardSystemVpnConflict("tun0", "10.1.0.2")
        }
        val manager = TunnelManager(repo, breaking)
        try {
            manager.acquire("cfg-x", "p1")
            org.junit.Assert.fail("expected conflict")
        } catch (e: WireGuardSystemVpnConflict) {
            assertEquals("tun0", e.vpnInterface)
        }
        // Nothing cached: the live snapshot has no tunnels.
        assertTrue(manager.liveSnapshot().isEmpty())
        // A later acquire retries the factory fresh (e.g. the system VPN left).
        val recovered = mockk<TunnelFactory> {
            every { create(any()) } returns mockk<Tunnel>(relaxed = true)
        }
        val manager2 = TunnelManager(repo, recovered)
        assertNotNull(manager2.acquire("cfg-x", "p1"))
    }

    // -- dial-stall bounce ---------------------------------------------------

    @Test
    fun `two consecutive dial timeouts bounce the native handle once`() {
        var startTunnelCalls = 0
        var handle1Closes = 0
        val callLog = mutableListOf<Pair<Int, String>>()
        val okConn: WgNativeConn = object : WgNativeConn {
            override fun read(size: Int): ByteArray? = ByteArray(0)
            override fun write(data: ByteArray) {}
            override fun remoteAddrHost(): String = "10.0.0.1"
            override fun close() {}
        }
        var secondThrows = false
        val handles = mutableListOf(
            FakeNative(
                onDial = { callLog.add(1 to "throw"); throw IOException("dial: context deadline exceeded") },
                onClose = { handle1Closes++ },
            ),
            FakeNative(
                onDial = {
                    if (secondThrows) throw IOException("dial: context deadline exceeded")
                    callLog.add(2 to "ok"); okConn
                },
            ),
        )
        val tunnel = WireguardTunnel(sampleConfig, nativeStarter = { _ ->
            startTunnelCalls++
            handles.removeFirst()
        })

        // 1st timeout: below the threshold, no bounce yet.
        assertThrows(IOException::class.java) { tunnel.dial("10.0.0.1", 22, 1_000) }
        assertEquals(1, startTunnelCalls)
        assertEquals(0, handle1Closes)

        // 2nd timeout: crosses the threshold → bounce (start #2, close of #1).
        assertThrows(IOException::class.java) { tunnel.dial("10.0.0.1", 22, 1_000) }
        assertEquals(2, startTunnelCalls)
        assertEquals(1, handle1Closes)

        // 3rd dial rides the fresh handle and succeeds.
        assertTrue(tunnel.dial("10.0.0.1", 22, 1_000) != null)
        assertEquals(listOf(1 to "throw", 1 to "throw", 2 to "ok"), callLog)

        // A success resets the streak, so a single later timeout does NOT
        // bounce again (still 2 starts).
        secondThrows = true
        // dial above succeeded → streak reset → this one timeout = streak 1,
        // on the CURRENT handle — no bounce.
        assertThrows(IOException::class.java) { tunnel.dial("10.0.0.1", 22, 1_000) }
        assertEquals(2, startTunnelCalls)
    }

    private class FakeNative(
        private val onDial: () -> WgNativeConn? = { null },
        private val onClose: () -> Unit = {},
    ) : WgNativeDevice {
        override fun dial(host: String, port: Long, timeoutMs: Long): WgNativeConn =
            onDial() ?: throw IllegalStateException("no conn returned")
        override fun listenUdp(): WgNativeUdpConn = throw IOException("not used")
        override fun startSocksListener(): Long = 0
        override fun listenTcp(port: Long): WgNativeListener = throw IOException("not used")
        override fun bindAddr(): String = "10.1.0.2"
        override fun close() = onClose()
    }

    private fun profile(tunnelConfigId: String?) = sh.haven.core.data.db.entities.ConnectionProfile(
        label = "p", host = "h", username = "u", connectionType = "SSH",
        tunnelConfigId = tunnelConfigId,
    )
}