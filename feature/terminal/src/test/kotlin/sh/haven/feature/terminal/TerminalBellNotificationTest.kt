package sh.haven.feature.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #753: the BEL → notification policy. The gate is the whole feature — a
 * bell that lands while the user is looking at the terminal must not post,
 * and a disabled setting must never post — so the decision is a pure
 * function and tested as one.
 */
class TerminalBellNotificationTest {

    @Test
    fun `background bell with setting on notifies`() {
        assertTrue(shouldNotifyOnBell(settingEnabled = true, isForeground = false))
    }

    @Test
    fun `foreground bell never notifies`() {
        assertFalse(shouldNotifyOnBell(settingEnabled = true, isForeground = true))
    }

    @Test
    fun `setting off never notifies`() {
        assertFalse(shouldNotifyOnBell(settingEnabled = false, isForeground = false))
        assertFalse(shouldNotifyOnBell(settingEnabled = false, isForeground = true))
    }
}
