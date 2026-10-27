package sh.haven.app.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import sh.haven.core.data.agent.McpStatusHolder
import sh.haven.core.data.agent.NearCarrierStatus
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject
import javax.inject.Singleton
import sh.haven.core.redact.LogRedact

private const val TAG = "McpNearCarrier"

/**
 * Carries MCP over the SSH session the user's normal *interactive* Haven
 * connection to [UserPreferencesRepository.mcpTunnelEndpointProfileId]
 * already authenticates — instead of [McpTunnelManager]'s separate,
 * independently-authenticated headless session. That headless auth can't use
 * FIDO2 keys (no UI to drive a touch prompt from a background service) and
 * otherwise falls back to trying every non-SK key in the whole keystore,
 * including ones never meant for that host — the actual cause of a real
 * "MCP won't reconnect without a force-stop" bug this class fixes. MCP-over
 * -near is now available exactly when that profile has a CONNECTED,
 * interactive session — no separate credential to go stale.
 *
 * Pure observer, not a connect-loop: [SshSessionManager] already owns
 * reconnecting the interactive session (with the user's normal auth, FIDO2
 * touch and all); this just reacts to its state and applies the forward. The
 * forward is added **non-critical** ([SshSessionManager.PortForwardInfo.critical]
 * = false) — a bind failure here must never tear down the user's actual
 * terminal session, only leave MCP-over-near down (WireGuard remains the
 * primary, always-on carrier regardless).
 *
 * [McpTunnelManager]'s own headless session is unchanged and keeps carrying
 * adb-forwarding and guest-MCP-service forwarding — this class only takes
 * over the MCP forward itself.
 */
@Singleton
class McpNearCarrier @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val connectionRepository: ConnectionRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val mcpStatusHolder: McpStatusHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // The session a forward was last successfully applied to — avoids
    // re-applying (a cheap no-op on the SSH side, but noisy) on every
    // unrelated `sessions` emission once already up for the live session.
    private var appliedSessionId: String? = null

    /**
     * Start (or restart) watching for the configured endpoint's interactive
     * session. [mcpPort] is the *remote-side* bind (what clients on the far
     * host dial — unchanged contract, 8730–8739); [tunneledPort] is the
     * phone-side target: the MCP server's dedicated tunneled-origin listener,
     * NOT the device-trusted loopback binder, so traffic arriving through the
     * `-R` forward can never inherit loopback auto-trust (#mcp-backbone
     * Stage 2).
     */
    fun start(mcpPort: Int, tunneledPort: Int) {
        job?.cancel()
        appliedSessionId = null
        job = scope.launch {
            combine(
                preferencesRepository.mcpTunnelEndpointProfileId,
                sshSessionManager.sessions,
            ) { profileId, sessions -> profileId to sessions }
                .distinctUntilChanged()
                .collectLatest { (profileId, sessions) ->
                    reconcile(profileId, sessions, mcpPort, tunneledPort)
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        appliedSessionId = null
        mcpStatusHolder.setNearCarrier(NearCarrierStatus())
    }

    private suspend fun reconcile(
        profileId: String?,
        sessions: Map<String, SshSessionManager.SessionState>,
        mcpPort: Int,
        tunneledPort: Int,
    ) {
        val prevId = appliedSessionId
        when (val decision = decideNearCarrier(profileId, sessions, prevId)) {
            is NearCarrierDecision.NoProfileConfigured -> {
                if (prevId != null) {
                    sshSessionManager.forgetPortForward(prevId, MCP_REVERSE_TUNNEL_RULE_ID)
                }
                appliedSessionId = null
                mcpStatusHolder.setNearCarrier(NearCarrierStatus())
            }
            is NearCarrierDecision.NoInteractiveSession -> {
                if (prevId != null) {
                    sshSessionManager.forgetPortForward(prevId, MCP_REVERSE_TUNNEL_RULE_ID)
                }
                appliedSessionId = null
                val label = connectionRepository.getById(decision.profileId)?.label
                mcpStatusHolder.setNearCarrier(
                    NearCarrierStatus(active = false, profileId = decision.profileId, profileLabel = label),
                )
            }
            is NearCarrierDecision.AlreadyRiding -> { /* nothing to do */ }
            is NearCarrierDecision.ShouldApplyTo -> {
                val session = decision.session
                if (prevId != null && prevId != session.sessionId) {
                    sshSessionManager.forgetPortForward(prevId, MCP_REVERSE_TUNNEL_RULE_ID)
                }
                val forward = SshSessionManager.PortForwardInfo(
                    ruleId = MCP_REVERSE_TUNNEL_RULE_ID,
                    type = SshSessionManager.PortForwardType.REMOTE,
                    bindAddress = "127.0.0.1",
                    bindPort = mcpPort,
                    targetHost = "127.0.0.1",
                    // The tunneled-origin listener, NOT the device-trusted
                    // loopback binder (#mcp-backbone Stage 2).
                    targetPort = tunneledPort,
                    critical = false,
                    selfHealOnBindFailure = true,
                )
                // Mark appliedSessionId before applyPortForwards mutates _sessions
                // via recordActiveForward; otherwise collectLatest cancels reconcile
                // while recordTunnelSshPid is still running and re-invokes
                // applyPortForwards on the same session a second time.
                appliedSessionId = session.sessionId
                val ok = try {
                    sshSessionManager.applyPortForwards(session.sessionId, listOf(forward))
                } catch (t: Throwable) {
                    appliedSessionId = null
                    throw t
                }
                if (ok) {
                    Log.i(TAG, "MCP now riding the interactive session for ${LogRedact.of(session.label)} (-R $mcpPort)")
                    mcpStatusHolder.setNearCarrier(
                        NearCarrierStatus(active = true, profileId = session.profileId, profileLabel = session.label),
                    )
                } else {
                    appliedSessionId = null
                    Log.w(TAG, "MCP forward failed to bind on the interactive session for ${LogRedact.of(session.label)} — leaving MCP-over-near down")
                    mcpStatusHolder.setNearCarrier(
                        NearCarrierStatus(active = false, profileId = session.profileId, profileLabel = session.label),
                    )
                }
            }
        }
    }

    private companion object {
        const val MCP_REVERSE_TUNNEL_RULE_ID = "mcp-reverse-tunnel"
    }
}

/** [McpNearCarrier.reconcile]'s decision, given the current [SshSessionManager] state — pure, so it's unit-testable without a coroutine scope. */
internal sealed interface NearCarrierDecision {
    data object NoProfileConfigured : NearCarrierDecision
    data class NoInteractiveSession(val profileId: String) : NearCarrierDecision
    data class AlreadyRiding(val session: SshSessionManager.SessionState) : NearCarrierDecision
    data class ShouldApplyTo(val session: SshSessionManager.SessionState) : NearCarrierDecision
}

/**
 * Which session (if any) should carry MCP for [profileId], given the live
 * [sessions] map and the session id a forward was last applied to
 * ([currentAppliedSessionId], to avoid redundant re-applies). Only an
 * **interactive** (non-headless) CONNECTED session for that profile
 * qualifies — deliberately excludes [McpTunnelManager]'s own headless
 * session for the same profileId (adb/guest-service forwarding), which this
 * carrier doesn't touch.
 */
internal fun decideNearCarrier(
    profileId: String?,
    sessions: Map<String, SshSessionManager.SessionState>,
    currentAppliedSessionId: String?,
): NearCarrierDecision {
    if (profileId.isNullOrBlank()) return NearCarrierDecision.NoProfileConfigured
    // Prefer staying on currentAppliedSessionId while it remains an eligible
    // CONNECTED session for profileId. Otherwise, when a second session earlier
    // in map iteration order finishes reconnecting alongside currentAppliedSessionId,
    // firstOrNull would hop back to the earlier session and trigger a self-heal
    // bind duel between the two sessions.
    val current = currentAppliedSessionId?.let { sessions[it] }
    if (current != null &&
        current.profileId == profileId &&
        !current.headless &&
        current.status == SshSessionManager.SessionState.Status.CONNECTED
    ) {
        return NearCarrierDecision.AlreadyRiding(current)
    }
    val session = sessions.values.firstOrNull {
        it.profileId == profileId &&
            !it.headless &&
            it.status == SshSessionManager.SessionState.Status.CONNECTED
    } ?: return NearCarrierDecision.NoInteractiveSession(profileId)
    return NearCarrierDecision.ShouldApplyTo(session)
}
