package sh.haven.core.data.agent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One entry in the live MCP tool registry, published by the tool server
 * (`:app`) so UI feature modules — which cannot depend on `:app` — can list
 * what an agent can call and let the user switch individual tools off (#755).
 * [consentLevel] is carried so the UI can show what enabling a tool costs the
 * user (a prompt per call vs none) without duplicating the registry's metadata.
 */
data class McpToolInfo(
    val name: String,
    val consentLevel: ConsentLevel,
    /** Subsystem group shown in Settings so the list can be steered by category (#755). */
    val category: McpToolCategory = McpToolCategory.of(name),
    /** Concise one-sentence summary of what the tool does (#755). */
    val summary: String = "",
)

/**
 * Per-tool on/off switches for the MCP endpoint (#755). The registry exposes
 * ~120 tools; a small-model client gets lost in the `tools/list` dump and a
 * large one pays every call's input tokens for tools the user never wanted
 * enabled. The endpoint master switch and the capability gates are too coarse
 * for that, so this stores the set of tools the user has switched off —
 * disabled tools leave `tools/list` entirely (the context saving is the point,
 * not just the denial) and a direct `tools/call` to one fails with a
 * Settings-pointing error.
 *
 * Cross-module bridge in the [McpStatusHolder] shape: the tool list is pushed
 * in by `McpTools` at construction; the disabled set is persisted here so it
 * survives restarts and is observable by both the dispatcher and Settings.
 *
 * The master switch, pairing and consent all still apply — this only removes
 * individual tools from the surface; it never adds one or bypasses a gate.
 */
@Singleton
class McpToolCatalog @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val disabledKey = stringSetPreferencesKey("mcp_disabled_tools")

    /** Tool names the user has switched off. Unknown names are kept harmlessly. */
    val disabledTools: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[disabledKey] ?: emptySet()
    }

    suspend fun isDisabled(name: String): Boolean = disabledTools.first().contains(name)

    suspend fun setToolEnabled(name: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[disabledKey] ?: emptySet()
            prefs[disabledKey] = if (enabled) current - name else current + name
        }
    }

    /**
     * Switch a whole category on or off in one edit (#755 follow-up) — the
     * category master switch in Settings. [names] is the category's membership
     * as currently published by the registry; the caller passes it rather than
     * the catalog deriving it, because the registry lives in `:app` and this
     * module only sees the [McpToolInfo] projection. One `edit` means the
     * dispatcher never observes a half-applied category.
     */
    suspend fun setToolsEnabled(names: Collection<String>, enabled: Boolean) {
        if (names.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[disabledKey] ?: emptySet()
            prefs[disabledKey] = if (enabled) current - names.toSet() else current + names
        }
    }

    private val _tools = MutableStateFlow<List<McpToolInfo>>(emptyList())

    /**
     * The live registry as last published by `McpTools`. Empty until the MCP
     * server's tool table is constructed (HavenApp injects McpServer eagerly,
     * so it is populated at process start); Settings shows a "not started"
     * hint rather than an empty list that would read as "no tools exist".
     */
    val tools: StateFlow<List<McpToolInfo>> = _tools.asStateFlow()

    fun publish(tools: List<McpToolInfo>) {
        _tools.value = tools
    }
}
