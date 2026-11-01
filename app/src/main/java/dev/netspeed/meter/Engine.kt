package dev.netspeed.meter

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Process

/**
 * Live traffic engine — behaviour-only port of the reference app's engine /
 * ticker pair (`f7.e` + `f7.o`):
 *
 *  - Sample the kernel byte counters once per tick (1 s, like the reference).
 *  - Count traffic **per network interface** from `/sys/class/net` (the
 *    reference app used its own native per-interface reader for the same
 *    reason: TrafficStats' "total" double-counts when a VPN / hotspot /
 *    loopback interface is involved). Unreadable interfaces fall back to the
 *    TrafficStats split (mobile counters vs total remainders).
 *  - Accumulate today's usage in memory and flush it to [Store] roughly once
 *    per minute (the reference flushed on a ~60 s cycle).
 *  - Detect counter resets (backwards jump) and rebase to zero for that tick.
 *
 * Entirely offline: counters are read locally, storage is local.
 */
object Engine {

    // Live speeds in bytes per second (read by the status-bar icon builder).
    @Volatile var currentDown: Double = 0.0
        private set
    @Volatile var currentUp: Double = 0.0
        private set

    // Connectivity state (read by the notification builder).
    @Volatile var onWifi = false
        private set
    @Volatile var onMobile = false
        private set
    /** WiFi network name when connected, quotes stripped, "" when unknown. */
    @Volatile var wifiName: String = ""
        private set

    // Today's usage in bytes (session accumulators in the reference engine).
    @Volatile var todayMobileBytes = 0.0
        private set
    @Volatile var todayWifiBytes = 0.0
        private set
    /** Today's tethered-hotspot (softap) traffic in bytes. */
    @Volatile var todayHotspotBytes = 0.0
        private set

    // Last TrafficStats-level counters (fallback path).
    private var lastTotalRx = TrafficStats.UNSUPPORTED.toLong()
    private var lastTotalTx = TrafficStats.UNSUPPORTED.toLong()
    private var lastMobileRx = TrafficStats.UNSUPPORTED.toLong()
    private var lastMobileTx = TrafficStats.UNSUPPORTED.toLong()
    private var lastUidRx = TrafficStats.UNSUPPORTED.toLong()
    private var lastUidTx = TrafficStats.UNSUPPORTED.toLong()
    private var haveSamples = false

    /** Baseline per-interface counters for the per-interface path. */
    private var lastIfaces: Map<String, Pair<Long, Long>> = emptyMap()

    private var ticksSinceFlush = 0
    private var inited = false

    /** Loads persisted state; cheap after the first call. */
    @Synchronized
    fun load(context: Context) {
        if (inited) return
        val (mob, wifi) = Store.loadToday(context)
        if (Store.storedDate(context) != Store.todayKey()) {
            // Stale counters from a previous day: start fresh (the day was
            // archived live when it ended, or was never recorded).
            todayMobileBytes = 0.0
            todayWifiBytes = 0.0
            todayHotspotBytes = 0.0
            Store.saveToday(context, 0.0, 0.0, 0.0)
        } else {
            todayMobileBytes = mob
            todayWifiBytes = wifi
            todayHotspotBytes = Store.loadTodayHotspot(context)
        }
        inited = true
    }

    /** Refreshes WiFi/mobile state and the WiFi name; resamples counters. */
    @SuppressLint("MissingPermission")
    fun updateConnectivity(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var wifi = false
        var mobile = false
        var active = false
        try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null) {
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                mobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                active = true
            }
        } catch (_: Exception) {
        }
        onWifi = wifi
        onMobile = active && mobile
        if (!active) {
            // No active network (e.g. airplane mode): stop attributing traffic.
            onWifi = false
            onMobile = false
        }

        if (onWifi) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wm.connectionInfo
                var ssid = info?.ssid ?: WifiManager.UNKNOWN_SSID
                if (ssid == WifiManager.UNKNOWN_SSID) ssid = "<unknown ssid>"
                wifiName = ssid.replace("\"", "")
            } catch (_: Exception) {
                wifiName = ""
            }
        } else {
            wifiName = ""
        }
        resample()
    }

    /** Re-reads the current counters as the new baseline (no traffic recorded). */
    @Synchronized
    private fun resample() {
        lastTotalRx = TrafficStats.getTotalRxBytes().coerceAtLeast(-1L)
        lastTotalTx = TrafficStats.getTotalTxBytes().coerceAtLeast(-1L)
        lastMobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(-1L)
        lastMobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(-1L)
        lastUidRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(-1L)
        lastUidTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(-1L)
        lastIfaces = snapshotIfaces()
        haveSamples = true
    }

    private fun snapshotIfaces(): Map<String, Pair<Long, Long>> {
        val map = HashMap<String, Pair<Long, Long>>()
        for (name in IfaceTraffic.list()) {
            if (IfaceTraffic.isSkipped(name)) continue
            val rx = IfaceTraffic.rxBytes(name)
            val tx = IfaceTraffic.txBytes(name)
            if (rx >= 0 && tx >= 0) map[name] = rx to tx
        }
        return map
    }

    /**
     * One tick of the sampling loop. Fresh counters are read inside the tick;
     * [dtMs] is the elapsed time since the previous tick (used for speeds).
     */
    @Synchronized
    fun tick(context: Context, dtMs: Long) {
        if (!inited) load(context)

        val dt = dtMs.coerceAtLeast(1)
        var mobDown = 0L
        var mobUp = 0L
        var wifiDown = 0L
        var wifiUp = 0L
        var hotspotDown = 0L
        var hotspotUp = 0L

        // Preferred path: per-interface counters (no loopback / tun / mirror
        // double-counting, exactly what the reference app's native reader did).
        val names = IfaceTraffic.list()
        val next = HashMap<String, Pair<Long, Long>>()
        for (name in names) {
            val cls = IfaceTraffic.classify(name)
            if (cls == null && IfaceTraffic.isSkipped(name)) continue
            val rx = IfaceTraffic.rxBytes(name)
            val tx = IfaceTraffic.txBytes(name)
            if (rx < 0 || tx < 0) continue // unreadable this tick
            next[name] = rx to tx
            val prev = lastIfaces[name]
            if (prev == null) continue // new interface: start as baseline
            val dRx = (rx - prev.first).coerceAtLeast(0)
            val dTx = (tx - prev.second).coerceAtLeast(0)
            when {
                // SoftAp interfaces belong to the hotspot's own accounting,
                // not to the regular WiFi split.
                IfaceTraffic.isHotspot(name) -> {
                    hotspotDown += dRx; hotspotUp += dTx
                    if (dRx + dTx > 0) HotspotUsage.ifacePathWorks = true
                }
                (cls ?: (if (onWifi) true else if (onMobile) false else null)) == true -> {
                    wifiDown += dRx; wifiUp += dTx
                }
                cls == false -> { mobDown += dRx; mobUp += dTx }
                else -> {
                    // No network classification available: keep the bytes out
                    // of the split-usage accounting but still show the speed.
                    wifiDown += dRx; wifiUp += dTx
                }
            }
        }
        val ifacePath = next.isNotEmpty()
        if (ifacePath) lastIfaces = next

        if (!ifacePath) {
            // Fallback: TrafficStats mobile-vs-total split (original logic).
            val totalRx = TrafficStats.getTotalRxBytes()
            val totalTx = TrafficStats.getTotalTxBytes()
            val mobileRx = TrafficStats.getMobileRxBytes()
            val mobileTx = TrafficStats.getMobileTxBytes()
            val uidRx = TrafficStats.getUidRxBytes(Process.myUid())
            val uidTx = TrafficStats.getUidTxBytes(Process.myUid())
            val totalSupported = totalRx >= 0 && totalTx >= 0
            val mobileSupported = mobileRx >= 0 && mobileTx >= 0
            val uidSupported = uidRx >= 0 && uidTx >= 0

            var downTotal = 0L
            var upTotal = 0L
            if (haveSamples && totalSupported) {
                downTotal = totalRx - lastTotalRx
                upTotal = totalTx - lastTotalTx
                var reset = downTotal < 0 || upTotal < 0
                if (mobileSupported) {
                    val mDown = mobileRx - lastMobileRx
                    val mUp = mobileTx - lastMobileTx
                    if (mDown < 0 || mUp < 0) reset = true else {
                        mobDown = mDown.coerceAtMost(downTotal).coerceAtLeast(0)
                        mobUp = mUp.coerceAtMost(upTotal).coerceAtLeast(0)
                    }
                }
                if (reset) {
                    downTotal = 0L; upTotal = 0L; mobDown = 0L; mobUp = 0L
                } else if (mobileSupported) {
                    wifiDown = (downTotal - mobDown).coerceAtLeast(0)
                    wifiUp = (upTotal - mobUp).coerceAtLeast(0)
                } else if (uidSupported) {
                    downTotal = (uidRx - lastUidRx).coerceAtLeast(0)
                    upTotal = (uidTx - lastUidTx).coerceAtLeast(0)
                    if (onMobile) { mobDown = downTotal; mobUp = upTotal }
                    else { wifiDown = downTotal; wifiUp = upTotal }
                } else {
                    downTotal = 0L; upTotal = 0L
                }
            } else if (haveSamples && !totalSupported && uidSupported) {
                downTotal = (uidRx - lastUidRx).coerceAtLeast(0)
                upTotal = (uidTx - lastUidTx).coerceAtLeast(0)
                if (onMobile) { mobDown = downTotal; mobUp = upTotal }
                else { wifiDown = downTotal; wifiUp = upTotal }
            }
            lastTotalRx = if (totalRx >= 0) totalRx else TrafficStats.UNSUPPORTED.toLong()
            lastTotalTx = if (totalTx >= 0) totalTx else TrafficStats.UNSUPPORTED.toLong()
            lastMobileRx = if (mobileRx >= 0) mobileRx else TrafficStats.UNSUPPORTED.toLong()
            lastMobileTx = if (mobileTx >= 0) mobileTx else TrafficStats.UNSUPPORTED.toLong()
            lastUidRx = if (uidRx >= 0) uidRx else TrafficStats.UNSUPPORTED.toLong()
            lastUidTx = if (uidTx >= 0) uidTx else TrafficStats.UNSUPPORTED.toLong()
            haveSamples = true
        }

        currentDown = ((mobDown + wifiDown) * 1000.0) / dt
        currentUp = ((mobUp + wifiUp) * 1000.0) / dt

        if ((ticksSinceFlush % 10) == 0) {
            android.util.Log.d(
                "NetSpeed",
                "iface=$ifacePath ifaces=${next.keys} down=${Fmt.speed(currentDown)} up=${Fmt.speed(currentUp)}"
            )
        }

        todayMobileBytes += mobDown + mobUp
        todayWifiBytes += wifiDown + wifiUp
        todayHotspotBytes += hotspotDown + hotspotUp

        ticksSinceFlush++
        if (ticksSinceFlush >= 60) flush(context)
    }

    /** Writes today's counters to storage (service onDestroy / periodic). */
    fun flush(context: Context) {
        if (!inited) return
        Store.saveToday(context, todayMobileBytes, todayWifiBytes, todayHotspotBytes)
        ticksSinceFlush = 0
    }

    /**
     * Date rolled over: archive the finished day into the history table and
     * start today clean. Works from the in-memory counters (flush() would
     * re-label today's bytes under the new date).
     */
    @Synchronized
    fun rollDay(context: Context) {
        val oldDate = Store.storedDate(context)
        val mob = todayMobileBytes
        val wifi = todayWifiBytes
        val hotspot = todayHotspotBytes
        if (oldDate != null && oldDate != Store.todayKey()) {
            if (mob + wifi + hotspot >= 1048576.0) {
                Store.archiveDay(context, oldDate, mob, wifi, hotspot)
            }
        }
        todayMobileBytes = 0.0
        todayWifiBytes = 0.0
        todayHotspotBytes = 0.0
        Store.saveToday(context, 0.0, 0.0, 0.0)
    }

    /** Clears all recorded usage. */
    fun resetHistory(context: Context) {
        Store.resetHistory(context)
        todayMobileBytes = 0.0
        todayWifiBytes = 0.0
        todayHotspotBytes = 0.0
        flush(context)
    }
}
