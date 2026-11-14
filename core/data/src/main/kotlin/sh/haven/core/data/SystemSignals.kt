package sh.haven.core.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import sh.haven.core.data.EventJournal

private const val TAG = "SystemSignals"

/**
 * System-signal observer (hd9.1, Ярус 2 of the journal spec): bridges Android
 * system callbacks into the [EventJournal] so the journal records network
 * transitions, screen on/off, power-save/Doze mode, and memory-pressure
 * signals — all the environmental events that can kill or disrupt a session.
 *
 * Registration is **conditional**: callbacks and broadcast receivers are
 * registered only while the journal is enabled; when disabled, everything is
 * unregistered and there is zero overhead. The owner (HavenApp) toggles via
 * [start]/[stop] driven by the `eventJournalEnabled` preference flow.
 *
 * Tags used:
 * - `sys-net`    — network available/lost, transport change, captive portal
 * - `sys-screen` — screen on/off
 * - `sys-power`  — power-save / Doze mode change
 * - `sys-memory` — onTrimMemory (LOW/CRITICAL/COMPLETE), onLowMemory
 */
class SystemSignals(
    private val context: Context,
    private val journal: EventJournal,
) {

    // ── network ──────────────────────────────────────────────────────────

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Suppress("MissingPermission") // ACCESS_NETWORK_STATE declared in manifest
    private fun registerNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var lastTransport: String? = null

            override fun onAvailable(network: Network) {
                journal.log("sys-net", "network available: $network")
            }

            override fun onLost(network: Network) {
                journal.log("sys-net", "network lost: $network")
                lastTransport = null
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                val transport = transportName(caps)
                if (transport != lastTransport) {
                    journal.log("sys-net", "transport changed: $lastTransport → $transport")
                    lastTransport = transport
                }
            }

            override fun onLinkPropertiesChanged(network: Network, props: android.net.LinkProperties) {
                // Captive portal / validation loss: link properties change when
                // the system marks a network as not validated (captive portal).
                val validated = props.linkAddresses.isNotEmpty()
                journal.log("sys-net", "link properties changed: validated=$validated addresses=${props.linkAddresses.size}")
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun unregisterNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.unregisterNetworkCallback(cb) } catch (_: IllegalArgumentException) { }
    }

    private fun transportName(caps: NetworkCapabilities): String {
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    // ── screen ───────────────────────────────────────────────────────────

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> journal.log("sys-screen", "screen on")
                Intent.ACTION_SCREEN_OFF -> journal.log("sys-screen", "screen off")
            }
        }
    }

    private fun registerScreen() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)
    }

    private fun unregisterScreen() {
        try { context.unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) { }
    }

    // ── power / Doze ─────────────────────────────────────────────────────

    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val powerSave = (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
                    journal.log("sys-power", "power-save mode changed: $powerSave")
                }
                android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val idle = (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isDeviceIdleMode
                    journal.log("sys-power", "device idle (Doze) mode changed: $idle")
                }
            }
        }
    }

    private fun registerPower() {
        val filter = IntentFilter().apply {
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        context.registerReceiver(powerReceiver, filter)
    }

    private fun unregisterPower() {
        try { context.unregisterReceiver(powerReceiver) } catch (_: IllegalArgumentException) { }
    }

    // ── memory pressure (ComponentCallbacks2) ────────────────────────────

    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            val label = when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL"
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE"
                else -> return // ignore other levels
            }
            journal.log("sys-memory", "onTrimMemory: $label (level=$level)")
        }

        override fun onLowMemory() {
            journal.log("sys-memory", "onLowMemory — system-wide memory pressure")
        }

        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            // not relevant for journal
        }
    }

    private fun registerMemory() {
        context.registerComponentCallbacks(componentCallbacks)
    }

    private fun unregisterMemory() {
        context.unregisterComponentCallbacks(componentCallbacks)
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    private var started = false

    /** Register all system-signal observers. Idempotent. */
    fun start() {
        if (started) return
        started = true
        try { registerNetwork() } catch (e: Exception) { Log.w(TAG, "network registration failed: ${e.message}") }
        try { registerScreen() } catch (e: Exception) { Log.w(TAG, "screen registration failed: ${e.message}") }
        try { registerPower() } catch (e: Exception) { Log.w(TAG, "power registration failed: ${e.message}") }
        try { registerMemory() } catch (e: Exception) { Log.w(TAG, "memory registration failed: ${e.message}") }
        journal.log("journal", "system signals observer started (hd9.1)")
    }

    /** Unregister all system-signal observers. Idempotent. */
    fun stop() {
        if (!started) return
        started = false
        unregisterNetwork()
        unregisterScreen()
        unregisterPower()
        unregisterMemory()
        journal.log("journal", "system signals observer stopped")
    }
}
