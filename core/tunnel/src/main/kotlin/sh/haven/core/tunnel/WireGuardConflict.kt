package sh.haven.core.tunnel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raised when a [WireguardTunnel] bring-up is refused because an ACTIVE
 * system-VPN (VpnService) already holds the same tunnel address.
 *
 * Why refuse rather than coexist: the WG server keeps one session per peer
 * key. Haven's config and the VPN app's config being "the same" means the
 * same private key — two devices handshaking against one peer steal the
 * session back and forth (each side's data drops silently under the other's
 * fresh keys until its next handshake, ~2 min), which shows up as intermittent
 * `dial … deadline exceeded` and connection flapping on BOTH apps. The userspace
 * model can't detect whose frames the server dropped, so the only correct move
 * is to not run a second device on that key: traffic rides the system VPN's
 * routing directly (it already covers the tunnel's destinations by definition).
 */
class WireGuardSystemVpnConflict(
    val vpnInterface: String,
    val address: String,
) : IOException(
    "WireGuard tunnel address $address is held by the active system VPN $vpnInterface — " +
        "the same config is loaded in a VPN app, and two devices sharing that peer key " +
        "fight over the WG session. Haven's tunnel stays down; connections ride the " +
        "system VPN directly."
)

/**
 * Detects the same-address system-VPN collision that motivates
 * [WireGuardSystemVpnConflict], and parses the [Interface] addresses a
 * wg-quick config declares (Kotlin mirror of wgbridge's parse, used to
 * decide BEFORE creating the device).
 *
 * Match rule: equality of the wg [Interface] Address against any address on
 * a live TRANSPORT_VPN network. Same address == same issued key in practice
 * (a WG server allocates the tunnel address out of the same pool as the key),
 * so address equality is the observable proxy for the key fight.
 */
@Singleton
class SystemVpnAddressProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Interface name of an active system VPN holding [address], or null. */
    fun collidingVpnInterface(addresses: List<InetAddress>): String? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.firstNotNullOfOrNull { net: Network ->
            val caps = cm.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return@firstNotNullOfOrNull null
            val lp: LinkProperties = cm.getLinkProperties(net) ?: return@firstNotNullOfOrNull null
            if (lp.linkAddresses.any { la -> addresses.any { it == la.address } }) lp.interfaceName else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "system-VPN probe failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "SystemVpnAddressProbe"

        /**
         * First IPv4 + any IPv6 [Interface] Address lines from a wg-quick
         * config, in declared order — the same value wgbridge's
         * [WireguardTunnel.localAddress] would report (it prefers the first
         * IPv4).
         *
         * Pure so the collision decision is testable without a VPN service:
         * tests inject the probe's `addresses` and compare against mock
         * link addresses.
         */
        fun parseInterfaceAddresses(configText: String): List<InetAddress> {
            val out = mutableListOf<InetAddress>()
            var inInterface = false
            for (rawLine in configText.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("[")) {
                    inInterface = line.removeSurrounding("[", "]").equals("Interface", ignoreCase = true)
                    continue
                }
                if (!inInterface) continue
                val key = line.substringBefore('=').trim().lowercase()
                if (key != "address") continue
                for (a in line.substringAfter('=').split(',')) {
                    val entry = a.trim()
                    if (entry.isEmpty()) continue
                    val addr = entry.substringBefore('/')
                    try {
                        out.add(InetAddress.getByName(addr))
                    } catch (_: Exception) {
                        // Unparseable — the Go parser would have rejected the
                        // whole config; here we just skip so the probe can
                        // still match on the remaining addresses.
                    }
                }
            }
            return out
        }

        /** Convenience: does a live system VPN hold any of these addresses? */
        fun collides(configText: String, probe: SystemVpnAddressProbe): WireGuardSystemVpnConflict? {
            val addresses = parseInterfaceAddresses(configText)
            if (addresses.isEmpty()) return null
            val iface = probe.collidingVpnInterface(addresses) ?: return null
            return WireGuardSystemVpnConflict(iface, addresses.first().hostAddress.orEmpty())
        }
    }
}