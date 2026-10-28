package sh.haven.core.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #501: "I click the terminate X11/Xvnc button, nothing happens, Xvnc is not
 * closed."
 *
 * The reaper filtered `ps -A` output for the display (`:1`). `ps -A` prints
 * process *names* and no arguments, and the display is only ever an argument
 * — so the filter matched nothing on every run and the stop button killed
 * nothing. These pin the argv shapes it has to handle instead.
 */
class XvncReapMatchTest {

    private val real = "/usr/bin/Xvnc :1 -geometry 1280x720 -depth 24 -rfbport 5901 -SecurityTypes VncAuth"

    @Test
    fun `a real Xvnc argv is matched for its own display`() {
        assertTrue(xvncMatches(real, display = 1))
    }

    @Test
    fun `the name alone is not enough — this is what ps -A gave us`() {
        // Exactly what the old filter had to work with, and why it never fired.
        assertFalse("no display in the text, so a per-display stop must not claim it", xvncMatches("Xvnc", display = 1))
    }

    @Test
    fun `a display is compared whole, so stopping 1 leaves 10 and 11 alone`() {
        val ten = "/usr/bin/Xvnc :10 -geometry 1280x720"
        assertTrue(xvncMatches(ten, display = 10))
        assertFalse("`:1` must not substring-match `:10`", xvncMatches(ten, display = 1))
        assertFalse(xvncMatches("/usr/bin/Xvnc :11 -depth 24", display = 1))
    }

    @Test
    fun `the wrong display is not matched`() {
        assertFalse(xvncMatches(real, display = 2))
    }

    @Test
    fun `a null display matches any Xvnc`() {
        assertTrue(xvncMatches(real, display = null))
        assertTrue(xvncMatches("Xvnc :7", display = null))
    }

    @Test
    fun `other processes are left alone`() {
        listOf(
            "/usr/bin/wayvnc --render-cursor 0.0.0.0 5901",
            "sway",
            "/usr/bin/Xwayland :1 -rootless",
            "grep Xvnc",
            "",
            "   ",
        ).forEach {
            assertFalse("must not match '$it'", xvncMatches(it, display = 1))
            assertFalse("must not match '$it'", xvncMatches(it, display = null))
        }
    }

    @Test
    fun `an Xvnc named without a path still matches`() {
        assertTrue(xvncMatches("Xvnc :1 -rfbport 5901", display = 1))
    }

    @Test
    fun `proot host process embedding the launch script is matched`() {
        // What a proot-launched Xvnc actually looks like on the host side:
        // argv[0] is libproot.so and the guest script rides inside argv (#551).
        val prootHost = "/data/app/x/lib/arm64/libproot.so -0 --link2symlink " +
            "-r /data/rootfs /bin/sh -c rm -f /tmp/.X1-lock ; " +
            "Xvnc :1 -geometry 1280x720 -localhost 0 & XVNC_PID=${'$'}! ; wait"
        assertTrue(xvncMatches(prootHost, display = 1))
        assertFalse("whole-token display", xvncMatches(prootHost, display = 10))
        assertTrue(xvncMatches(prootHost, display = null))
    }

    @Test
    fun `buildXvncCommand enables TCP listener and -ac while staying reap-matchable`() {
        val cmd = buildXvncCommand(display = 1, securityArg = "-SecurityTypes None")
        assertTrue("Expected -listen tcp -ac in: $cmd", cmd.contains("-listen tcp -ac"))
        assertTrue(xvncMatches(cmd, display = 1))
        assertFalse(xvncMatches(cmd, display = 10))
    }
}
