package sh.haven.core.tunnel

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelAuthBrokerTest {

    private val broker = TunnelAuthBroker(mockk(relaxed = true))

    @Test
    fun `context line names the account and the tunnel`() {
        // A bare "Password" field must say whose password it wants (#750).
        assertEquals("alice @ Corp VPN", broker.destinationLine("alice", "Corp VPN"))
        assertEquals("alice @ Corp VPN", broker.destinationLine(" alice ", "Corp VPN"))
    }

    @Test
    fun `falls back to the tunnel name, then to VPN`() {
        assertEquals("Corp VPN", broker.destinationLine("", "Corp VPN"))
        assertEquals("alice @ VPN", broker.destinationLine("alice", ""))
        assertEquals("VPN", broker.destinationLine("", " "))
    }
}
