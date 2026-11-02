package sh.haven.core.mosh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.mosh.MoshServerSessionProbe.ProbeExecResult

/**
 * Policy tests for the out-of-band session liveness probe. No Android or
 * network involved: the exec channel is a fake, the parsing is the unit.
 */
class MoshServerSessionProbeTest {

    private val ssLine = "UNCONN 0 0 0.0.0.0:60001 0.0.0.0:* users:((\"mosh-server\",pid=31337,fd=5))"
    private val netstatLine = "udp        0      0 0.0.0.0:60001            0.0.0.0:*"
    private val lsofLine = "mosh-serv 31337 kali 5u IPv4 12345 0t0 UDP *:60001"

    private fun probe(
        output: String,
        exitStatus: Int? = 0,
        timedOut: Boolean = false,
    ) = MoshServerSessionProbe(exec = { _, _ -> ProbeExecResult(exitStatus, output, timedOut) })

    /** The reported case: reached the server, the session's port is free. */
    @Test
    fun `declares dead when the server was reached and the port is free`() = runBlocking {
        assertEquals(true, probe("UNCONN 0 0 0.0.0.0:5353 0.0.0.0:*").isServerSessionDead(60001))
    }

    /** The session is alive: the port is bound. Parses all three tool formats. */
    @Test
    fun `reports alive for ss, netstat and lsof listings`() = runBlocking {
        assertEquals(false, probe(ssLine).isServerSessionDead(60001))
        assertEquals(false, probe(netstatLine).isServerSessionDead(60001))
        assertEquals(false, probe(lsofLine).isServerSessionDead(60001))
    }

    /** An SSH exec that never completed is unknown, not dead. */
    @Test
    fun `timed-out probe is unknown`() = runBlocking {
        assertNull(probe("", timedOut = true).isServerSessionDead(60001))
    }

    /** An SSH-level failure (network down, server unreachable) is unknown. */
    @Test
    fun `failed ssh exec is unknown`() = runBlocking {
        assertNull(probe("", exitStatus = null).isServerSessionDead(60001))
    }

    /** The check itself failing (no ss/netstat/lsof) is unknown, never dead. */
    @Test
    fun `probe tool failure is unknown`() = runBlocking {
        assertNull(probe("", exitStatus = 127).isServerSessionDead(60001))
    }

    /** An exception from the exec channel (e.g. not connected) is unknown. */
    @Test
    fun `throwing exec is unknown`() = runBlocking {
        val throwing = MoshServerSessionProbe(exec = { _, _ -> throw IllegalStateException("Not connected") })
        assertNull(throwing.isServerSessionDead(60001))
    }

    /** Port parsing must not confuse shorter/longer ports. */
    @Test
    fun `port parsing does not match neighbouring ports`() = runBlocking {
        // Port 60001 bound; asking about 6000 or 600 must not match.
        assertEquals(true, probe(ssLine).isServerSessionDead(6000))
        assertEquals(true, probe(ssLine).isServerSessionDead(600))
        // Port 6000 bound; asking about 60001 must not match it.
        assertEquals(true, probe(ssLine.replace("60001", "6000")).isServerSessionDead(60001))
    }

    /** The command is the portable three-tool chain. */
    @Test
    fun `port check command covers ss, netstat and lsof`() {
        val cmd = MoshServerSessionProbe.portCheckCommand()
        assertTrue(cmd.contains("ss -uln"))
        assertTrue(cmd.contains("netstat -un"))
        assertTrue(cmd.contains("lsof -nP -iUDP"))
    }

    /** Boundary sanity: the lookahead keeps a 4-digit port from a 5-digit one. */
    @Test
    fun `port listing boundary is respected`() {
        assertTrue(MoshServerSessionProbe.portListened("x:60001 ", 60001))
        assertFalse(MoshServerSessionProbe.portListened("x:600011", 60001))
        assertFalse(MoshServerSessionProbe.portListened("x:160001 ", 60001))
    }
}
