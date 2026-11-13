package sh.haven.core.data

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [EventJournal] — the on-device ring buffer that records
 * session-death and reconnection events for forensics (hd9).
 *
 * Robolectric provides a real [Context.filesDir] and [Context.cacheDir]
 * so file I/O is exercised without mocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class EventJournalTest {

    private lateinit var context: Context
    private lateinit var journal: EventJournal

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        // Clean up any leftover files from a previous test
        File(context.filesDir, "event_journal.log").delete()
        File(context.filesDir, "event_journal.1.log").delete()
        File(context.cacheDir, "event_journal_export.log").delete()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "event_journal.log").delete()
        File(context.filesDir, "event_journal.1.log").delete()
        File(context.cacheDir, "event_journal_export.log").delete()
    }

    // --- (a) disabled by default — log() writes nothing ---

    @Test
    fun `log is disabled by default`() {
        journal = EventJournal(context)
        assertFalse("journal must start disabled", journal.isEnabled)
    }

    @Test
    fun `log writes nothing when disabled`() {
        journal = EventJournal(context)
        journal.log("test", "should not appear")
        val activeFile = File(context.filesDir, "event_journal.log")
        assertFalse("active file must not exist when disabled", activeFile.exists())
    }

    // --- (b) enabled — lines appear with ISO timestamp and [tag] ---

    @Test
    fun `log writes a line when enabled`() {
        journal = EventJournal(context)
        journal.setEnabled(true)
        journal.log("mosh-verdict", "session died: transport declared dead")

        val text = File(context.filesDir, "event_journal.log").readText()
        assertTrue("log must contain the tag", text.contains("[mosh-verdict]"))
        assertTrue("log must contain the message", text.contains("session died: transport declared dead"))
    }

    @Test
    fun `log line starts with ISO timestamp`() {
        journal = EventJournal(context)
        journal.setEnabled(true)
        journal.log("tag", "msg")

        val line = File(context.filesDir, "event_journal.log").readLines().first()
        // ISO format: 2026-11-13T21:28:44.123Z
        val isoPattern = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z ")
        assertTrue("line must start with ISO timestamp: $line", isoPattern.containsMatchIn(line))
    }

    // --- (c) rotation — with small maxBytes, .1 file appears after overflow ---

    @Test
    fun `rotation creates dot-1 file when active exceeds maxBytes`() {
        // Use a very small maxBytes so rotation triggers quickly
        val smallMax = 512L
        journal = EventJournal(context, maxBytes = smallMax)
        journal.setEnabled(true)

        // Write enough events to exceed 512 bytes
        repeat(100) { i ->
            journal.log("stress", "event line $i with some padding to fill bytes faster")
        }

        val rotated = File(context.filesDir, "event_journal.1.log")
        assertTrue("rotated file must exist after overflow", rotated.exists())
        assertTrue("rotated file must have content", rotated.length() > 0)

        val active = File(context.filesDir, "event_journal.log")
        assertTrue("active file must still exist", active.exists())
        assertTrue("active file must have content", active.length() > 0)
    }

    // --- (d) exportCopy() returns null when journal is empty ---

    @Test
    fun `exportCopy returns null when journal is empty`() {
        journal = EventJournal(context)
        // Even enabled — nothing logged yet
        journal.setEnabled(true)
        assertNull("export must be null for empty journal", journal.exportCopy())
    }

    // --- (e) exportCopy() contains the logged events ---

    @Test
    fun `exportCopy contains logged events`() {
        journal = EventJournal(context)
        journal.setEnabled(true)
        journal.log("session-died", "clean=false, stall=47s")
        journal.log("mosh-verdict", "server dead, port free")

        val copy = journal.exportCopy()
        assertNotNull("export must not be null after logging", copy)
        val text = copy!!.readText()
        assertTrue("export must contain first event", text.contains("[session-died]"))
        assertTrue("export must contain second event", text.contains("[mosh-verdict]"))
        assertTrue("export must contain message text", text.contains("clean=false, stall=47s"))
    }

    @Test
    fun `exportCopy includes rotated data`() {
        val smallMax = 256L
        journal = EventJournal(context, maxBytes = smallMax)
        journal.setEnabled(true)

        // Fill enough to trigger rotation
        repeat(50) { i ->
            journal.log("rotation", "line $i padded to fill the small buffer faster")
        }

        // Log one more after rotation
        journal.log("after-rot", "survivor")

        val copy = journal.exportCopy()
        assertNotNull(copy)
        val text = copy!!.readText()
        assertTrue("export must include post-rotation event", text.contains("[after-rot]"))
        // Rotated content should appear before the active content
        assertTrue("export must include rotated data", text.contains("[rotation]"))
    }
}
