package sh.haven.app.agent

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager

/**
 * Coverage for [decideNearCarrier] — the pure decision logic behind
 * [McpNearCarrier], which fixes a real "MCP won't reconnect without a
 * force-stop" bug by riding the user's interactive session instead of a
 * separately-authenticated headless one. The one correctness detail these
 * pin: only an interactive (non-headless), CONNECTED session for the
 * configured profile qualifies — a headless session for the same profileId
 * (e.g. McpTunnelManager's own adb/guest-service tunnel) must never be
 * mistaken for it.
 */
class McpNearCarrierTest {

    private fun session(
        sessionId: String = "s1",
        profileId: String = "p1",
        status: SshSessionManager.SessionState.Status = SshSessionManager.SessionState.Status.CONNECTED,
        headless: Boolean = false,
        label: String = "dev machine",
    ) = SshSessionManager.SessionState(
        sessionId = sessionId,
        profileId = profileId,
        label = label,
        status = status,
        client = mockk<SshClient>(relaxed = true),
        headless = headless,
    )

    @Test
    fun `no profile configured means nothing to do`() {
        val decision = decideNearCarrier(profileId = null, sessions = emptyMap(), currentAppliedSessionId = null)
        assertEquals(NearCarrierDecision.NoProfileConfigured, decision)
    }

    @Test
    fun `blank profile id is treated the same as unset`() {
        val decision = decideNearCarrier(profileId = "  ", sessions = emptyMap(), currentAppliedSessionId = null)
        assertEquals(NearCarrierDecision.NoProfileConfigured, decision)
    }

    @Test
    fun `a connected interactive session for the profile should be applied to`() {
        val s = session()
        val decision = decideNearCarrier("p1", mapOf(s.sessionId to s), currentAppliedSessionId = null)
        assertTrue(decision is NearCarrierDecision.ShouldApplyTo)
        assertEquals(s, (decision as NearCarrierDecision.ShouldApplyTo).session)
    }

    @Test
    fun `a headless session for the same profile is ignored — not mistaken for the interactive one`() {
        val headless = session(sessionId = "s-headless", headless = true)
        val decision = decideNearCarrier("p1", mapOf(headless.sessionId to headless), currentAppliedSessionId = null)
        assertEquals(NearCarrierDecision.NoInteractiveSession("p1"), decision)
    }

    @Test
    fun `an interactive session that is still connecting does not qualify yet`() {
        val connecting = session(status = SshSessionManager.SessionState.Status.CONNECTING)
        val decision = decideNearCarrier("p1", mapOf(connecting.sessionId to connecting), currentAppliedSessionId = null)
        assertEquals(NearCarrierDecision.NoInteractiveSession("p1"), decision)
    }

    @Test
    fun `a session for a different profile is not picked`() {
        val other = session(profileId = "some-other-profile")
        val decision = decideNearCarrier("p1", mapOf(other.sessionId to other), currentAppliedSessionId = null)
        assertEquals(NearCarrierDecision.NoInteractiveSession("p1"), decision)
    }

    @Test
    fun `already riding the exact same session is a no-op, not a re-apply`() {
        val s = session()
        val decision = decideNearCarrier("p1", mapOf(s.sessionId to s), currentAppliedSessionId = s.sessionId)
        assertTrue(decision is NearCarrierDecision.AlreadyRiding)
        assertEquals(s, (decision as NearCarrierDecision.AlreadyRiding).session)
    }

    @Test
    fun `a reconnect that landed on a NEW session id should re-apply`() {
        val s = session(sessionId = "s-new")
        val decision = decideNearCarrier("p1", mapOf(s.sessionId to s), currentAppliedSessionId = "s-old")
        assertTrue(decision is NearCarrierDecision.ShouldApplyTo)
    }

    @Test
    fun `disconnecting clears NoInteractiveSession even if a stale applied id lingers`() {
        val decision = decideNearCarrier("p1", emptyMap(), currentAppliedSessionId = "s-old")
        assertEquals(NearCarrierDecision.NoInteractiveSession("p1"), decision)
        assertNull((decision as? NearCarrierDecision.ShouldApplyTo)?.session)
    }

    @Test
    fun `keeps riding currentAppliedSessionId when an earlier session in map order also becomes CONNECTED`() {
        val s1 = session(sessionId = "s1", profileId = "p1")
        val s2 = session(sessionId = "s2", profileId = "p1")
        // Map iteration order has s1 first, but s2 is already carrying MCP.
        // When s1 finishes reconnecting and becomes CONNECTED alongside s2,
        // decideNearCarrier must stay on s2 instead of hopping back to s1
        // and triggering a self-heal kill loop between the two sessions.
        val sessions = linkedMapOf(s1.sessionId to s1, s2.sessionId to s2)
        val decision = decideNearCarrier("p1", sessions, currentAppliedSessionId = s2.sessionId)
        assertEquals(NearCarrierDecision.AlreadyRiding(s2), decision)
    }
}
