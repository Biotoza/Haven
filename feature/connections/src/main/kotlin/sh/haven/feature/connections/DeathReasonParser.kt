package sh.haven.feature.connections

/**
 * hd9.1: derive a human-readable death reason from the mosh transport trace.
 * The trace is a multi-line log; the last meaningful line (usually a verdict
 * or an error) is the reason shown on the death banner and written into the
 * event journal. Falls back to a generic phrase when the trace is empty or
 * unparseable. Kept as a top-level function so it is unit-testable without
 * constructing the view model.
 */
internal fun extractDeathReason(trace: String?): String {
    if (trace.isNullOrBlank()) return "transport declared dead"
    val lines = trace.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return "transport declared dead"
    // Prefer the last line that looks like a verdict or an error.
    val verdictLine = lines.lastOrNull { line ->
        val l = line.lowercase()
        l.contains("verdict") || l.contains("dead") || l.contains("error") ||
            l.contains("silence") || l.contains("stall")
    }
    return verdictLine?.take(120) ?: lines.last().take(120)
}
