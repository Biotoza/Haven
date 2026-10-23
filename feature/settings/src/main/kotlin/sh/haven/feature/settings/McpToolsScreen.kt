package sh.haven.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.agent.McpToolInfo

/**
 * Per-tool on/off switches for the MCP endpoint (#755). The registry exposes
 * many tools; a small-model client gets lost in the `tools/list` dump and a
 * large one pays every call's input tokens for tools the user never wanted.
 * Switching a tool off removes it from `tools/list` entirely (the context
 * saving is the point) and a direct call to it fails with a Settings-pointing
 * error. The endpoint master switch, pairing and per-call consent all still
 * apply — this only subtracts from the surface.
 *
 * Mounted as an overlay over the pager from [SettingsScreen], same as
 * [PairedClientsScreen]; hence the opaque background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val tools by viewModel.mcpTools.collectAsState()
    val disabled by viewModel.mcpDisabledTools.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_mcp_tools_screen_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_cd_back),
                    )
                }
            },
        )
        if (tools.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_mcp_tools_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return
        }
        Text(
            text = stringResource(R.string.settings_mcp_tools_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tools.sortedBy { it.name }, key = { it.name }) { tool ->
                val enabled = tool.name !in disabled
                ListItem(
                    headlineContent = { Text(tool.name) },
                    supportingContent = {
                        Text(
                            when (tool.consentLevel) {
                                ConsentLevel.EVERY_CALL ->
                                    stringResource(R.string.settings_mcp_tools_consent_every_call)
                                ConsentLevel.ONCE_PER_SESSION ->
                                    stringResource(R.string.settings_mcp_tools_consent_once)
                                ConsentLevel.NEVER ->
                                    stringResource(R.string.settings_mcp_tools_consent_never)
                            },
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { viewModel.setMcpToolEnabled(tool.name, it) },
                        )
                    },
                )
            }
        }
    }
}
