package dev.netspeed.meter

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process

/**
 * Hotspot (tethered) usage gauge with a platform-stats fallback.
 *
 * The engine's primary hotspot accounting reads the SoftAp interface
 * counters from /sys/class/net. On some devices (Android 16 / One UI) the
 * SELinux policy denies apps access to those files entirely, so hotspot
 * usage would silently stay at zero and the hotspot limit would never
 * fire. Fallback: the public NetworkStatsManager — tethering traffic is
 * published under the reserved UID_TETHERING (-4) bucket and is readable
 * with just ACCESS_NETWORK_STATE.
 */
object HotspotUsage {

    /** Set by [Engine] when the per-interface path counted hotspot bytes. */
    @Volatile var ifacePathWorks = false

    private var nsm: NetworkStatsManager? = null

    /**
     * True when the platform's per-app network stats (the netstats fallback)
     * can be read: on Android 15+ the "Usage data access" appop is required
     * before other apps' / tethering counters are returned.
     */
    fun usageAccessGranted(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
        val mode = ops?.checkOpNoThrow(
            "GET_USAGE_STATS", Process.myUid(), context.packageName
        )
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    /**
     * The Usage data access grant is needed when the interface path doesn't
     * work on this device and the stats fallback isn't allowed yet.
     */
    fun needsUsageAccess(context: Context): Boolean =
        !ifacePathWorks && !usageAccessGranted(context)

    /**
     * Tethered-clients usage in bytes between the two epoch times. Uses the
     * per-interface path when it is proven to work on this build; otherwise
     * (or when it records nothing) the NetworkStatsManager TETHERING
     * fallback, which also covers gaps from before the service started.
     */
    fun bytesBetween(context: Context, fromMs: Long, toMs: Long): Double {
        if (ifacePathWorks) {
            var bytes = 0.0
            val from = java.util.Date(fromMs)
            for (key in Store.historyKeys(context)) {
                val day = Store.parseKey(key) ?: continue
                // Days fully inside the window (day start >= fromMs).
                if (day.time >= fromMs) bytes += Store.dayHotspot(context, key)
            }
            bytes += Engine.todayHotspotBytes
            if (bytes > 0.0) return bytes
        }
        return netstatsTotal(context, fromMs, toMs).toDouble()
    }

    /**
     * NetworkStatsManager fallback: sums the TETHERING bucket over the time
     * window for both transports. Returns -1 when the platform refuses.
     */
    private fun netstatsTotal(context: Context, fromMs: Long, toMs: Long): Long {
        if (android.os.Build.VERSION.SDK_INT < 28) return -1L
        val manager = try {
            nsm ?: (context.getSystemService(Context.NETWORK_STATS_SERVICE)
                    as? NetworkStatsManager)?.also { nsm = it }
        } catch (_: Exception) {
            null
        } ?: return -1L

        var total = 0L
        for (type in intArrayOf(
            ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI
        )) {
            try {
                val summary = manager.querySummary(
                    type, null, fromMs, toMs
                ) ?: continue
                val bucket = NetworkStats.Bucket()
                var decoded = 0L
                while (summary.hasNextBucket()) {
                    summary.getNextBucket(bucket)
                    decoded += bucket.rxBytes + bucket.txBytes
                    val isApTraffic = bucket.uid == NetworkStats.Bucket.UID_TETHERING
                    if (!isApTraffic) continue
                    total += bucket.rxBytes + bucket.txBytes
                }
                android.util.Log.d(
                    "NetSpeed",
                    "netstats type=$type bucketsTotal=$decoded tetherTotal=$total"
                )
                summary.close()
            } catch (e: SecurityException) {
                android.util.Log.d("NetSpeed", "netstats secx type=$type: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.d("NetSpeed", "netstats err type=$type: $e")
            }
        }
        return total
    }
}
