package com.example.tellme.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.tellme.NotificationHelper
import com.example.tellme.data.NotificationStore
import com.example.tellme.worker.BriefGenerator

/**
 * Fires at the exact scheduled time and shows the stored brief. If generation has not finished
 * yet (rare), it retries silently on a short interval; if it still isn't ready it shows a
 * friendly error so the user is never left with a silent, empty notification.
 */
class PostReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_RETRY = "retry"
        const val MAX_RETRY = 6
        private const val RETRY_INTERVAL_MS = 30_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_POST) return
        val scheduleId = intent.getStringExtra(Scheduler.EXTRA_SCHEDULE_ID) ?: return
        val trigger = intent.getLongExtra(Scheduler.EXTRA_TRIGGER, 0L)
        val retry = intent.getIntExtra(EXTRA_RETRY, 0)
        val id = BriefGenerator.briefNotificationId(scheduleId)

        val brief = NotificationStore.loadBrief(scheduleId, trigger)
        if (brief != null) {
            NotificationHelper.showBrief(context, id, brief.first, brief.second, scheduleId, trigger)
            return
        }

        if (retry < MAX_RETRY) {
            // Retry silently: posting an alerting placeholder here (and on each retry) made the
            // phone notify repeatedly before the real brief landed. Progress is already visible
            // via the silent foreground work notification.
            scheduleRetry(context, scheduleId, trigger, retry + 1)
        } else {
            NotificationHelper.showBrief(
                context, id, "TellMe",
                "Couldn't generate a brief for this time. The model may be missing, or the device was offline during the 2-minute prep window.",
            )
        }
    }

    private fun scheduleRetry(context: Context, scheduleId: String, trigger: Long, retry: Int) {
        val retryIntent = Intent(context, PostReceiver::class.java).apply {
            action = Scheduler.ACTION_POST
            data = android.net.Uri.parse("tellme://$scheduleId/post")
            putExtra(Scheduler.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(Scheduler.EXTRA_TRIGGER, trigger)
            putExtra(EXTRA_RETRY, retry)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, retryIntent, flags)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + RETRY_INTERVAL_MS
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
