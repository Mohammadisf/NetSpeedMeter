package dev.netspeed.meter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts live tracking after reboot when the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The reference app restarted itself when the previous run was still
        // marked running (serviceRunning pref) unless the user pressed Stop.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // The reference app resumed whenever the previous run was still
            // marked running (serviceRunning); the boot toggle also works on
            // its own for users who only want a manual restart after reboots.
            if (Store.isServiceRunning(context) || Store.isStartOnBoot(context)) {
                SpeedService.start(context)
            }
        }
    }
}
