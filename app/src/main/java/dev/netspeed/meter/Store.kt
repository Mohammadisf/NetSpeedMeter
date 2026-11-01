package dev.netspeed.meter

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Local traffic store — a faithful re-implementation of the reference app's
 * SharedPreferences persistence model (file "net"):
 *
 *  - `date`   : date stamp (dd-MM-yyyy, US locale) of today's counters
 *  - `today1` : Float, today's **mobile** usage in MiB
 *  - `today2` : Float, today's **WiFi** usage in MiB
 *  - `1<date>` / `2<date>` : Float MiB per completed day (mobile / WiFi)
 *
 * 100 % offline: nothing ever leaves the device.
 */
object Store {

    private const val FILE = "net"
    private val DATE_FMT = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    private const val KEY_DATE = "date"
    private const val KEY_TODAY1 = "today1" // mobile MiB
    private const val KEY_TODAY2 = "today2" // wifi MiB
    private const val KEY_TODAY3 = "today3" // hotspot (tethered clients) MiB
    private const val KEY_SERVICE_RUNNING = "serviceRunning"

    fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- dates

    fun todayKey(): String = DATE_FMT.format(Date())

    fun keyOf(date: Date): String = DATE_FMT.format(date)

    fun parseKey(key: String): Date? =
        try {
            DATE_FMT.parse(key)
        } catch (_: Exception) {
            null
        }

    private fun dateOfKey(key: String): Date? = parseKey(key)

    // ------------------------------------------------------------- settings

    fun isStartOnBoot(context: Context): Boolean =
        sp(context).getBoolean("start_on_boot", false)

    fun setStartOnBoot(context: Context, value: Boolean) =
        sp(context).edit().putBoolean("start_on_boot", value).apply()

    /** Hide the notification from the lock screen (template: hide_lockscreen_notif). */
    fun isHideLockscreenNotif(context: Context): Boolean =
        sp(context).getBoolean("hide_lockscreen_notif", false)

    fun setHideLockscreenNotif(context: Context, value: Boolean) =
        sp(context).edit().putBoolean("hide_lockscreen_notif", value).apply()

    fun isServiceRunning(context: Context): Boolean =
        sp(context).getBoolean(KEY_SERVICE_RUNNING, false)

    fun setServiceRunning(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_SERVICE_RUNNING, value).apply()

    /** App theme: system / light / dark (⋮ menu → Theme). */
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    fun themeMode(context: Context): ThemeMode = when (sp(context).getString("theme", null)) {
        "light" -> ThemeMode.LIGHT
        "dark" -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

    fun setThemeMode(context: Context, value: ThemeMode) =
        sp(context).edit()
            .putString("theme", value.name.lowercase(java.util.Locale.US)).apply()

    /** Accent color id ("blue"/"green"/"purple"/"orange"/"red"/"teal"). */
    fun accentId(context: Context): String = sp(context).getString("accent", "blue") ?: "blue"

    fun setAccentId(context: Context, value: String) =
        sp(context).edit().putString("accent", value).apply()

    /** Whether the archived day rows of the usage table are expanded. */
    fun isTableExpanded(context: Context): Boolean =
        sp(context).getBoolean("table_expanded", false)

    fun setTableExpanded(context: Context, value: Boolean) =
        sp(context).edit().putBoolean("table_expanded", value).apply()

    /** Try to disable the connection type that passed its data limit (best
     *  effort — needs the platform to accept the change). */
    fun isEnforceLimits(context: Context): Boolean =
        sp(context).getBoolean("enforce_limits", false)

    fun setEnforceLimits(context: Context, value: Boolean) =
        sp(context).edit().putBoolean("enforce_limits", value).apply()

    /** Try to disable the hotspot when its limit is passed (best effort). */
    fun isEnforceHotspot(context: Context): Boolean =
        sp(context).getBoolean("enforce_hotspot", false)

    fun setEnforceHotspot(context: Context, value: Boolean) =
        sp(context).edit().putBoolean("enforce_hotspot", value).apply()

    // -------------------------------------------------- per-month fired flags
    // The data-limit notification fires once per passing per month for each
    // limit type; flags reset when the calendar month changes.

    private const val FIRED_WIFI = "limit_fired_wifi"
    private const val FIRED_MOBILE = "limit_fired_mobile"
    private const val FIRED_COMBINED = "limit_fired_combined"

    fun limitFiredMonth(context: Context): String =
        sp(context).getString("limit_fired_month", "") ?: ""

    fun isLimitFired(context: Context, type: String): Boolean = when (type) {
        "wifi" -> sp(context).getBoolean(FIRED_WIFI, false)
        "combined" -> sp(context).getBoolean(FIRED_COMBINED, false)
        else -> sp(context).getBoolean(FIRED_MOBILE, false)
    }

    fun setLimitFired(context: Context, type: String, value: Boolean) =
        sp(context).edit().putBoolean(when (type) {
            "wifi" -> FIRED_WIFI
            "combined" -> FIRED_COMBINED
            else -> FIRED_MOBILE
        }, value).apply()

    /** Rotates the per-month fired flags when a new month started. */
    @Synchronized
    fun ensureLimitFiredMonthFresh(context: Context, month: String) {
        if (limitFiredMonth(context) == month) return
        sp(context).edit()
            .putString("limit_fired_month", month)
            .putBoolean(FIRED_WIFI, false)
            .putBoolean(FIRED_MOBILE, false)
            .putBoolean(FIRED_COMBINED, false)
            .apply()
    }

    /** Clears the fired flags (after the user edits limits, so a newly set
     *  already-exceeded limit re-fires and can be enforced). */
    @Synchronized
    fun clearLimitFiredFlags(context: Context) {
        sp(context).edit()
            .putBoolean(FIRED_WIFI, false)
            .putBoolean(FIRED_MOBILE, false)
            .putBoolean(FIRED_COMBINED, false)
            .apply()
    }

    /** Whether the notification shows both upload and download speeds (⋮ default on). */
    fun isShowUpDown(context: Context): Boolean =
        sp(context).getBoolean("show_updown", true)

    fun setShowUpDown(context: Context, value: Boolean) =
        sp(context).edit().putBoolean("show_updown", value).apply()

    /** Speed units: bytes (B/s, KB/s…) when false, bits (bit/s, kbit/s…) when true. */
    fun useBits(context: Context): Boolean =
        sp(context).getBoolean("speed_bits", false)

    fun setUseBits(context: Context, value: Boolean) =
        sp(context).edit().putBoolean("speed_bits", value).apply()

    // -------------------------------------------------------------- alerts
    // Usage alert: post a notification once per period when the usage within
    // the period (rolling window of `alert_hours`) passes `alert_limit` MB.

    fun alertLimitMb(context: Context): Long = sp(context).getLong("alert_limit", 0L)

    fun setAlertLimitMb(context: Context, value: Long) =
        sp(context).edit().putLong("alert_limit", value.coerceAtLeast(0L)).apply()

    fun alertPeriodHours(context: Context): Long = sp(context).getLong("alert_hours", 24L)

    fun setAlertPeriodHours(context: Context, value: Long) =
        sp(context).edit().putLong("alert_hours", value.coerceIn(1L, 24L * 30L)).apply()

    /** Epoch ms when the alert last fired (re-arms after one full period). */
    fun alertFiredAt(context: Context): Long = sp(context).getLong("alert_fired", 0L)

    fun setAlertFiredAt(context: Context, value: Long) =
        sp(context).edit().putLong("alert_fired", value).apply()

    // ------------------------------------------------------------- hotspot
    // Hotspot limit: warn (and optionally auto-disable the hotspot) once per
    // period when the tethered clients' usage within the rolling window
    // passes `hotspot_limit` MB.

    /** Epoch ms until which automatic (re-)blocking is paused — set when the
     *  user taps Restore internet, so the sit-statement is not instantly
     *  re-blocked while its limit is still exceeded. */
    fun enforcePauseUntil(context: Context): Long = sp(context).getLong("enforce_pause_until", 0L)

    fun setEnforcePauseUntil(context: Context, value: Long) =
        sp(context).edit().putLong("enforce_pause_until", value).apply()

    fun hotspotLimitMb(context: Context): Long = sp(context).getLong("hotspot_limit", 0L)

    fun setHotspotLimitMb(context: Context, value: Long) =
        sp(context).edit().putLong("hotspot_limit", value.coerceAtLeast(0L)).apply()

    fun hotspotPeriodHours(context: Context): Long = sp(context).getLong("hotspot_hours", 24L)

    fun setHotspotPeriodHours(context: Context, value: Long) =
        sp(context).edit().putLong("hotspot_hours", value.coerceIn(1L, 24L * 30L)).apply()

    /** Epoch ms when the hotspot limit last fired (re-arms after one period). */
    fun hotspotFiredAt(context: Context): Long = sp(context).getLong("hotspot_fired", 0L)

    fun setHotspotFiredAt(context: Context, value: Long) =
        sp(context).edit().putLong("hotspot_fired", value).apply()

    // -------------------------------------------------------------- limits
    // Three independent monthly limits (0 = disabled), in MiB:
    //   wifi     — Wi-Fi traffic only
    //   mobile   — mobile data only
    //   combined — Wi-Fi + mobile together
    // Migration: v2.1 stored a single mobile-data limit under "limit";
    // it keeps meaning "mobile only" until the user edits it in the new dialog.

    fun limitWifiMb(context: Context): Long = sp(context).getLong("limit_wifi", 0L)

    fun setLimitWifiMb(context: Context, value: Long) =
        sp(context).edit().putLong("limit_wifi", value.coerceAtLeast(0L)).apply()

    fun limitMobileMb(context: Context): Long {
        val sp = sp(context)
        return if (sp.contains("limit_mobile")) sp.getLong("limit_mobile", 0L)
        else sp.getLong("limit", 0L) // pre-2.2 single mobile limit
    }

    fun setLimitMobileMb(context: Context, value: Long) =
        sp(context).edit().putLong("limit_mobile", value.coerceAtLeast(0L)).apply()

    fun limitCombinedMb(context: Context): Long = sp(context).getLong("limit_combined", 0L)

    fun setLimitCombinedMb(context: Context, value: Long) =
        sp(context).edit().putLong("limit_combined", value.coerceAtLeast(0L)).apply()

    // ---------------------------------------------------------------- usage

    /** Date stamp currently stored for today's counters. */
    fun storedDate(context: Context): String? = sp(context).getString(KEY_DATE, null)

    /** Today's usage in bytes: (mobile, wifi). */
    fun loadToday(context: Context): Pair<Double, Double> {
        val sp = sp(context)
        return (sp.getFloat(KEY_TODAY1, 0f).toDouble() * 1048576.0) to
                (sp.getFloat(KEY_TODAY2, 0f).toDouble() * 1048576.0)
    }

    /** Today's tethered-hotspot usage in bytes. */
    fun loadTodayHotspot(context: Context): Double =
        sp(context).getFloat(KEY_TODAY3, 0f).toDouble() * 1048576.0

    /** Persists today's live counters (template: periodic flush from the engine). */
    @Synchronized
    fun saveToday(
        context: Context,
        mobileBytes: Double,
        wifiBytes: Double,
        hotspotBytes: Double
    ) {
        val key = todayKey()
        sp(context).edit()
            .also { if (storedDate(context) != key) it.putString(KEY_DATE, key) }
            .putFloat(KEY_TODAY1, (mobileBytes / 1048576.0).toFloat())
            .putFloat(KEY_TODAY2, (wifiBytes / 1048576.0).toFloat())
            .putFloat(KEY_TODAY3, (hotspotBytes / 1048576.0).toFloat())
            .apply()
    }

    /** Moves a finished day's counters into the history table. */
    @Synchronized
    fun archiveDay(
        context: Context,
        key: String,
        mobileBytes: Double,
        wifiBytes: Double,
        hotspotBytes: Double
    ) {
        sp(context).edit()
            .putFloat("1$key", (mobileBytes / 1048576.0).toFloat())
            .putFloat("2$key", (wifiBytes / 1048576.0).toFloat())
            .putFloat("3$key", (hotspotBytes / 1048576.0).toFloat())
            .putFloat(KEY_TODAY1, 0f)
            .putFloat(KEY_TODAY2, 0f)
            .putFloat(KEY_TODAY3, 0f)
            .putString(KEY_DATE, todayKey())
            .apply()
        prune(context)
    }

    /** Usage (bytes) recorded for a past day, or null when there is no entry. */
    fun day(context: Context, key: String): Pair<Double, Double>? {
        val sp = sp(context)
        if (!sp.contains("1$key") && !sp.contains("2$key")) return null
        return (sp.getFloat("1$key", 0f).toDouble() * 1048576.0) to
                (sp.getFloat("2$key", 0f).toDouble() * 1048576.0)
    }

    /** Hotspot usage (bytes) recorded for a past day (0 when absent). */
    fun dayHotspot(context: Context, key: String): Double =
        sp(context).getFloat("3$key", 0f).toDouble() * 1048576.0

    /** All archived day keys (today's key excluded), newest first. */
    fun historyKeys(context: Context): List<String> {
        val today = todayKey()
        val keys = ArrayList<String>()
        for (e in sp(context).all) {
            val k = e.key as? String ?: continue
            if (k.startsWith("1") || k.startsWith("2") || k.startsWith("3")) {
                val day = k.substring(1)
                if (day != today && keys.contains(day).not() && parseKey(day) != null) {
                    keys.add(day)
                }
            }
        }
        keys.sortByDescending { parseKey(it) }
        return keys
    }

    /** Deletes archived days older than 90 days (when a new day is archived). */
    private fun prune(context: Context) {
        val cut = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -90)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        val e = sp(context).edit()
        var removed = false
        for (key in historyKeys(context)) {
            val d = dateOfKey(key)
            if (d != null && d.before(cut)) {
                e.remove("1$key").remove("2$key")
                removed = true
            }
        }
        if (removed) e.apply()
    }

    /** Sum of archived usage between the two dates (both inclusive). */
    fun sumRange(context: Context, from: Date, to: Date): Pair<Double, Double> {
        var mob = 0.0; var wifi = 0.0
        for (key in historyKeys(context)) {
            val d = dateOfKey(key) ?: continue
            if (!d.before(from) && !d.after(to)) {
                val u = day(context, key) ?: continue
                mob += u.first; wifi += u.second
            }
        }
        return mob to wifi
    }

    /** Archived usage for the current calendar month (bytes). */
    fun sumThisMonth(context: Context): Pair<Double, Double> {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return sumRange(context, cal.time, now.time)
    }

    /** Sum of archived hotspot usage between the two dates (both inclusive). */
    fun sumHotspotRange(context: Context, from: Date, to: Date): Double {
        var hotspot = 0.0
        for (key in historyKeys(context)) {
            val d = dateOfKey(key) ?: continue
            if (!d.before(from) && !d.after(to)) {
                hotspot += dayHotspot(context, key)
            }
        }
        return hotspot
    }

    /** Sum of archived hotspot usage for the current calendar month (bytes). */
    fun sumHotspotThisMonth(context: Context): Double {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return sumHotspotRange(context, cal.time, now.time)
    }

    /** Wipes every history entry and today's counters. */
    fun resetHistory(context: Context) {
        val e = sp(context).edit()
        for (key in sp(context).all.keys) {
            if (key.startsWith("1") || key.startsWith("2") || key.startsWith("3")) e.remove(key)
        }
        e.putFloat(KEY_TODAY1, 0f).putFloat(KEY_TODAY2, 0f).putFloat(KEY_TODAY3, 0f).apply()
    }
}
