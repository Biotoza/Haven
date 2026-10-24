package sh.haven.core.ssh

/**
 * Minimal view of an active session as it appears in the foreground notification.
 * Only the fields the notification needs — transport-specific session state stays
 * in each manager's own SessionState type.
 */
interface ForegroundSessionInfo {
    val profileId: String
    val label: String
}

/**
 * Implemented (via adapter or directly) by every session manager whose sessions
 * count as "active connections" for the foreground notification. SshConnectionService
 * iterates the full Set of participants — adding a new transport means contributing
 * one @IntoSet binding in ForegroundSessionParticipantModule, not editing the service.
 */
interface ForegroundSessionParticipant {
    /** Sessions in CONNECTED / CONNECTING / RECONNECTING state. */
    val activeSessions: List<ForegroundSessionInfo>

    /**
     * #757: emits whenever the active-session set may have changed, so the
     * foreground notification can be re-posted when a session ENDS, not only
     * when one starts. Before this the notification was built at startForeground
     * and on MCP activity only, so a session ending on its own (typed `exit`,
     * dropped transport) left the count and labels stale. Adapters back this
     * with their manager's session StateFlow; the default never emits, so a
     * participant that can't observe changes simply doesn't drive re-posts
     * (the MCP endpoint participant is covered by the service's own
     * mcpStatusHolder collection).
     */
    val changes: kotlinx.coroutines.flow.Flow<Unit>
        get() = kotlinx.coroutines.flow.emptyFlow()

    /** Tear down all sessions; called on Disconnect All and on service destruction. */
    fun disconnectAll()
}
