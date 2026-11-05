package sh.haven.core.mosh

/**
 * Orphan-mosh cleanup at NEW connect time (Dez's idea, 2026-11-05).
 *
 * Problem: when the client goes away (tab closed, phone died, network lost)
 * the server-side mosh-server has no idea — mosh has no client-shutdown
 * packet — and it lingers as an orphan until the server's 7-day network
 * timeout. Orphans pile up on the host.
 *
 * Fix: the client remembers the port of the session it started. On the NEXT
 * connect of the same profile — and ONLY there, never on network recovery —
 * it asks the server over the bootstrap SSH channel "is that port still
 * bound?" If yes, that process has no client anymore (re-attach is impossible
 * anyway, #371), so the client tells the server to kill it. All fact-based,
 * no timers: the check is a probe, the kill is a direct command.
 *
 * Safety conditions (Dez, 2026-11-05 — hard requirements):
 *  1. Runs only when a NEW session is being created, never on reconnect /
 *     network recovery.
 *  2. Never kills a session that is alive on both ends.
 *  3. Never kills sessions of parallel tabs.
 *  4. Never kills a "waiting" session (one that rode out an outage and is
 *     waiting for the network to return).
 *
 * Conditions 2-4 hold structurally: only a port that is stored on disk AND
 * absent from the in-memory live-session set is a candidate. A live or
 * waiting session always has its transport in memory, so its port can never
 * become a candidate. A stored-but-not-live port can only belong to a
 * session whose client is really gone (app restarted, tab closed) — exactly
 * the orphan case.
 */
class MoshOrphanCleaner(
    /**
     * Executes a command on the server over the profile's bootstrap SSH
     * channel. Mirrors [MoshServerSessionProbe]'s exec contract.
     */
    private val exec: suspend (command: String, timeoutMs: Long?) -> MoshServerSessionProbe.ProbeExecResult,
) {

    /**
     * The stored port is a cleanup candidate iff it is set and no live
     * session (any tab) currently uses it. Pure so the safety rules are
     * testable without a network.
     */
    fun candidatePort(storedPort: Int?, livePorts: Set<Int>): Int? {
        if (storedPort == null || storedPort <= 0) return null
        if (storedPort in livePorts) return null
        return storedPort
    }

    /**
     * Best-effort removal of the orphaned mosh-server bound to [port].
     *
     *  1. Probe whether the port is still bound (reuse the portable port
     *     check). Not bound = nothing to do (the process already died).
     *  2. If bound, send a kill targeting exactly that port's process.
     *
     * Returns true when a kill command was dispatched. Never throws — a
     * failed exec (server unreachable) is simply "nothing done": the next
     * connect will try again. No waits, no timers: the fact of the port
     * being gone is verified by the probe on the next connect, not here.
     */
    suspend fun cleanupOrphan(port: Int, timeoutMs: Long = MoshServerSessionProbe.DEFAULT_TIMEOUT_MS): Boolean {
        val probeResult = runCatching { exec(portCheckCommand(), timeoutMs) }.getOrNull() ?: return false
        if (probeResult.timedOut) return false
        if (probeResult.exitStatus == null || probeResult.exitStatus != 0) return false
        if (!MoshServerSessionProbe.portListened(probeResult.output, port)) return false

        val killResult = runCatching { exec(killCommand(port), timeoutMs) }.getOrNull() ?: return false
        if (killResult.exitStatus == null) return false
        // Exit status of the kill pipeline is not a strong signal (kill of an
        // already-dead pid fails), so treat "command ran" as dispatched.
        return true
    }

    companion object {
        /** Port-check command shared with [MoshServerSessionProbe]. */
        internal fun portCheckCommand(): String = MoshServerSessionProbe.portCheckCommand()

        /**
         * Kill the process listening on [port]/udp, targeting ONLY that port.
         *
         * Primary: `fuser -k -TERM PORT/udp` (psmisc, available on most
         * Linux hosts). Fallback: parse the pid out of `ss -ulnp` /
         * `netstat -unp` / `lsof -nP -iUDP` and kill it. Trailing `true`
         * keeps the pipeline exit status zero when nothing matched, so the
         * cleanup result does not depend on whether a pid was found.
         */
        internal fun killCommand(port: Int): String =
            "fuser -k -TERM $port/udp 2>/dev/null; " +
                "pids=\$( (ss -ulnp 2>/dev/null || netstat -unp 2>/dev/null || lsof -nP -iUDP 2>/dev/null) " +
                "| grep ':$port ' | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u ); " +
                "[ -n \"\$pids\" ] && kill \$pids; true"
    }
}
