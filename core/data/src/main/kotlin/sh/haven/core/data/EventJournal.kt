package sh.haven.core.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "EventJournal"

/**
 * App-internal event journal (hd9) — the on-device answer to "why did the
 * client reconnect / close a session?".
 *
 * The three legacy capture channels (server-side traps, Shizuku logcat,
 * adb-dependent polls) all go blind exactly when a session breaks: the
 * network is gone, the debug port moved, or the capture toggle did not
 * survive a reinstall. The journal lives INSIDE the app process and writes
 * to its own private storage, so it survives network loss, debug-port churn
 * and app restarts. It is opt-in (settings toggle, default OFF), exportable
 * by hand (share sheet), and never uploads anything anywhere — a plain
 * upstream-friendly diagnostics feature.
 *
 * Ring buffer: two files (active + one rotated), bounded, oldest data
 * discarded. Events are one line each:
 * `2026-11-13T21:28:44.123Z [tag] message` (UTC, for server-log correlation).
 *
 * Diagnostics must never be the reason the app fails: every write is
 * defensive and disabled-by-default.
 */
class EventJournal(private val context: Context, private val maxBytes: Long = MAX_BYTES) {

    private val file: File get() = File(context.filesDir, "event_journal.log")
    private val rotatedFile: File get() = File(context.filesDir, "event_journal.1.log")

    @Volatile
    private var enabled = false

    val isEnabled: Boolean get() = enabled

    /** Called on app start and whenever the settings toggle changes. */
    fun setEnabled(flag: Boolean) {
        enabled = flag
        if (flag) {
            log("journal", "enabled — process up (file=${file.name})")
        }
    }

    /**
     * Append one event line. No-op when disabled. Never throws — a full
     * disk or an unwritable files dir must not take the app down with it.
     */
    fun log(tag: String, message: String) {
        if (!enabled) return
        synchronized(this) {
            runCatching {
                rotateIfNeeded()
                file.appendText("${nowUtc()} [$tag] ${message.replace('\n', ' ')}\n")
            }.onFailure { e ->
                Log.w(TAG, "event journal write failed: ${e.message}")
            }
        }
    }

    /** The active log for manual export, or null when empty/absent. */
    fun exportFile(): File? = synchronized(this) {
        file.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Copies the journal (rotated + active) into a shareable cache file.
     * filesDir is deliberately outside the FileProvider surface (secrets
     * policy) — cache-path is the sanctioned sharing route, so the export
     * lands there and the caller shares the copy.
     */
    fun exportCopy(): File? = synchronized(this) {
        val active = file.takeIf { it.exists() && it.length() > 0 }?.readText().orEmpty()
        val rotated = rotatedFile.takeIf { it.exists() && it.length() > 0 }?.readText().orEmpty()
        if (active.isEmpty() && rotated.isEmpty()) return@synchronized null
        runCatching {
            File(context.cacheDir, "event_journal_export.log").apply {
                writeText(rotated + active)
            }
        }.getOrNull()
    }

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() < maxBytes) return
        runCatching {
            rotatedFile.delete()
            file.renameTo(rotatedFile)
            file.appendText("=== rotated at ${nowUtc()}, previous data in ${rotatedFile.name} ===\n")
        }
    }

    companion object {
        internal const val MAX_BYTES = 512L * 1024L

        internal fun nowUtc(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
    }
}
