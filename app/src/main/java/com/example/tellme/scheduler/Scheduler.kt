package com.example.tellme.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.tellme.data.NotificationStore
import com.example.tellme.data.Schedule
import com.example.tellme.data.ScheduleStore
import java.util.Calendar

/**
 * Owns all AlarmManager interaction. Core idea (per the app design):
 *
 *  - [schedule] registers a "pre" alarm for the next occurrence of a schedule. A pre alarm fires
 *    [PRE_ALARM_OFFSET_MS] before the exact time.
 *  - [PreAlarmReceiver], on the pre alarm, enqueues model+generation work, schedules the
 *    "post" alarm at the exact time (so the brief is shown punctually), and schedules the next
 *    "pre" alarm (for the following occurrence).
 *  - [PostReceiver] reads the stored brief at the exact time and shows it. If the brief is not
 *    ready it retries a few times, then shows an error notice.
 *
 * Intents are keyed by schedule id (via a data URI) so editing/cancelling a schedule is clean.
 * Each occurrence uses the same pre/post data URIs, so at most one pending pre and one pending
 * post alarm exist per schedule at any moment -- no accidental overwrites of the current occurrence.
 */
object Scheduler {

    const val ACTION_PRE = "com.example.tellme.action.PRE_ALARM"
    const val ACTION_POST = "com.example.tellme.action.POST"

    const val EXTRA_SCHEDULE_ID = "schedule_id"
    const val EXTRA_TRIGGER = "trigger_millis"

    /** How early (ms) the model loads and generation runs. 2 minutes. */
    const val PRE_ALARM_OFFSET_MS = 2L * 60 * 1000

    private const val TAG = "TellMe.Scheduler"

    private fun preIntent(ctx: Context, scheduleId: String, triggerMillis: Long): Intent =
        Intent(ctx, PreAlarmReceiver::class.java).apply {
            action = ACTION_PRE
            data = android.net.Uri.parse("tellme://$scheduleId/pre")
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_TRIGGER, triggerMillis)
        }

    private fun postIntent(ctx: Context, scheduleId: String, triggerMillis: Long): Intent =
        Intent(ctx, PostReceiver::class.java).apply {
            action = ACTION_POST
            data = android.net.Uri.parse("tellme://$scheduleId/post")
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_TRIGGER, triggerMillis)
        }

    private fun pending(ctx: Context, intent: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(ctx, 0, intent, flags)
    }

    /** Soonest occurrence (ms) of this schedule strictly after [from], or [Long.MAX_VALUE]. */
    fun nextTriggerMillis(from: Long, hour: Int, minute: Int, days: Set<Int>): Long {
        if (days.isEmpty()) return Long.MAX_VALUE
        var best = Long.MAX_VALUE
        for (day in days) {
            val cal = Calendar.getInstance().apply { timeInMillis = from }
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            var diff = day - cal.get(Calendar.DAY_OF_WEEK)
            // If the target weekday is today but already passed (or is "now"), roll a week forward.
            if (diff < 0 || (diff == 0 && cal.timeInMillis <= from)) diff += 7
            cal.add(Calendar.DAY_OF_MONTH, diff)
            if (cal.timeInMillis < best) best = cal.timeInMillis
        }
        return best
    }

    /** Register the pre alarm for the next occurrence after [from] (default: now). */
    fun schedule(ctx: Context, schedule: Schedule, from: Long = System.currentTimeMillis()) {
        if (!schedule.enabled || schedule.days.isEmpty()) return
        val trigger = nextTriggerMillis(from, schedule.hour, schedule.minute, schedule.days)
        if (trigger == Long.MAX_VALUE) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val preTime = (trigger - PRE_ALARM_OFFSET_MS).coerceAtLeast(System.currentTimeMillis())
        val piPre = pending(ctx, preIntent(ctx, schedule.id, trigger))

        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTime, piPre)
        } else {
            Log.w(TAG, "Exact alarms not permitted; falling back to inexact.")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTime, piPre)
        }
        Log.i(TAG, "Scheduled pre for '${schedule.title}' at $preTime (occurrence $trigger)")
    }

    /** Register the post (exact-time) alarm for the current occurrence. Called by PreAlarmReceiver. */
    fun schedulePost(ctx: Context, scheduleId: String, triggerMillis: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val piPost = pending(ctx, postIntent(ctx, scheduleId, triggerMillis))
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, piPost)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, piPost)
        }
        Log.i(TAG, "Scheduled post for $scheduleId at $triggerMillis")
    }

    /** Convenience used by receivers: schedule the following occurrence after [afterTrigger]. */
    fun scheduleNext(ctx: Context, schedule: Schedule, afterTrigger: Long) {
        schedule(ctx, schedule, from = afterTrigger + 1)
    }

    fun rescheduleAll(ctx: Context) {
        ScheduleStore.enabled().forEach { schedule(ctx, it) }
    }

    fun cancel(ctx: Context, scheduleId: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Recreate intents; cancel matches by action + data URI (schedule id), ignoring extras.
        val trigger = System.currentTimeMillis()
        am.cancel(pending(ctx, preIntent(ctx, scheduleId, trigger)))
        am.cancel(pending(ctx, postIntent(ctx, scheduleId, trigger)))
        Log.i(TAG, "Cancelled alarms for $scheduleId")
    }

    /** Helper so workers/receivers build the storage key consistently. */
    fun briefKey(scheduleId: String, triggerMillis: Long): String = NotificationStore.key(scheduleId, triggerMillis)
}
