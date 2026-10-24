package sh.haven.core.ssh

/**
 * Pluggable keep-alive signal for [SshConnectionService].
 *
 * Anything that needs the FGS to stay running but isn't a session
 * (e.g. the MCP agent endpoint exposed by the app module) implements
 * this interface and contributes it via Hilt `@IntoSet`. The FGS-
 * lifecycle path in `SessionManagerRegistry.hasActiveSessions` ORs
 * across every contributor.
 *
 * Two contributors today:
 *  - The MCP HTTP endpoint (in `:app`).
 *  - (none else yet — Wayland compositor uses the JNI singleton, not
 *    the FGS, and would route through here if/when that changes.)
 */
interface ForegroundKeepAlive {
    val isActive: Boolean

    /**
     * #757: emits whenever [isActive] may have changed, so the foreground
     * notification can follow the keep-alive up and down. Default never emits:
     * the MCP endpoint's transitions are already covered by the service's own
     * mcpStatusHolder collection, and AnyConnect's by the Tunnels screen's
     * explicit stop path.
     */
    val changes: kotlinx.coroutines.flow.Flow<Unit>
        get() = kotlinx.coroutines.flow.emptyFlow()
}
