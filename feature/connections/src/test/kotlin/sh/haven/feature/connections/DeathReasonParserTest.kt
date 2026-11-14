package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Test

class DeathReasonParserTest {

    @Test
    fun `null trace falls back to the generic reason`() {
        assertEquals("transport declared dead", extractDeathReason(null))
    }

    @Test
    fun `blank trace falls back to the generic reason`() {
        assertEquals("transport declared dead", extractDeathReason("   \n  "))
    }

    @Test
    fun `prefers the last verdict or error line`() {
        val trace = "session started\nsome verdict: silence 45s\nfinal error: stall window expired\n"
        assertEquals("final error: stall window expired", extractDeathReason(trace))
    }

    @Test
    fun `uses the last line when no verdict marker is present`() {
        assertEquals("last line", extractDeathReason("alpha\nbeta\nlast line"))
    }

    @Test
    fun `truncates a long reason to 120 characters`() {
        val long = "verdict: " + "x".repeat(200)
        assertEquals(120, extractDeathReason(long).length)
    }

    @Test
    fun `skips blank lines when looking for a verdict`() {
        val trace = "keepalive tick\n\n\nerror: rebinds exhausted\n"
        assertEquals("error: rebinds exhausted", extractDeathReason(trace))
    }
}
