package sh.haven.core.data.agent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #755 — per-tool MCP switches. The dispatcher reads [McpToolCatalog.disabledTools]
 * to subtract tools from `tools/list` and to deny calls, and Settings writes the
 * same store, so the set semantics here are the whole feature's persistence layer:
 * off adds to the set, on removes only that name, and the set survives a reopen
 * (a fresh catalog over the same file must still see what the user switched off).
 */
@RunWith(RobolectricTestRunner::class)
class McpToolCatalogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun catalog(): McpToolCatalog {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("prefs_${System.nanoTime()}.preferences_pb")
        }
        return McpToolCatalog(ds)
    }

    @Test
    fun setToolEnabledRoundTripsThroughTheDisabledSet() = runBlocking {
        val catalog = catalog()
        assertFalse("nothing is disabled by default", catalog.isDisabled("read_terminal_snapshot"))

        catalog.setToolEnabled("read_terminal_snapshot", false)
        assertTrue(catalog.isDisabled("read_terminal_snapshot"))
        assertEquals(setOf("read_terminal_snapshot"), catalog.disabledTools.first())

        // Turning one back on must not disturb the others.
        catalog.setToolEnabled("capture_haven_ui", false)
        catalog.setToolEnabled("read_terminal_snapshot", true)
        assertEquals(setOf("capture_haven_ui"), catalog.disabledTools.first())
        assertFalse(catalog.isDisabled("read_terminal_snapshot"))
        assertTrue(catalog.isDisabled("capture_haven_ui"))
    }

    @Test
    fun setToolsEnabledFlipsACategoryInOneEdit() = runBlocking {
        val catalog = catalog()
        val category = listOf("get_gps_status", "start_gps_log", "stop_gps_log")

        catalog.setToolsEnabled(category, false)
        assertEquals(category.toSet(), catalog.disabledTools.first())

        // Re-enabling the category clears exactly its members, leaving a
        // tool switched off individually elsewhere untouched.
        catalog.setToolEnabled("read_terminal_snapshot", false)
        catalog.setToolsEnabled(category, true)
        assertEquals(setOf("read_terminal_snapshot"), catalog.disabledTools.first())
    }

    @Test
    fun publishReplacesTheToolListForSettings() {
        val catalog = catalog()
        assertEquals(emptyList<McpToolInfo>(), catalog.tools.value)
        val tools = listOf(McpToolInfo("get_app_info", ConsentLevel.NEVER))
        catalog.publish(tools)
        assertEquals(tools, catalog.tools.value)
    }
}
