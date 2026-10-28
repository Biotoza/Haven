package sh.haven.feature.connections

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import sh.haven.core.data.db.AgentAuditEventDao
import javax.inject.Inject

/** Window after the most recent agent action during which an indicator stays lit. */
const val AGENT_ACTIVE_WINDOW_MS = 30_000L

/** Glowing-red used for the robot's eyes while the agent is active. */
private val EYE_RED = Color(0xFFFF1F1F)

/** The robot body lights up light blue while its eyes flash red (active). */
private val ROBOT_BLUE = Color(0xFF4FC3F7)

/**
 * A robot face that wakes with **glowing red eyes** while [active]. Shared by the header
 * [AgentActiveChip] and the per-connection [ConnectionMcpIndicator] so "the agent is
 * operating" reads the same everywhere.
 */
@Composable
fun RobotEyesIcon(
    active: Boolean,
    baseTint: Color,
    contentDescription: String?,
    size: Dp = 24.dp,
    disabled: Boolean = false,
) {
    // Always create the transition (cheap when the eyes aren't drawn) and gate the value,
    // so the composition structure stays stable as [active] flips.
    val transition = rememberInfiniteTransition(label = "agentEyes")
    val pulseRaw by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "agentEyesPulse",
    )
    val pulse = if (active && !disabled) pulseRaw else 0f
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = contentDescription,
            // Light blue body while the eyes flash red; dimmed when disabled; muted when idle.
            tint = when {
                disabled -> baseTint.copy(alpha = 0.45f)
                active -> ROBOT_BLUE
                else -> baseTint
            },
            modifier = Modifier.size(size),
        )
        if (active && !disabled) {
            Canvas(modifier = Modifier.size(size)) {
                val w = this.size.width
                val eyeY = this.size.height * 0.46f
                val dx = w * 0.155f
                val cx = w / 2f
                val r = w * 0.072f
                for (ex in listOf(cx - dx, cx + dx)) {
                    val c = Offset(ex, eyeY)
                    drawCircle(EYE_RED.copy(alpha = 0.35f * pulse), radius = r * 2.2f, center = c)
                    drawCircle(EYE_RED.copy(alpha = pulse), radius = r, center = c)
                }
            }
        }
        if (disabled) {
            // A diagonal slash makes "MCP off for this connection" unmistakable.
            Canvas(modifier = Modifier.size(size)) {
                val w = this.size.width
                drawLine(
                    color = baseTint.copy(alpha = 0.8f),
                    start = Offset(w * 0.2f, w * 0.2f),
                    end = Offset(w * 0.8f, w * 0.8f),
                    strokeWidth = w * 0.08f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * Tiny ViewModel feeding [AgentActiveChip] from the agent audit table. Observing the
 * latest timestamp directly off Room means the chip lights up on every audit insert.
 */
@HiltViewModel
class AgentActiveChipViewModel @Inject constructor(
    dao: AgentAuditEventDao,
) : ViewModel() {
    val lastEventAt: StateFlow<Long?> = dao.observeLatestTimestamp()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
}

/** 1 Hz clock used to fade activity indicators without a push "fade" event. */
@Composable
private fun rememberNowTicker(): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return nowMs
}

/**
 * Header indicator: the robot wakes with glowing red eyes during the active window
 * (recent MCP activity), muted otherwise; absent until the endpoint has ever been used.
 * [onClick] navigates to the audit-log surface.
 */
@Composable
fun AgentActiveChip(
    onClick: () -> Unit,
    viewModel: AgentActiveChipViewModel = hiltViewModel(),
) {
    val lastEventAt by viewModel.lastEventAt.collectAsStateWithLifecycle()
    val nowMs = rememberNowTicker()
    val ts = lastEventAt ?: return
    val active by remember(ts, nowMs) {
        derivedStateOf { (nowMs - ts) in 0L..AGENT_ACTIVE_WINDOW_MS }
    }
    IconButton(onClick = onClick) {
        RobotEyesIcon(
            active = active,
            baseTint = MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = if (active) {
                stringResource(R.string.connections_agent_active)
            } else {
                stringResource(R.string.connections_agent_activity)
            },
        )
    }
}

/**
 * Per-connection MCP indicator + toggle, as a **tri-state** that keeps the list uncluttered:
 * - **null** — the agent has never operated on this connection (and MCP is enabled): no icon.
 * - **false** — MCP is deactivated for it: a slashed grey robot (tap to re-enable).
 * - **true** — the agent is operating on it now: a robot with glowing red eyes (tap to disable).
 *
 * So an idle, never-touched connection shows nothing; the robot only appears while it matters.
 */
@Composable
internal fun ConnectionMcpIndicator(
    lastActiveAt: Long?,
    mcpEnabled: Boolean,
    onToggle: () -> Unit,
) {
    // null state — enabled and never used this session: render nothing (and spin no ticker).
    if (mcpEnabled && lastActiveAt == null) return

    val nowMs = rememberNowTicker()
    val active = lastActiveAt != null && (nowMs - lastActiveAt) in 0L..AGENT_ACTIVE_WINDOW_MS

    // Enabled and the active window has elapsed → fall back to null (no icon).
    if (mcpEnabled && !active) return

    val cd = stringResource(
        if (!mcpEnabled) R.string.connections_mcp_disabled else R.string.connections_mcp_active,
    )
    IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
        RobotEyesIcon(
            active = active,
            baseTint = MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = cd,
            size = 20.dp,
            disabled = !mcpEnabled,
        )
    }
}
