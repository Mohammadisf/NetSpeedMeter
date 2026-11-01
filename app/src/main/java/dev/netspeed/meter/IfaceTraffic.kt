package dev.netspeed.meter

import java.io.File

/**
 * Per-interface byte counters read straight from `/sys/class/net` (no root):
 * TrafficStats' "total" includes loopback, tethering and tunnel interfaces,
 * which double-counts traffic when a VPN or hotspot is active. The reference
 * app avoided this with its own native per-interface reader; this is the
 * platform-only equivalent. Values return -1 when unreadable (Engine then
 * falls back to TrafficStats).
 */
object IfaceTraffic {

    private const val NET_DIR = "/sys/class/net"

    fun list(): List<String> =
        try {
            File(NET_DIR).list()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    fun rxBytes(name: String): Long = read(name, true)
    fun txBytes(name: String): Long = read(name, false)

    private fun read(name: String, rx: Boolean): Long =
        try {
            val f = File("$NET_DIR/$name/statistics/${if (rx) "rx_bytes" else "tx_bytes"}")
            f.readText().trim().toLong()
        } catch (_: Exception) {
            -1L
        }

    /**
     * Interfaces that mirror/aggregate other ones (their counters must be
     * excluded, otherwise traffic is counted twice) — the same list the
     * reference engine filtered out (lo, tun0, rndis0, usb0, rmnet_ipa0, ...).
     */
    fun isSkipped(name: String): Boolean {
        val n = name.lowercase()
        return n == "lo" || n.startsWith("rmnet_ipa") ||
                n.startsWith("tun") || n.startsWith("tap") ||
                n.startsWith("rndis") || n.startsWith("usb") ||
                n.startsWith("ims") || n.startsWith("dummy") ||
                n.startsWith("bond") || n.startsWith("ip6tnl") ||
                n == "sit0" || n.startsWith("p2p") || n.startsWith("wfd") ||
                n.startsWith("teth") || n.startsWith("v4-") || n.startsWith("v6-")
    }

    /**
     * true  = LAN / WiFi-class traffic,
     * false = mobile data (rmnet*, ccmni*),
     * null  = unknown — the engine attributes it to the active network type.
     */
    fun classify(name: String): Boolean? {
        if (isSkipped(name)) return null
        val n = name.lowercase()
        return when {
            n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("eth") -> true
            n.startsWith("rmnet") || n.startsWith("ccmni") -> false
            else -> null
        }
    }

    /**
     * SoftAp (hotspot / tethering access point) interfaces. Their counters are
     * the tethered clients' traffic, tracked separately from regular WiFi:
     * "swlan*" on Qualcomm devices, "softap*", dedicated "ap0/ap1" radios and
     * the second "wlan1" radio on older devices with two WiFi interfaces.
     */
    fun isHotspot(name: String): Boolean {
        if (isSkipped(name)) return false
        val n = name.lowercase()
        return n.startsWith("swlan") || n.contains("softap") ||
                n == "ap0" || n == "ap1" || n == "wlan1"
    }
}
