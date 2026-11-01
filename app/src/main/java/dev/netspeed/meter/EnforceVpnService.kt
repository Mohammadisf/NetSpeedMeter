package dev.netspeed.meter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.FileInputStream

/**
 * Enforcement kill-switch — the platform-supported fallback for automatic
 * disconnection.
 *
 * Modern Android does not let normal (unrooted) apps switch WiFi, mobile
 * data or the hotspot on/off: those controls need system/privileged
 * permissions (TETHER_PRIVILEGED / MODIFY_PHONE_STATE, Android 16 removed
 * the last hidden paths entirely). The one connectivity-control API that
 * IS granted to sandboxed apps is [VpnService]: with the user's one-time
 * consent the app may bring up a sealed tunnel interface and blackhole
 * every packet, which cuts all internet (and the internet reachable by
 * tethered clients) exactly like a kill switch.
 *
 * Block flow:
 *  - [block] is a no-op (returns false) when the user has not granted the
 *    VPN consent dialog yet — the caller then reports the limitation
 *    honestly. MainActivity requests the consent while the configure
 *    dialogs are open.
 *  - While running, packets entering the tunnel are read and discarded.
 *    Nothing is forwarded, so no connection can pass and no data can be
 *    consumed (the exact guarantee a data-limit breaker needs).
 *  - The user restores internet with the "Restore internet" notification
 *    action; enforcement pauses for 24 h so the sit-statement is not
 *    instantly re-blocked while the limit is still exceeded.
 */
class EnforceVpnService : VpnService() {

    companion object {
        private const val NOTIF_ID = 5
        private const val CHANNEL_ID = "enforce_vpn_v1"
        private const val ACTION_BLOCK = "dev.netspeed.meter.ENFORCE_BLOCK"
        private const val ACTION_RELEASE = "dev.netspeed.meter.ENFORCE_RELEASE"

        @Volatile
        var blocking = false
            private set

        /** Whether the kill-switch can be armed right now (VPN consent given). */
        fun canBlock(context: Context): Boolean = try {
            VpnService.prepare(context) == null
        } catch (_: Exception) {
            false
        }

        /** Requests the one-time VPN consent; returns whether it is on. */
        fun requestConsent(context: Context): Boolean = try {
            val consent = VpnService.prepare(context)
            if (consent != null) {
                context.startActivity(consent)
            }
            consent == null
        } catch (_: Exception) {
            false
        }

        /** Arms the kill-switch; false when VPN consent is missing. */
        fun block(context: Context): Boolean {
            if (!canBlock(context)) return false
            val intent = Intent(context, EnforceVpnService::class.java).setAction(ACTION_BLOCK)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Drops the kill-switch back down and arms a 24 h grace so a still
         * exceeded limit is not instantly re-blocked (the user chose to
         * restore the internet). Used by the Restore notification action and
         * by the configure dialogs when enforcement is switched off.
         */
        fun release(context: Context) {
            Store.setEnforcePauseUntil(
                context, System.currentTimeMillis() + 24L * 3_600_000L
            )
            if (!blocking) {
                context.stopService(Intent(context, EnforceVpnService::class.java))
                return
            }
            val intent = Intent(context, EnforceVpnService::class.java).setAction(ACTION_RELEASE)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, EnforceVpnService::class.java))
            }
        }
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    /**
     * True when the app itself is dropping the tunnel (Restore action /
     * enforcement disabled): then [onRevoke] — which the platform calls for
     * every closed VPN — must not warn the user.
     */
    @Volatile private var selfReleased = false

    private fun closeTunnel() {
        blocking = false
        val fd = tunFd
        tunFd = null
        if (fd != null) runCatching { fd.close() } // unblocks the blackhole read loop
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.enforce_vpn_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.enforce_vpn_notif_text)
        channel.setShowBadge(false)
        channel.setSound(null, null)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELEASE -> {
                // The system holds a bound connection on every established
                // VPN, so stopping the service is not enough — the tunnel fd
                // must be closed to end the session.
                if (!selfReleased) Store.setEnforcePauseUntil(
                    this, System.currentTimeMillis() + 24L * 3_600_000L
                )
                selfReleased = true
                closeTunnel()
                stopSelf()
            }
            ACTION_BLOCK -> startBlocking()
        }
        return START_NOT_STICKY
    }

    private fun startBlocking() {
        if (blocking) return
        if (VpnService.prepare(this) != null) {
            // Consent was revoked or never granted: report honestly.
            stopSelf()
            return
        }
        val fd: ParcelFileDescriptor = try {
            Builder()
                .setSession(getString(R.string.enforce_vpn_session))
                .setMtu(1500)
                .addAddress("10.111.222.223", 32)
                .addAddress("fdfe:dcba:9876::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .establish()
                ?: return stopSelf()
        } catch (_: Exception) {
            return stopSelf()
        }
        tunFd = fd
        val type =
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        blocking = true

        // Blackhole: read every packet and discard it. Nothing leaves the
        // device while this loop runs, so no data-limited traffic can pass.
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(4096)
        val thread = Thread {
            try {
                while (blocking) {
                    input.read(buf) // discard
                }
            } catch (_: Exception) {
                // fd closed / service stopped
            }
            runCatching { input.close() }
        }
        thread.name = "enforce-blackhole"
        thread.start()
        worker = thread
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_speed)
        .setContentTitle(getString(R.string.enforce_vpn_notif_title))
        .setContentText(getString(R.string.enforce_vpn_notif_text))
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            R.drawable.ic_stat_speed,
            getString(R.string.restore_internet),
            PendingIntent.getService(
                this, 2,
                Intent(this, EnforceVpnService::class.java).setAction(ACTION_RELEASE),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    override fun onDestroy() {
        selfReleased = true
        closeTunnel()
        runCatching { worker?.join(500) }
        worker = null
        super.onDestroy()
    }

    /**
     * Called when the user revokes the VPN from system settings (or when a
     * self-released tunnel is closed): stop the tunnel and, unless the app
     * itself triggered the release, tell the user enforcement is down.
     */
    override fun onRevoke() {
        val deliberate = selfReleased
        selfReleased = true
        closeTunnel()
        if (!deliberate) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_speed)
                .setContentTitle(getString(R.string.enforce_vpn_notif_title))
                .setContentText(getString(R.string.enforce_vpn_notif_text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .build()
            nm.notify(6, notif)
        }
        stopSelf()
    }
}
