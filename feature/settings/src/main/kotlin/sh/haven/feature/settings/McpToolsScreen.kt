package sh.haven.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.agent.McpToolCategory
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
 * Tools are grouped by subsystem into collapsible sections (collapsed by
 * default so the screen opens as a compact category list rather than ~230
 * switches), each headed by a master switch that flips the whole category on
 * or off at once.
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
    var expandedCategories by rememberSaveable { mutableStateOf(emptySet<String>()) }

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
        // OTHER sorts last even though it is last in the enum only by intent;
        // the rest keep the enum's order (terminal first — the commonest thing
        // to trim on a small-model client).
        val grouped = tools.groupBy { it.category }
        val order = McpToolCategory.entries.sortedBy { if (it == McpToolCategory.OTHER) 1 else 0 }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            order.forEach { category ->
                val inCategory = grouped[category].orEmpty().sortedBy { it.name }
                if (inCategory.isEmpty()) return@forEach
                val names = inCategory.map { it.name }
                val offCount = names.count { it in disabled }
                val enabledCount = names.size - offCount
                val isExpanded = category.name in expandedCategories
                item(key = "header_${category.name}") {
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) {
                            expandedCategories = if (isExpanded) {
                                expandedCategories - category.name
                            } else {
                                expandedCategories + category.name
                            }
                        },
                        leadingContent = {
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(categoryLabelRes(category)),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        supportingContent = {
                            val summary = if (offCount > 0) {
                                val offLabel = stringResource(
                                    R.string.settings_mcp_tools_category_off_count,
                                    offCount,
                                )
                                "$enabledCount / ${names.size} · $offLabel"
                            } else {
                                "$enabledCount / ${names.size}"
                            }
                            Text(text = summary)
                        },
                        trailingContent = {
                            Switch(
                                checked = enabledCount > 0,
                                onCheckedChange = { viewModel.setMcpToolsEnabled(names, it) },
                            )
                        },
                    )
                }
                if (isExpanded) {
                    items(inCategory, key = { it.name }) { tool ->
                        val enabled = tool.name !in disabled
                        ListItem(
                            modifier = Modifier.padding(start = 24.dp),
                            headlineContent = { Text(tool.name) },
                            supportingContent = {
                                val consentLabel = when (tool.consentLevel) {
                                    ConsentLevel.EVERY_CALL ->
                                        stringResource(R.string.settings_mcp_tools_consent_every_call)
                                    ConsentLevel.ONCE_PER_SESSION ->
                                        stringResource(R.string.settings_mcp_tools_consent_once)
                                    ConsentLevel.NEVER ->
                                        stringResource(R.string.settings_mcp_tools_consent_never)
                                }
                                Text(
                                    if (tool.summary.isNotBlank()) {
                                        "${tool.summary}\n$consentLabel"
                                    } else {
                                        consentLabel
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
                item(key = "divider_${category.name}") {
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun categoryLabelRes(category: McpToolCategory): Int = when (category) {
    McpToolCategory.TERMINAL -> R.string.settings_mcp_tools_cat_terminal
    McpToolCategory.CONNECTIONS -> R.string.settings_mcp_tools_cat_connections
    McpToolCategory.FILES -> R.string.settings_mcp_tools_cat_files
    McpToolCategory.DESKTOP -> R.string.settings_mcp_tools_cat_desktop
    McpToolCategory.GUEST -> R.string.settings_mcp_tools_cat_guest
    McpToolCategory.USB -> R.string.settings_mcp_tools_cat_usb
    McpToolCategory.KEYS -> R.string.settings_mcp_tools_cat_keys
    McpToolCategory.MAIL -> R.string.settings_mcp_tools_cat_mail
    McpToolCategory.RCLONE -> R.string.settings_mcp_tools_cat_rclone
    McpToolCategory.SENSES -> R.string.settings_mcp_tools_cat_senses
    McpToolCategory.AGENT -> R.string.settings_mcp_tools_cat_agent
    McpToolCategory.OTHER -> R.string.settings_mcp_tools_cat_other
}
