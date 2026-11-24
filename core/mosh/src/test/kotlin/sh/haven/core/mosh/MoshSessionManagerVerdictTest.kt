package sh.haven.core.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.content.ContextWrapper

/**
 * Regression test for the 02.11.2026 crash: `updateServerSessionVerdict` stored
 * a `null` verdict straight into a `ConcurrentHashMap`, which rejects null
 * values with a NullPointerException. The watcher feeds `null` on every first
 * `stallSeconds` emission (packets flowing / short blip), so the app crashed
 * the moment a session reached CONNECTED — six `data_app_crash` entries in the
 * phone's dropbox, one per connect attempt.
 *
 * Pinned invariant: "no verdict" is represented by the ABSENCE of a map entry
 * (the documented `absent/null = unknown` semantics), never by a stored null.
 */
class MoshSessionManagerVerdictTest {

    // Только для конструктора: тестируемые пути (updateServerSessionVerdict,
    // registerSession, connectSession) к контексту не обращаются. hasNetwork()
    // вызывается исключительно транспортом — в эти тесты не входит.
    private val manager = MoshSessionManager(ContextWrapper(null))

    private val zeroKey = "AAAAAAAAAAAAAAAAAAAAAA=="

    @Suppress("UNCHECKED_CAST")
    private fun verdicts(): MutableMap<String, Boolean?> {
        val field = MoshSessionManager::class.java.getDeclaredField("serverSessionVerdicts")
        field.isAccessible = true
        return field.get(manager) as MutableMap<String, Boolean?>
    }

    /** Register + connect: the state a session is in when the watcher starts. */
    private fun connectedId(): String {
        val id = manager.registerSession("test-profile", "test")
        manager.connectSession(id, "127.0.0.1", 60001, zeroKey, 80, 24)
        return id
    }

    @Test
    fun `null verdict removes the entry instead of crashing - 02-11 incident`() {
        val id = connectedId()
        // Before the fix this threw NPE (ConcurrentHashMap.put with null value).
        manager.updateServerSessionVerdict(id, null)
        assertFalse("null verdict must be absent, not stored", verdicts().containsKey(id))
    }

    @Test
    fun `true and false verdicts are stored, null clears them`() {
        val id = connectedId()
        manager.updateServerSessionVerdict(id, true)
        assertEquals(true, verdicts()[id])
        manager.updateServerSessionVerdict(id, false)
        assertEquals(false, verdicts()[id])
        manager.updateServerSessionVerdict(id, null)
        assertFalse(verdicts().containsKey(id))
    }

    @Test
    fun `verdict for unknown session is a no-op`() {
        manager.updateServerSessionVerdict("no-such-session", true)
        assertTrue(verdicts().isEmpty())
    }

    @Test
    fun `verdict for a still-connecting session is a no-op`() {
        val id = manager.registerSession("test-profile", "test") // CONNECTING
        manager.updateServerSessionVerdict(id, true)
        assertTrue(verdicts().isEmpty())
    }
}
