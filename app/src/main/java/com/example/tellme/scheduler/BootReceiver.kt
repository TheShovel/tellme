package com.example.tellme.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms all enabled schedules after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Scheduler.rescheduleAll(context)
        }
    }
}
