package sh.haven.core.mosh

/**
 * Out-of-band liveness probe for a mosh session.
 *
 * The transport cannot tell a dead network from a dead server — both look
 * like receive silence — so before declaring a silent session dead (#421)
 * the host asks the server directly whether the session's UDP port is still
 * listened on. The check runs over the profile's SSH channel, which is the
 * same channel the mosh bootstrap used, and requires no server-side setup.
 *
 * Three verdicts:
 *  - `true`  — reached the server and the session's port is free: the
 *    mosh-server process is gone, the session is dead.
 *  - `false` — reached the server and the port is still bound: the session
 *    lives, keep waiting for the network.
 *  - `null`  — unknown: the server could not be reached (network down,
 *    server down) or the check could not run. Never treat as dead — a
 *    phone in a pocket must resume (#92).
 */
class MoshServerSessionProbe(
    /**
     * Executes a command on the session's server and returns its outcome.
     * Wired by the host to the profile's SSH client; must return a result
     * with [ProbeExecResult.exitStatus] null when the SSH exec itself failed
     * (unreachable server), and set [ProbeExecResult.timedOut] when the
     * command was aborted at the deadline.
     */
    private val exec: suspend (command: String, timeoutMs: Long?) -> ProbeExecResult,
) {
    suspend fun isServerSessionDead(port: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean? {
        val result = runCatching { exec(portCheckCommand(), timeoutMs) }.getOrNull() ?: return null
        if (result.timedOut) return null
        if (result.exitStatus == null) return null
        if (result.exitStatus != 0) return null
        return !portListened(result.output, port)
    }

    data class ProbeExecResult(
        /** Process exit status, or null when the SSH exec itself failed. */
        val exitStatus: Int?,
        val output: String,
        val timedOut: Boolean = false,
    )

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /**
         * Lists UDP listeners portably: `ss` (Linux), `netstat` (macOS/BSD),
         * `lsof` (last resort). Any of the three producing a listing is
         * enough; their failures are suppressed so the chain can fall through.
         */
        internal fun portCheckCommand(): String =
            "(ss -uln 2>/dev/null || netstat -un 2>/dev/null || lsof -nP -iUDP 2>/dev/null)"

        /**
         * True when [output] shows the port bound as a listener. The
         * negative digit lookahead keeps `:6000` from matching a check for
         * port 60001 (and the colon prefix keeps an IPv6 group from matching
         * a port — five hex digits cannot straddle a group boundary).
         */
        internal fun portListened(output: String, port: Int): Boolean =
            Regex(":${port}(?!\\d)").containsMatchIn(output)
    }
}
