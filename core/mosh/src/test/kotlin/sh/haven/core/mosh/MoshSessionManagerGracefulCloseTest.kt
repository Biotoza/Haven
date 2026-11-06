package sh.haven.core.mosh

import android.content.ContextWrapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the close-time graceful close (Dez 2026-11-06): EOF first so the
 * remote shell ends the session cleanly, then — only when the session is
 * still alive after the grace period — a port-targeted kill as guarantee.
 *
 * Pinned invariants:
 *  1. A session that ends cleanly during the grace NEVER gets a kill.
 *  2. A session that survives the grace (EOF ignored — vim, hung process)
 *     gets exactly one port-targeted kill.
 *  3. Unknown sessions are a no-op (no throw, no exec).
 */
class MoshSessionManagerGracefulCloseTest {

    // Context only feeds the constructor; the tested paths (registerSession,
    // connectSession, gracefulClose) never touch it.
    private val manager = MoshSessionManager(ContextWrapper(null))

    private val zeroKey = "AAAAAAAAAAAAAAAAAAAAAA=="

    private fun connectedId(port: Int = 60001): String {
        val id = manager.registerSession("test-profile", "test")
        manager.connectSession(id, "127.0.0.1", port, zeroKey, 80, 24)
        return id
    }

    private fun execRecording(commands: MutableList<String>): suspend (String, Long?) -> MoshServerSessionProbe.ProbeExecResult =
        { cmd, _ ->
            commands.add(cmd)
            MoshServerSessionProbe.ProbeExecResult(exitStatus = 0, output = "")
        }

    @Test
    fun `clean exit during grace - no kill sent`() = runBlocking {
        val id = connectedId()
        val commands = mutableListOf<String>()
        // The remote exits cleanly ~100ms after the EOF: the clean-exit path
        // removes the session from the map.
        launch { delay(100); manager.removeSession(id) }
        manager.gracefulClose(id, execRecording(commands), eofGraceMs = 5_000L)
        assertTrue("clean exit must not send a kill", commands.isEmpty())
    }

    @Test
    fun `EOF ignored - session alive after grace - one port kill sent`() = runBlocking {
        val id = connectedId(port = 60007)
        val commands = mutableListOf<String>()
        manager.gracefulClose(id, execRecording(commands), eofGraceMs = 200L)
        assertEquals(1, commands.size)
        assertTrue("kill must target the session's port", commands[0].contains("60007/udp"))
    }

    @Test
    fun `unknown session - nothing sent, nothing thrown`() = runBlocking {
        val commands = mutableListOf<String>()
        manager.gracefulClose("no-such-session", execRecording(commands), eofGraceMs = 50L)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `exec failure - gracefulClose swallows it`() = runBlocking {
        val id = connectedId(port = 60009)
        val exec: suspend (String, Long?) -> MoshServerSessionProbe.ProbeExecResult =
            { _, _ -> throw RuntimeException("ssh gone") }
        // Must not throw: the caller removes the session regardless.
        manager.gracefulClose(id, exec, eofGraceMs = 50L)
    }
}
