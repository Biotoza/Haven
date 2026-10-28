package sh.haven.core.glkvm

import kotlin.math.roundToInt

/**
 * #GLKVM pure coordinate and keyboard-layout math for the GL.iNet GLKVM
 * (PiKVM kvmd fork).
 *
 * The device HID mouse takes signed 16-bit absolutes (`-32768..32767`) across
 * the full frame width and height (matching the GLKVM web UI's own `Hs`
 * mapping). An earlier calibration in `glkvm-mcp` had `X_OFF_FRAC = 0.05` and
 * `X_SPAN_FRAC = 0.90` because the target machine at the time was mirroring a
 * 16:10 display pillarboxed inside a 16:9 (2560x1440) HDMI signal
 * (`(16/10) / (16/9) = 0.90`). On a full-frame target display, that 5%/90%
 * rescaling shifts clicks horizontally by up to 5% of frameW (128px at 2560w).
 */
object GlkvmGeometry {
    const val X_OFF_FRAC = 0.0
    const val X_SPAN_FRAC = 1.0

    /** px → device int for the x axis (full width), clamped to int16. */
    fun vFromPxX(x: Double, frameW: Int): Int {
        val v = (x * 65535.0 / frameW - 32768.0).roundToInt()
        return v.coerceIn(-32768, 32767)
    }

    /** px → device int for the y axis (full height), clamped to int16. */
    fun vFromPxY(y: Double, frameH: Int): Int {
        val v = (y * 65535.0 / frameH - 32768.0).roundToInt()
        return v.coerceIn(-32768, 32767)
    }

    /** Inverse of [vFromPxX]/[vFromPxY] — device ints back to px. */
    fun pxFromV(vx: Int, vy: Int, frameW: Int, frameH: Int): Pair<Double, Double> {
        val x = frameW * (vx + 32768) / 65535.0
        val y = frameH * (vy + 32768) / 65535.0
        return x to y
    }

    /**
     * Wheel steps the device expects. Measured: one client wheel notch
     * down ≈ device delta_y = -3 (and +3 up). One POST per notch.
     */
    const val WHEEL_STEP = 3
}

/**
 * Characters typed through [charSequence]: maps each char to a
 * KeyboardEvent.code name (a *position* code — the target OS then applies
 * ITS layout to the physical position) plus whether Shift is held.
 *
 * kvmd's server-side printer (`/api/hid/print`) bakes in a US layout and
 * mangles punctuation on other targets (verified: `"` → `@`, `\` → `#` on a
 * UK Windows target, and the GL fork ignores its `layout=` query param), so
 * table coverage is how text is typed correctly. Chars outside the table do
 * not produce a key sequence — they still exist, but the caller must fall
 * back to the printer path (phase-2 verb), which is fine for accents.
 */
object GlkvmLayout {

    /** (code, shift) — `shift` describes the PRIMARY char of the entry. */
    data class Key(val code: String, val shift: Boolean)

    private val letters: Map<Char, Key> = buildMap {
        for (i in 0..25) {
            val lower = ('a' + i)
            val upper = ('A' + i)
            put(lower, Key("Key$upper", false))
            put(upper, Key("Key$upper", true))
        }
    }

    private val ukPunct: Map<Char, Key> = mapOf(
        ' ' to Key("Space", false), '\t' to Key("Tab", false), '\n' to Key("Enter", false),
        '1' to Key("Digit1", false), '!' to Key("Digit1", true),
        '2' to Key("Digit2", false), '"' to Key("Digit2", true),
        '3' to Key("Digit3", false),   // UK shifted 3 is £ — non-ASCII, printer path
        '4' to Key("Digit4", false), '$' to Key("Digit4", true),
        '5' to Key("Digit5", false), '%' to Key("Digit5", true),
        '6' to Key("Digit6", false), '^' to Key("Digit6", true),
        '7' to Key("Digit7", false), '&' to Key("Digit7", true),
        '8' to Key("Digit8", false), '*' to Key("Digit8", true),
        '9' to Key("Digit9", false), '(' to Key("Digit9", true),
        '0' to Key("Digit0", false), ')' to Key("Digit0", true),
        '-' to Key("Minus", false), '_' to Key("Minus", true),
        '=' to Key("Equal", false), '+' to Key("Equal", true),
        '[' to Key("BracketLeft", false), '{' to Key("BracketLeft", true),
        ']' to Key("BracketRight", false), '}' to Key("BracketRight", true),
        ';' to Key("Semicolon", false), ':' to Key("Semicolon", true),
        '\'' to Key("Quote", false), '@' to Key("Quote", true),
        // UK: the ANSI backslash position carries # and ~
        '#' to Key("Backslash", false), '~' to Key("Backslash", true),
        // ISO 102nd key: UK Windows maps it to \ (shift: |)
        '\\' to Key("IntlBackslash", false), '|' to Key("IntlBackslash", true),
        ',' to Key("Comma", false), '<' to Key("Comma", true),
        '.' to Key("Period", false), '>' to Key("Period", true),
        '/' to Key("Slash", false), '?' to Key("Slash", true),
        '`' to Key("Backquote", false), // UK shifted is ¬ — non-ASCII
    )

    private val usPunct: Map<Char, Key> = mapOf(
        ' ' to Key("Space", false), '\t' to Key("Tab", false), '\n' to Key("Enter", false),
        '1' to Key("Digit1", false), '!' to Key("Digit1", true),
        '2' to Key("Digit2", false), '@' to Key("Digit2", true),
        '3' to Key("Digit3", false), '#' to Key("Digit3", true),
        '4' to Key("Digit4", false), '$' to Key("Digit4", true),
        '5' to Key("Digit5", false), '%' to Key("Digit5", true),
        '6' to Key("Digit6", false), '^' to Key("Digit6", true),
        '7' to Key("Digit7", false), '&' to Key("Digit7", true),
        '8' to Key("Digit8", false), '*' to Key("Digit8", true),
        '9' to Key("Digit9", false), '(' to Key("Digit9", true),
        '0' to Key("Digit0", false), ')' to Key("Digit0", true),
        '-' to Key("Minus", false), '_' to Key("Minus", true),
        '=' to Key("Equal", false), '+' to Key("Equal", true),
        '[' to Key("BracketLeft", false), '{' to Key("BracketLeft", true),
        ']' to Key("BracketRight", false), '}' to Key("BracketRight", true),
        ';' to Key("Semicolon", false), ':' to Key("Semicolon", true),
        '\'' to Key("Quote", false), '"' to Key("Quote", true),
        '\\' to Key("Backslash", false), '|' to Key("Backslash", true),
        ',' to Key("Comma", false), '<' to Key("Comma", true),
        '.' to Key("Period", false), '>' to Key("Period", true),
        '/' to Key("Slash", false), '?' to Key("Slash", true),
        '`' to Key("Backquote", false), '~' to Key("Backquote", true),
    )

    val UK: Map<Char, Key> = letters + ukPunct
    val US: Map<Char, Key> = letters + usPunct

    fun tableFor(targetLayout: String?): Map<Char, Key> =
        if (targetLayout.equals("us", ignoreCase = true)) US else UK

    /**
     * Key sequence for one character: Shift down, tap, Shift up — or just
     * the tap when no Shift is needed. Returns null when the char is not
     * covered by the table (caller falls back to `hid/print`).
     */
    fun keySequenceFor(ch: Char, targetLayout: String?): List<Pair<String, Boolean>>? {
        val key = tableFor(targetLayout)[ch] ?: return null
        return buildList {
            if (key.shift) add("ShiftLeft" to true)
            add(key.code to false)
            if (key.shift) add("ShiftLeft" to false)
        }
    }
}