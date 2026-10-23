package sh.haven.core.glkvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlkvmLayoutTest {
    // Expectations verified live against a UK Windows target through the GL
    // firmware (kvmd.py accuracy notes: \ and | carry through IntlBackslash,
    // " types @, etc).

    @Test
    fun `plain letters tap without shift`() {
        assertEquals(listOf("KeyA" to false), GlkvmLayout.keySequenceFor('a', "uk"))
        assertEquals(listOf("KeyZ" to false), GlkvmLayout.keySequenceFor('z', "us"))
    }

    @Test
    fun `uppercase letters shift-tap`() {
        assertEquals(
            listOf("ShiftLeft" to true, "KeyA" to false, "ShiftLeft" to false),
            GlkvmLayout.keySequenceFor('A', "uk"),
        )
    }

    @Test
    fun `uk backslash uses the ISO 102nd key`() {
        assertEquals(listOf("IntlBackslash" to false), GlkvmLayout.keySequenceFor('\\', "uk"))
        assertEquals(
            listOf("ShiftLeft" to true, "IntlBackslash" to false, "ShiftLeft" to false),
            GlkvmLayout.keySequenceFor('|', "uk"),
        )
    }

    @Test
    fun `us backslash sits on the ANSI position`() {
        assertEquals(listOf("Backslash" to false), GlkvmLayout.keySequenceFor('\\', "us"))
    }

    @Test
    fun `quotemark lands on Digit2 in uk and Quote-shift in us`() {
        assertEquals(
            listOf("ShiftLeft" to true, "Digit2" to false, "ShiftLeft" to false),
            GlkvmLayout.keySequenceFor('"', "uk"),
        )
        assertEquals(
            listOf("ShiftLeft" to true, "Quote" to false, "ShiftLeft" to false),
            GlkvmLayout.keySequenceFor('"', "us"),
        )
    }

    @Test
    fun `at sign lands on Quote-shift in uk and Digit2-shift in us`() {
        assertTrue(GlkvmLayout.keySequenceFor('@', "uk")!!.contains("Quote" to false))
        assertTrue(GlkvmLayout.keySequenceFor('@', "us")!!.contains("Digit2" to false))
    }

    @Test
    fun `uk hash and tilde ride the backslash position`() {
        assertEquals(listOf("Backslash" to false), GlkvmLayout.keySequenceFor('#', "uk"))
        assertTrue(GlkvmLayout.keySequenceFor('~', "uk")!!.contains("Backslash" to false))
    }

    @Test
    fun `unmapped chars fall through to the printer path`() {
        assertNull(GlkvmLayout.keySequenceFor('£', "uk"))
        assertNull(GlkvmLayout.keySequenceFor('é', "us"))
        assertNull(GlkvmLayout.keySequenceFor('¬', "uk"))
    }

    @Test
    fun `digits are position codes in both layouts`() {
        for (d in 0..9) {
            val ch = '0' + d
            assertEquals(listOf("Digit$d" to false), GlkvmLayout.keySequenceFor(ch, "uk"))
            assertEquals(listOf("Digit$d" to false), GlkvmLayout.keySequenceFor(ch, "us"))
        }
    }

    @Test
    fun `default layout is uk`() {
        assertEquals(GlkvmLayout.UK, GlkvmLayout.tableFor(null))
        assertEquals(GlkvmLayout.UK, GlkvmLayout.tableFor("uk"))
        assertEquals(GlkvmLayout.UK, GlkvmLayout.tableFor("UK"))
        assertEquals(GlkvmLayout.US, GlkvmLayout.tableFor("us"))
    }
}