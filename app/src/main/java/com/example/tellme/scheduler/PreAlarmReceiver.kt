package com.example.tellme.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.tellme.data.ScheduleStore
import com.example.tellme.worker.GenerateService

/**
 * Fires ~2 minutes before the scheduled time. It:
 *   1. starts the [GenerateService] foreground service immediately (which runs even while the app is
 *      in the background, unlike WorkManager),
 *   2. schedules the exact-time post alarm, and
 *   3. schedules the next occurrence's pre alarm.
 */
class PreAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_PRE) return
        val scheduleId = intent.getStringExtra(Scheduler.EXTRA_SCHEDULE_ID) ?: return
        val trigger = intent.getLongExtra(Scheduler.EXTRA_TRIGGER, 0L)

        // Start generation immediately as a foreground service. This runs even when the app is in
        // the background, so the brief is ready by the exact time instead of being deferred.
        val serviceIntent = Intent(context, GenerateService::class.java).apply {
            action = Scheduler.ACTION_PRE
            putExtra(Scheduler.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(Scheduler.EXTRA_TRIGGER, trigger)
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            // Extremely unlikely (dataSync FGS is allowed from the background for an alarm-triggered
            // receiver). If it ever fails, fall back to the stored-error path via the service's own
            // post scheduling: nothing else we can do here without a running process.
            android.util.Log.e(TAG, "startForegroundService failed", e)
        }

        Scheduler.schedulePost(context, scheduleId, trigger)
        val schedule = ScheduleStore.get(scheduleId)
        if (schedule != null && schedule.enabled && schedule.days.isNotEmpty()) {
            Scheduler.scheduleNext(context, schedule, trigger)
        }
    }

    companion object {
        private const val TAG = "TellMe.PreAlarmReceiver"
    }
}
