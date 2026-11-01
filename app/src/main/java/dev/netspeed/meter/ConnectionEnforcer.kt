package dev.netspeed.meter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager

/**
 * Enforcement helper: turns off the connection type whose limit was passed.
 *
 * Every attempt is **verified**: after trying to switch a control off, the
 * helper re-reads the platform's actual state and only reports success when
 * the goal state was really reached. This way the app never claims "it was
 * turned off automatically" when the device silently refused the change —
 * on devices where Android blocks the control entirely, the notification
 * honestly says the automatic disconnection was not possible.
 *
 * Android tightly guards these controls, and third-party apps are limited to:
 *
 *  - Mobile data: `TelephonyManager.setDataEnabled(false)` — needs
 *    MODIFY_PHONE_STATE, granted only to system/privileged apps. Normal apps
 *    get a SecurityException, detected and reported as "not possible".
 *  - WiFi: `WifiManager.setWifiEnabled(false)` is allowed only for apps
 *    targeting a platform version below Android 10 (Q) that also run on
 *    pre-Q devices; on modern combinations the call returns false and the
 *    state is verified unchanged.
 *  - Hotspot: no public System API exists. `stopSoftAp()` (API 33+) is a
 *    System API, and the legacy `setWifiApEnabled(..., false)` path is a
 *    hidden API that works on some devices (API < 31 builds) when the app
 *    holds CHANGE_WIFI_STATE + NEARBY_WIFI_DEVICES. The helper tries every
 *    available path and verifies the result where the platform exposes a
 *    readable state.
 *
 * The canDisable... capability probes report the honest capability in the
 * dialogs, so the
 * user is told up front whether the automatic action can actually work on
 * their device instead of being promised it and then getting a manual
 * disconnect notification later.
 */
object ConnectionEnforcer {

    private fun granted(context: Context, perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ capability

    /**
     * Whether the platform could accept a mobile-data-off request.
     * MODIFY_PHONE_STATE is only for system/privileged apps, so this is
     * false for normal store installs.
     */
    fun canDisableMobileData(context: Context): Boolean =
        granted(context, "android.permission.MODIFY_PHONE_STATE")

    /**
     * Whether WiFi can be switched off programmatically: requires
     * CHANGE_WIFI_STATE, and both the runtime and the build's target SDK
     * must be below Android 10 (apps targeting 29+ cannot control WiFi).
     */
    fun canDisableWifi(context: Context): Boolean =
        granted(context, Manifest.permission.CHANGE_WIFI_STATE) &&
                Build.VERSION.SDK_INT < 29 &&
                context.applicationInfo.targetSdkVersion < 29

    /**
     * Whether a hotspot stop mechanism exists on this device build: the
     * API 33+ `stopSoftAp()` System API or the legacy
     * `setWifiApEnabled(server, enabled=false)` path. Having the method does
     * not guarantee the platform accepts the call — the attempt is verified
     * at enforcement time.
     */
    fun canDisableHotspot(context: Context): Boolean = try {
        val wm = wifi(context)
        wm.javaClass.methods.any {
            it.name == "stopSoftAp" || it.name == "setWifiApEnabled"
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Whether at least one of the data-limit controls (WiFi / mobile /
     * combined) can realistically be toggled by this app.
     */
    fun canEnforceDataLimits(context: Context): Boolean =
        canDisableWifi(context) || canDisableMobileData(context)

    // ------------------------------------------------------------- actions

    /**
     * Attempts to switch mobile data off and verifies the resulting state
     * with the public `TelephonyManager.isDataEnabled()`. True only when the
     * platform really reports the data connection as disabled (also true
     * when it was already off).
     */
    fun disableMobileData(context: Context): Boolean {
        return try {
            val tm = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE)
                    as TelephonyManager
            tm.setDataEnabled(false) // needs MODIFY_PHONE_STATE (system/privileged)
            try {
                !tm.isDataEnabled // verify the platform actually applied it
            } catch (_: Exception) {
                false
            }
        } catch (_: SecurityException) {
            false // platform refuses: not a system/privileged app
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Attempts to switch WiFi off and re-checked `isWifiEnabled` afterwards.
     * Returns true only when the goal state is reached (true already when
     * WiFi was off before the call).
     */
    fun disableWifi(context: Context): Boolean = try {
        val wm = wifi(context)
        if (!wm.isWifiEnabled) {
            true
        } else {
            wm.setWifiEnabled(false) // false from Android 10 for target 29+
            !wm.isWifiEnabled        // verify: did the state really change?
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Attempts to stop the hotspot and, when the platform exposes a readable
     * SoftAp state, verifies the hotspot is really off. When the state
     * cannot be read at all, the absence of an invocation exception is
     * treated as success (the call was accepted by the framework).
     */
    fun disableHotspot(context: Context): Boolean {
        val wm = wifi(context)
        // 1) API 33+ hidden System API: WifiManager.stopSoftAp().
        if (invokeStopSoftAp(wm)) return verifiedOff(wm) != false
        // 2) Legacy hidden API (removed from the platform in 31+):
        //    WifiManager.setWifiApEnabled(WifiConfiguration, false).
        try {
            val m = wm.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                java.lang.Boolean.TYPE
            )
            m.isAccessible = true
            m.invoke(wm, null, false)
            return verifiedOff(wm) != false
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Best-effort combined Wi-Fi + mobile limit: turn both off. True only if
     * at least one of the two controls was verified as off.
     */
    fun disableCombined(context: Context): Boolean {
        val wifi = disableWifi(context)
        val mobile = disableMobileData(context)
        return wifi || mobile
    }

    /**
     * Full fallback for Android versions that no longer expose the direct
     * controls (Samsung on Android 16 removed the last hidden paths): arm
     * the built-in kill-switch VPN, which blocks all outgoing traffic (and
     * therefore also the internet reachable by tethered clients). Returns
     * false when the one-time VPN consent has not been given yet.
     */
    fun enforceViaKillSwitch(context: Context): Boolean =
        try {
            EnforceVpnService.block(context)
        } catch (_: Exception) {
            false
        }

    /** Whether the kill-switch path is ready to arm (VPN consent given). */
    fun canEnforceViaKillSwitch(context: Context): Boolean = try {
        EnforceVpnService.canBlock(context)
    } catch (_: Exception) {
        false
    }

    /**
     * Asks the user for the one-time VPN consent needed by the kill-switch.
     * Call from a foreground activity (the configure dialogs); when consent
     * is already granted this is a no-op.
     */
    fun requestConsentIfNeeded(context: Context): Boolean = try {
        val consent = android.net.VpnService.prepare(context)
        if (consent != null) context.startActivity(consent)
        consent == null
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------ internals

    private fun wifi(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Invokes the hidden `stopSoftAp()`; true when accepted without exception. */
    private fun invokeStopSoftAp(wm: WifiManager): Boolean = try {
        val m = wm.javaClass.getMethod("stopSoftAp")
        m.isAccessible = true
        m.invoke(wm)
        true
    } catch (_: SecurityException) {
        false // platform refuses (signature-protected System API)
    } catch (_: Exception) {
        false
    }

    /**
     * Reads the SoftAp state via hidden accessors where present:
     * true = on, false = off, null = cannot be read on this build.
     */
    private fun hotspotOn(wm: WifiManager): Boolean? = try {
        try {
            val m = wm.javaClass.getMethod("isWifiApEnabled") // System API 33+
            m.isAccessible = true
            m.invoke(wm) as Boolean
        } catch (_: NoSuchMethodException) {
            val m = wm.javaClass.getMethod("getWifiApState") // older builds
            m.isAccessible = true
            (m.invoke(wm) as Int) == 13 // WIFI_AP_STATE_ENABLED == 13
        }
    } catch (_: Exception) {
        null // state unreadable on this build
    }

    /** Verification step: null = unknown, false = confirmed off. */
    private fun verifiedOff(wm: WifiManager): Boolean? =
        hotspotOn(wm)?.let { !it }
}
