package sh.haven.core.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #757: the ongoing notification went stale when a session ENDED because the
 * service only rebuilt it at startForeground (a session starting) and on MCP
 * activity. The fix rides a merged signal of every contributor's `changes`.
 * These tests pin the wiring: an emission from ANY contributor must reach the
 * collector, and a contributor that never emits must not block the others.
 * A signal that fails either test leaves the notification stale again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundChangeSignalTest {

    private class Participant(
        override val changes: Flow<Unit>,
        active: List<ForegroundSessionInfo> = emptyList(),
    ) : ForegroundSessionParticipant {
        override val activeSessions: List<ForegroundSessionInfo> = active
        override fun disconnectAll() {}
    }

    private class KeepAlive(
        override val changes: Flow<Unit>,
        override val isActive: Boolean = false,
    ) : ForegroundKeepAlive

    @Test
    fun `an emission from any participant reaches the signal`() = runTest {
        val a = MutableSharedFlow<Unit>()
        val b = MutableSharedFlow<Unit>()
        val signal = foregroundChangeSignal(
            setOf(Participant(a), Participant(b)),
            emptySet(),
        )
        var received = 0
        val job = launch { signal.collect { received++ } }
        testScheduler.advanceUntilIdle()

        a.emit(Unit)
        b.emit(Unit)
        a.emit(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(3, received)
        job.cancel()
    }

    @Test
    fun `a contributor whose changes never emits does not block the others`() = runTest {
        val live = MutableSharedFlow<Unit>()
        val signal = foregroundChangeSignal(
            setOf(Participant(emptyFlow()), Participant(live)),
            setOf(KeepAlive(emptyFlow())),
        )
        var received = 0
        val job = launch { signal.collect { received++ } }
        testScheduler.advanceUntilIdle()

        live.emit(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(1, received)
        job.cancel()
    }

    @Test
    fun `keep-alive emissions reach the signal too`() = runTest {
        val live = MutableSharedFlow<Unit>()
        val signal = foregroundChangeSignal(
            setOf(Participant(emptyFlow())),
            setOf(KeepAlive(live)),
        )
        var received = 0
        val job = launch { signal.collect { received++ } }
        testScheduler.advanceUntilIdle()

        live.emit(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(1, received)
        job.cancel()
    }
}
