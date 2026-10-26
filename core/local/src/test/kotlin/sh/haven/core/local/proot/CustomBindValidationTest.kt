package sh.haven.core.local.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * #760: a custom bind whose Android host path no longer exists is dropped at
 * launch instead of being passed to proot (which warns "can't sanitize
 * binding" on every launch and silently no-ops). The common way a bind goes
 * stale is Android's random /data/app/~~<random>/ install path changing on
 * update.
 */
class CustomBindValidationTest {

    @Test
    fun `existing host path is bindable`() {
        val dir = Files.createTempDirectory("bind-ok").toFile()
        assertTrue(bindHostPresent(dir.absolutePath))
    }

    @Test
    fun `missing host path is not bindable`() {
        val missing = Files.createTempDirectory("bind-gone").toFile().resolve("nope")
        assertFalse(bindHostPresent(missing.absolutePath))
    }

    @Test
    fun `blank path is not bindable`() {
        assertFalse(bindHostPresent(""))
    }
}
