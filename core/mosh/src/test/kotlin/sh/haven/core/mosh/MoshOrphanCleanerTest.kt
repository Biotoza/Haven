package sh.haven.core.mosh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshOrphanCleanerTest {

    private fun cleaner(
        responses: MutableList<MoshServerSessionProbe.ProbeExecResult>,
    ) = MoshOrphanCleaner { _, _ -> responses.removeAt(0) }

    private fun exec(exitStatus: Int?, output: String, timedOut: Boolean = false) =
        MoshServerSessionProbe.ProbeExecResult(exitStatus = exitStatus, output = output, timedOut = timedOut)

    // ---- candidate selection (Dez's safety conditions, 2026-11-05) ----

    @Test
    fun noStoredPort_noCandidate() {
        assertNull(MoshOrphanCleaner { _, _ -> exec(0, "") }.candidatePort(null, setOf(60001)))
        assertNull(MoshOrphanCleaner { _, _ -> exec(0, "") }.candidatePort(0, emptySet()))
    }

    @Test
    fun storedPortStillLive_parallelTab_noCandidate() {
        // Condition 3: a parallel tab's session is alive — its port is in the
        // live set and must NEVER become a candidate.
        assertNull(MoshOrphanCleaner { _, _ -> exec(0, "") }.candidatePort(60001, setOf(60001)))
    }

    @Test
    fun storedPortNotLive_candidate() {
        // The target case: app restarted / tab closed — port on disk, no live
        // session holds it. (Conditions 2 and 4 hold structurally: a live or
        // waiting session always has its transport in memory.)
        assertEquals(60001, MoshOrphanCleaner { _, _ -> exec(0, "") }.candidatePort(60001, setOf(60010)))
    }

    // ---- cleanup flow ----

    @Test
    fun portNotBound_noKill() = runBlocking {
        val responses = mutableListOf(exec(exitStatus = 0, output = "no listeners here"))
        val result = cleaner(responses).cleanupOrphan(60001)
        assertFalse(result)
        assertEquals(0, responses.size) // kill was never attempted
    }

    @Test
    fun probeExecFails_noKill() = runBlocking {
        val responses = mutableListOf(exec(exitStatus = null, output = ""))
        assertFalse(cleaner(responses).cleanupOrphan(60001))
    }

    @Test
    fun probeTimedOut_noKill() = runBlocking {
        val responses = mutableListOf(exec(exitStatus = 0, output = "", timedOut = true))
        assertFalse(cleaner(responses).cleanupOrphan(60001))
    }

    @Test
    fun portBound_killDispatched() = runBlocking {
        // First exec = port check (bound), second = kill pipeline (ran).
        val responses = mutableListOf(
            exec(exitStatus = 0, output = "udp UNCONN 0 0 100.69.12.2:60001 users:((\"mosh-server\",pid=42,fd=4))"),
            exec(exitStatus = 0, output = ""),
        )
        assertTrue(cleaner(responses).cleanupOrphan(60001))
        assertEquals(0, responses.size)
    }

    @Test
    fun killExecFails_stillCountsAsDispatched() = runBlocking {
        // The kill pipeline returns a status, but kill of an already-dead pid
        // may be non-zero — dispatch, not status, decides the result.
        val responses = mutableListOf(
            exec(exitStatus = 0, output = "100.69.12.2:60001 pid=42"),
            exec(exitStatus = 1, output = ""),
        )
        assertTrue(cleaner(responses).cleanupOrphan(60001))
    }

    // ---- commands ----

    @Test
    fun killCommandTargetsExactPort() {
        val cmd = MoshOrphanCleaner.killCommand(60001)
        assertTrue(cmd.contains("fuser -k -TERM 60001/udp"))
        assertTrue(cmd.contains("grep ':60001 '"))
        assertTrue(cmd.trimEnd().endsWith("true")) // pipeline never fails hard
    }

    @Test
    fun killCommandDoesNotMatchLongerPorts() {
        // Port 6000 must not match a listener on 60001 (mirrors the probe's
        // negative-digit-lookahead rule, encoded here as the trailing space).
        assertTrue(MoshOrphanCleaner.killCommand(6000).contains("grep ':6000 '"))
    }
}
