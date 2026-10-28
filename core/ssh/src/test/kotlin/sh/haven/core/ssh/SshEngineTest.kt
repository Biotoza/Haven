package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshEngineTest {

    private fun config(options: Map<String, String>) = ConnectionConfig(
        host = "example.com",
        username = "u",
        sshOptions = options,
    )

    @Test
    fun `absent directive defaults to JSCH`() {
        assertEquals(SshEngine.JSCH, config(emptyMap()).sshEngine)
    }

    @Test
    fun `directive is case-insensitive in key and value`() {
        assertEquals(SshEngine.SSHLIB, config(mapOf("havensshengine" to "SSHLIB")).sshEngine)
        assertEquals(SshEngine.SSHLIB, config(mapOf("HavenSshEngine" to "sshlib")).sshEngine)
    }

    @Test
    fun `unknown value falls back to JSCH, never throws`() {
        assertEquals(SshEngine.JSCH, config(mapOf("HavenSshEngine" to "openssh")).sshEngine)
        assertEquals(SshEngine.JSCH, config(mapOf("HavenSshEngine" to "")).sshEngine)
    }

    @Test
    fun `directive round-trips through parseSshOptions`() {
        val options = ConnectionConfig.parseSshOptions("HavenSshEngine sshlib\nServerAliveInterval 30")
        assertEquals(SshEngine.SSHLIB, config(options).sshEngine)
    }

    @Test
    fun `forwardX11 and forwardPulseAudio round-trip through setInOptions and parseSshOptions`() {
        val base = "ServerAliveInterval 30\nHavenSshEngine sshlib"
        val withBoth = setForwardPulseAudioInOptions(setForwardX11InOptions(base, true), true)
        assertTrue(sshForwardX11FromOptionsText(withBoth))
        assertTrue(sshForwardPulseAudioFromOptionsText(withBoth))
        assertEquals(SshEngine.SSHLIB, sshEngineFromOptionsText(withBoth))

        val clearedX11 = setForwardX11InOptions(withBoth, false)
        assertFalse(sshForwardX11FromOptionsText(clearedX11))
        assertTrue(sshForwardPulseAudioFromOptionsText(clearedX11))

        val clearedAll = setForwardPulseAudioInOptions(clearedX11, false)
        assertFalse(sshForwardPulseAudioFromOptionsText(clearedAll))
        assertEquals(base, clearedAll)
    }

    @Test
    fun `standard OpenSSH ForwardX11 yes is recognized and replaced cleanly by toggle`() {
        val raw = "ForwardX11 yes\nServerAliveInterval 60"
        assertTrue(sshForwardX11FromOptionsText(raw))
        val disabled = setForwardX11InOptions(raw, false)
        assertFalse(sshForwardX11FromOptionsText(disabled))
        assertEquals("ServerAliveInterval 60", disabled)
    }

    @Test
    fun `buildForwardingEnvExportCommand combines DISPLAY and PULSE_SERVER when enabled`() {
        assertNull(buildForwardingEnvExportCommand(forwardX11 = false, forwardPulseAudio = false))
        assertEquals(
            "export DISPLAY=localhost:10.0",
            buildForwardingEnvExportCommand(forwardX11 = true, forwardPulseAudio = false),
        )
        assertEquals(
            "export PULSE_SERVER=tcp:127.0.0.1:14713",
            buildForwardingEnvExportCommand(forwardX11 = false, forwardPulseAudio = true),
        )
        assertEquals(
            "export DISPLAY=localhost:10.0 ; export PULSE_SERVER=tcp:127.0.0.1:14713",
            buildForwardingEnvExportCommand(forwardX11 = true, forwardPulseAudio = true),
        )
    }

    @Test
    fun `buildMediaPortForwards produces non-critical self-healing reverse tunnels`() {
        val forwards = buildMediaPortForwards(
            forwardX11 = true,
            localX11Port = 6001,
            forwardPulseAudio = true,
            localPulsePort = 4713,
        )
        assertEquals(2, forwards.size)
        val x11 = forwards[0]
        assertEquals("haven-x11-forward", x11.ruleId)
        assertEquals(SshSessionManager.PortForwardType.REMOTE, x11.type)
        assertEquals("127.0.0.1", x11.bindAddress)
        assertEquals(6010, x11.bindPort)
        assertEquals(6001, x11.targetPort)
        assertFalse(x11.critical)
        assertTrue(x11.selfHealOnBindFailure)

        val pulse = forwards[1]
        assertEquals("haven-pulse-forward", pulse.ruleId)
        assertEquals(SshSessionManager.PortForwardType.REMOTE, pulse.type)
        assertEquals("127.0.0.1", pulse.bindAddress)
        assertEquals(14713, pulse.bindPort)
        assertEquals(4713, pulse.targetPort)
        assertFalse(pulse.critical)
        assertTrue(pulse.selfHealOnBindFailure)
    }
}
