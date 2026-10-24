package sh.haven.app.transport

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.map
import sh.haven.core.rdp.RdpSessionManager
import sh.haven.core.ssh.ForegroundSessionParticipant
import sh.haven.core.ssh.Transport
import sh.haven.core.ssh.TransportSessionManager

/**
 * The remote-desktop transports' registry bindings, kept apart from
 * [TransportSessionManagerModule] so a build without remote desktop can drop
 * this file and nothing else (#510).
 *
 * Nothing outside this file names `sh.haven.core.rdp` in the registry path, so
 * removing it from a variant's source set removes RDP from the session
 * registry and the foreground notification together — the two places a
 * forgotten transport has bitten us before (#363, #366).
 *
 * There is no VNC or SPICE binding: those are desktop *sessions* rather than
 * transports and were never in the registry.
 */
@Module
@InstallIn(SingletonComponent::class)
object DesktopTransportModule {

    // No sendInput: RDP input goes through its own protocol path, not a PTY.
    @Provides @IntoSet
    fun rdpTransport(m: RdpSessionManager): TransportSessionManager = object : TransportSessionManager {
        override val transport = Transport.RDP
        override fun removeAllSessionsForProfile(profileId: String) = m.removeAllSessionsForProfile(profileId)
        override val activeSessionCount get() = m.activeSessions.size
        override val sessions
            get() = m.sessions.value.values.map {
                UnifiedSession(it.sessionId, it.profileId, it.label, mapStatus(it.status.name), Transport.RDP)
            }
    }

    @Provides @IntoSet
    fun rdpForeground(m: RdpSessionManager): ForegroundSessionParticipant = object : ForegroundSessionParticipant {
        override val activeSessions get() = m.activeSessions.map { SessionInfo(it.profileId, it.label) }
        override val changes get() = m.sessions.map { }
        override fun disconnectAll() = m.disconnectAll()
    }
}
