package sh.haven.core.mosh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.mosh.crypto.MoshCrypto
import sh.haven.mosh.network.MoshConnection

/** A recorded mosh session: server port plus the session key that client and server share. */
data class MoshSessionRecord(val port: Int, val sessionKey: String)

/**
 * Orphan-mosh cleanup at NEW connect time (Dez's idea, 2026-11-05).
 *
 * Problem: when the client goes away (tab closed, phone died, network lost)
 * the server-side mosh-server has no idea — mosh has no client-shutdown
 * packet — and it lingers as an orphan until the server's 7-day network
 * timeout. Orphans pile up on the host.
 *
 * Fix: the client remembers the port AND session key of every session it
 * started. On the NEXT connect of the same profile — and ONLY there, never
 * on network recovery — it asks the server over the bootstrap SSH channel
 * "is that port still bound?" and verifies ownership by probing the port
 * with its own session key: a server holding the same key answers, any
 * other process (another device's waiting session) silently drops the
 * packet. Only a verified-ours process is killed. All fact-based, no
 * timers: the check is a probe, the kill is a direct command.
 *
 * Safety conditions (Dez, 2026-11-05 — hard requirements):
 *  1. Runs only when a NEW session is being created, never on reconnect /
 *     network recovery.
 *  2. Never kills a session that is alive on both ends.
 *  3. Never kills sessions of parallel tabs.
 *  4. Never kills a "waiting" session (one that rode out an outage and is
 *     waiting for the network to return).
 *  5. Never kills another device's session (port reuse included) — the key
 *     probe makes this structural: without a matching key there is no
 *     answer, and without an answer there is no kill.
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
     * The stored ports that are cleanup candidates: set on disk and no live
     * session (any tab) currently uses them. Pure so the safety rules are
     * testable without a network.
     */
    fun candidateRecords(
        stored: List<MoshSessionRecord>,
        livePorts: Set<Int>,
    ): List<MoshSessionRecord> =
        stored.filter { it.port > 0 && it.port !in livePorts && it.sessionKey.isNotBlank() }

    /**
     * Ownership probe: send one encrypted packet to [port] with the stored
     * session key. The server answers only when it holds the SAME key — a
     * foreign process (or a free port) stays silent. Safe: a foreign waiting
     * session cannot even decrypt the probe, so nothing is disturbed.
     */
    suspend fun probeSessionAlive(
        host: String,
        port: Int,
        sessionKey: String,
        timeoutMs: Long = 1_500L,
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: MoshConnection? = null
        try {
            val crypto = MoshCrypto(sessionKey)
            connection = MoshConnection(
                serverIp = host,
                port = port,
                crypto = crypto,
                // Fresh random nonce range: the old client transport already
                // consumed low nonces, and a server-side replay filter must
                // not mistake our probe for a duplicate of the dead session.
                initialSendNonceSeq = (System.currentTimeMillis() shl 8) and Long.MAX_VALUE,
            )
            connection.sendProbePacket()
            connection.receiveInstruction(timeoutMs.toInt()) != null
        } catch (e: Exception) {
            false
        } finally {
            runCatching { connection?.close() }
        }
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
