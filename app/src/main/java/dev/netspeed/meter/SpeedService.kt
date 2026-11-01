package dev.netspeed.meter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service running the 1-second sampling loop, exactly like the
 * reference app (SpeedMeterService + f7.o ticker):
 *
 *  - Ticks every 1000 ms; each tick reads the kernel byte counters, updates
 *    today's mobile/WiFi usage and the live speeds.
 *  - The notification's small icon is **rewritten every tick** with the live
 *    combined (down + up) speed, exactly like the reference app: a square
 *    white "value over unit" bitmap lands next to the clock in the status
 *    bar (see [StatusBarIcon], port of the reference renderer).
 *  - Notification line 1 (big):  "↓ <down>  ↑ <up>"
 *    Notification line 2:        usage split by network type, or a warning
 *                                line when the monthly limit is exceeded.
 *  - On a date change the finished day is archived into the history table.
 *  - The notification is re-posted on **every tick** (like the reference
 *    keeps its handler running and re-posting) **with a fresh `when`
 *    timestamp**, so the drawer's recency-based ranking always puts the
 *    speed notification back at the top of the stream, ahead of any other
 *    app's notifications. Combined with the silent IMPORTANCE_HIGH channel
 *    (top stream class — IMPORTANCE_MIN would pin it to the bottom collapsed
 *    section instead), it effectively stays pinned at position #1.
 */
class SpeedService : Service() {

    companion object {
        private const val NOTIF_ID = 1

        /**
         * v3 of the channel: importance LOW → HIGH. Channels are immutable
         * once created, so a new id applies the change to existing installs
         * without the user touching Settings. Sound and vibration are set to
         * null, so the notification is silent and never pops up a heads-up —
         * but IMPORTANCE_HIGH ranks it above other apps' LOW/DEFAULT
         * notifications in the drawer's stream (the template app sits on
         * IMPORTANCE_LOW, whose stream can be outranked by louder apps).
         */
        private const val CHANNEL_ID = "live_speed_v3"
        private const val ACTION_START = "dev.netspeed.meter.START"
        private const val ACTION_STOP = "dev.netspeed.meter.STOP"
        const val ACTION_UPDATE = "dev.netspeed.meter.UPDATE"

        /** Separate notification for "usage alert" (fired when a configured
         *  rolling-window usage limit is passed). Re-posted at most once per
         *  configured period (see [checkUsageAlert]). */
        private const val ALERT_NOTIF_ID = 2
        private const val CHANNEL_ALERTS = "usage_alerts_v1"

        /** Data-limit-reached notification (one per month per limit type). */
        private const val LIMIT_NOTIF_ID = 3
        /** Hotspot-limit-reached notification. */
        private const val HOTSPOT_NOTIF_ID = 4

        private const val MONTH_FMT = "yyyyMM"

        const val TICK_MS = 1_000L

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, SpeedService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SpeedService::class.java))
        }
    }

    private lateinit var handler: Handler
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private lateinit var notificationManager: NotificationManager

    private var serviceTicks = 0
    private var lastTickAt = 0L
    /** Cached monthly usage in MiB (for the warning line): mobile, wifi, combined. */
    @Volatile private var monthlyMobileMb = 0.0
    @Volatile private var monthlyWifiMb = 0.0
    @Volatile private var monthlyCombinedMb = 0.0

    private val ticker = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            val dt = if (lastTickAt == 0L) TICK_MS else now - lastTickAt
            lastTickAt = now

            // Date roll: template checked the stored date each tick as well.
            if (Store.storedDate(this@SpeedService) != Store.todayKey()) {
                Engine.rollDay(this@SpeedService)
            }

            Engine.tick(this@SpeedService, dt)
            serviceTicks++

            // Refresh the monthly-limit cache and evaluate the usage alert
            // once every 15 ticks (storage scans are not free).
            if (serviceTicks % 15 == 0) {
                refreshMonthlyUsage()
                checkUsageAlert()
                checkDataLimits()
                checkHotspotLimit()
            }

            // Re-post every tick: freshness = top of the notification stream.
            updateNotification()
            sendUpdate()

            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()

        Engine.load(this)
        Engine.updateConnectivity(this)

        startForegroundCompat()
        updateNotification()
        running = true
        lastTickAt = 0L
        handler.postDelayed(ticker, TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(ticker)
        Engine.flush(this)
        Engine.updateConnectivity(this) // resample before persisting
        Store.setServiceRunning(this, false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notif

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_toggle),
            // HIGH = top of the drawer stream (above other apps' LOW/DEFAULT
            // notifications). Sound and vibration are disabled explicitly so
            // it is silent and never pops up a heads-up banner.
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.setShowBadge(false)
        channel.description = getString(R.string.channel_description)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.enableLights(false)
        notificationManager.createNotificationChannel(channel)

        // Usage-alert channel: an actual alert, so the default notification
        // sound stays enabled to make the warning noticeable.
        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.usage_alerts_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        alertChannel.description = getString(R.string.alerts_message)
        notificationManager.createNotificationChannel(alertChannel)
    }

    private fun startForegroundCompat() {
        notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_stat_speed,
                getString(R.string.close),
                PendingIntent.getService(
                    this, 1,
                    Intent(this, SpeedService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        Store.setServiceRunning(this, true)

        val type =
            if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notificationBuilder.build(), type)
    }

    /**
     * Reference notification layout:
     *   small icon:     live combined speed bitmap (next to the clock)
     *   content title:  "↓ x  ↑ y"          (speed, merged big text)
     *   content text:   line 2 (usage split / warning)
     */
    private fun updateNotification() {
        val bits = Store.useBits(this)
        val line1 = if (Store.isShowUpDown(this)) {
            "↓ " + Fmt.speed(Engine.currentDown, bits) +
                    "   ↑ " + Fmt.speed(Engine.currentUp, bits)
        } else {
            "↓ " + Fmt.speed(Engine.currentDown, bits)
        }

        // Monthly usage (cached; the reference also checked its limit on the
        // notification-refresh path, not every storage scan).
        val wifiLimit = Store.limitWifiMb(this)
        val mobileLimit = Store.limitMobileMb(this)
        val combinedLimit = Store.limitCombinedMb(this)
        val anyLimit = wifiLimit > 0 || mobileLimit > 0 || combinedLimit > 0
        if (anyLimit && serviceTicks % 15 == 0) refreshMonthlyUsage()

        // First-exceeded limit wins, in priority order: combined, mobile, wifi.
        var warning = ""
        when {
            combinedLimit > 0 && monthlyCombinedMb >= combinedLimit ->
                warning = getString(
                    R.string.notif_warning_combined,
                    Fmt.amount(monthlyCombinedMb * 1048576.0)
                )
            mobileLimit > 0 && monthlyMobileMb >= mobileLimit ->
                warning = getString(
                    R.string.notif_warning_mobile,
                    Fmt.amount(monthlyMobileMb * 1048576.0)
                )
            wifiLimit > 0 && monthlyWifiMb >= wifiLimit ->
                warning = getString(
                    R.string.notif_warning_wifi,
                    Fmt.amount(monthlyWifiMb * 1048576.0)
                )
        }

        // Line 2 always carries BOTH daily usage numbers (mobile and WiFi) —
        // previously the connected network's line hid the other type, so on
        // WiFi only WiFi usage was visible. The warning line still wins when
        // a monthly limit was passed.
        val line2 = if (warning.isNotBlank()) {
            warning
        } else {
            val split = getString(
                R.string.notif_split,
                Fmt.amount(Engine.todayMobileBytes), Fmt.amount(Engine.todayWifiBytes)
            )
            if (Engine.onWifi && Engine.wifiName.isNotBlank()) "$split (${Engine.wifiName})" else split
        }

        val text = buildString { append(line1); append("\n"); append(line2) }
        notificationBuilder.setContentTitle(line1)
        notificationBuilder.setContentText(line2)
        notificationBuilder.setSmallIcon(
            StatusBarIcon.icon(this, Engine.currentDown + Engine.currentUp)
        )
        // Fresh timestamp on every re-post: the drawer's ranking is
        // recency-based, so this is what keeps the notification pinned at
        // the very top of the list (the template app does the same by
        // posting a brand-new notification every second).
        notificationBuilder.setWhen(System.currentTimeMillis())
        notificationBuilder.setShowWhen(true)
        notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        notificationBuilder.setVisibility(
            if (Store.isHideLockscreenNotif(this)) NotificationCompat.VISIBILITY_SECRET
            else NotificationCompat.VISIBILITY_PUBLIC
        )
        notificationManager.notify(NOTIF_ID, notificationBuilder.build())
    }

    /** Re-reads the monthly usage cache used by the limit-warning line. */
    private fun refreshMonthlyUsage() {
        val (mob, wifi) = Store.sumThisMonth(this)
        monthlyMobileMb = mob / 1048576.0
        monthlyWifiMb = wifi / 1048576.0
        monthlyCombinedMb = (mob + wifi) / 1048576.0
    }

    /**
     * Usage alert: when the configured MB limit is passed within the rolling
     * [period], post a one-shot notification. Fires at most once per period;
     * the next evaluation happens a full period after the last fire.
     */
    private fun checkUsageAlert() {
        val limitMb = Store.alertLimitMb(this)
        if (limitMb <= 0) return
        val hours = Store.alertPeriodHours(this)
        if (hours <= 0) return
        val periodMs = hours * 3_600_000L
        val now = System.currentTimeMillis()
        if (now - Store.alertFiredAt(this) < periodMs) return

        // Archived days inside the window + today's live counters.
        val from = java.util.Date(now - periodMs)
        val (archMob, archWifi) = Store.sumRange(this, from, java.util.Date(now))
        val used = archMob + archWifi + Engine.todayMobileBytes + Engine.todayWifiBytes
        if (used < limitMb * 1048576.0) return

        Store.setAlertFiredAt(this, now)
        val text = getString(
            R.string.alert_notif_text,
            Fmt.amount(used),
            getString(R.string.alert_period_hours, hours)
        )
        val alert = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle(getString(R.string.alert_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ALERT_NOTIF_ID, alert)
    }

    /**
     * Monthly data-limit watch: when a limit (wifi / mobile / combined) is
     * passed, post a notification once per month for that type and — if the
     * user opted in and the platform accepts it — automatically disconnect
     * the connection type that passed the limit.
     */
    private fun checkDataLimits() {
        if (Store.limitWifiMb(this) <= 0 && Store.limitMobileMb(this) <= 0 &&
            Store.limitCombinedMb(this) <= 0
        ) return

        val month = java.text.SimpleDateFormat(MONTH_FMT, java.util.Locale.US)
            .format(java.util.Date())
        Store.ensureLimitFiredMonthFresh(this, month)

        // Monthly usage: archived month + today's live counters.
        val monthSums = Store.sumThisMonth(this)
        val usedMobile = monthSums.first + Engine.todayMobileBytes
        val usedWifi = monthSums.second + Engine.todayWifiBytes
        val usedCombined = usedMobile + usedWifi

        // Priority order: combined, mobile, wifi (matches the warning line).
        if (Store.limitCombinedMb(this) > 0 && usedCombined >= Store.limitCombinedMb(this) * 1048576.0) {
            fireDataLimit(getString(R.string.limit_name_combined), "combined", usedCombined,
                Store.limitCombinedMb(this), ConnectionEnforcer::disableCombined)
        } else if (Store.limitMobileMb(this) > 0 && usedMobile >= Store.limitMobileMb(this) * 1048576.0) {
            fireDataLimit(getString(R.string.limit_name_mobile), "mobile", usedMobile,
                Store.limitMobileMb(this), ConnectionEnforcer::disableMobileData)
        } else if (Store.limitWifiMb(this) > 0 && usedWifi >= Store.limitWifiMb(this) * 1048576.0) {
            fireDataLimit(getString(R.string.limit_name_wifi), "wifi", usedWifi,
                Store.limitWifiMb(this), ConnectionEnforcer::disableWifi)
        }
    }

    /**
     * Runs one enforcement step for an exceeded limit: direct toggle first,
     * then the built-in kill-switch VPN when the direct control is not
     * available (modern Android). Returns the honest result line to append
     * to the notification.
     */
    private fun runEnforcement(
        context: Context,
        direct: (Context) -> Boolean,
        vpnResult: Int
    ): String {
        val direct = direct(context)
        android.util.Log.d("NetSpeed", "enforce direct=$direct")
        if (direct) return getString(R.string.limit_disconnected)
        if (ConnectionEnforcer.canEnforceViaKillSwitch(context) &&
            ConnectionEnforcer.enforceViaKillSwitch(context)
        ) {
            android.util.Log.d("NetSpeed", "enforce kill-switch armed")
            return getString(vpnResult)
        }
        return getString(R.string.limit_disconnect_failed)
    }

    private fun fireDataLimit(
        typeName: String,
        type: String,
        used: Double,
        limitMb: Long,
        enforce: (Context) -> Boolean
    ) {
        if (Store.isLimitFired(this, type)) return
        Store.setLimitFired(this, type, true)

        // Optional automatic disconnection — only when the user opted in.
        // A "restore" grace period pauses re-blocking after the user tapped
        // Restore internet (the limits themselves re-arm on their periods).
        val note: String = if (Store.isEnforceLimits(this)) {
            if (Store.enforcePauseUntil(this) > System.currentTimeMillis())
                getString(R.string.limit_disconnect_failed)
            else runEnforcement(this, enforce, R.string.limit_blocked_vpn)
        } else {
            ""
        }

        val text = getString(
            R.string.limit_notif_text, typeName,
            Fmt.amount(used), Fmt.amount(limitMb * 1048576.0)
        ) + note

        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle(getString(R.string.limit_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        notificationManager.notify(LIMIT_NOTIF_ID, notif)
    }

    /**
     * Hotspot-limit watch: when the tethered clients' usage inside the
     * rolling period passes the configured MB limit, warn once per period
     * and — if opted in — attempt to switch the hotspot off.
     */
    private fun checkHotspotLimit() {
        val limitMb = Store.hotspotLimitMb(this)
        if (limitMb <= 0) return
        val hours = Store.hotspotPeriodHours(this)
        if (hours <= 0) return
        val periodMs = hours * 3_600_000L
        val now = System.currentTimeMillis()
        if (now - Store.hotspotFiredAt(this) < periodMs) return

        // Tethered usage inside the rolling window: interface counters when
        // the platform allows them, NetworkStatsManager TETHERING fallback
        // when it does not (e.g. Android 16 hides /sys/class/net).
        val used = HotspotUsage.bytesBetween(this, now - periodMs, now)
        android.util.Log.d("NetSpeed", "hotspot limit check used=${Fmt.amount(used)} limit=${limitMb}MB")
        if (used < limitMb * 1048576.0) return

        Store.setHotspotFiredAt(this, now)
        var text = getString(
            R.string.hotspot_alert_text,
            Fmt.amount(used),
            getString(R.string.alert_period_hours, hours),
            Fmt.amount(limitMb * 1048576.0)
        )

        // Optional automatic hotspot shutdown — only when the user opted in.
        if (Store.isEnforceHotspot(this)) {
            text += if (Store.enforcePauseUntil(this) > System.currentTimeMillis())
                getString(R.string.limit_disconnect_failed)
            else runEnforcement(this, ConnectionEnforcer::disableHotspot,
                R.string.limit_hotspot_blocked_vpn)
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle(getString(R.string.hotspot_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        notificationManager.notify(HOTSPOT_NOTIF_ID, notif)
    }

    private fun sendUpdate() {
        val intent = Intent(ACTION_UPDATE)
            .setPackage(packageName)
        sendBroadcast(intent)
    }
}
