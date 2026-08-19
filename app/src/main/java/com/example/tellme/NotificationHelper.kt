package com.example.tellme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.Html
import androidx.core.app.NotificationCompat
import com.example.tellme.R

object NotificationHelper {

    const val CHANNEL_BRIEFS = "tellme.briefs"
    const val CHANNEL_WORK = "tellme.work"
    const val WORK_NOTIF_ID = 4201
    const val ACTION_OPEN_BRIEF = "com.example.tellme.OPEN_BRIEF"
    const val EXTRA_SCHEDULE_ID = "schedule_id"
    const val EXTRA_TRIGGER_MILLIS = "trigger_millis"

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BRIEFS,
                    context.getString(R.string.channel_briefs_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_briefs_desc) }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WORK,
                    context.getString(R.string.channel_work_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_work_desc) }
            )
        }
    }

    /** A notification shown to the user for a generated brief. Tapping opens the brief detail screen. */
    fun showBrief(context: Context, id: Int, title: String, body: String, scheduleId: String = "", triggerMillis: Long = 0L) {
        val styled = markdownToHtml(body)
        val preview = if (body.length <= 160) body else body.substring(0, 160).let { s ->
            val lastSpace = s.lastIndexOf(' ')
            if (lastSpace > 100) s.substring(0, lastSpace) + "…" else s + "…"
        }
        // PendingIntent to open the brief detail screen
        val detailIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_BRIEF
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_TRIGGER_MILLIS, triggerMillis)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, id, detailIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_BRIEFS)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(Html.fromHtml(markdownToHtml(preview), Html.FROM_HTML_MODE_COMPACT))
            .setStyle(NotificationCompat.BigTextStyle().bigText(Html.fromHtml(styled, Html.FROM_HTML_MODE_COMPACT)))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    /** Notification used for the foreground worker status (model load / generation progress). */
    fun workNotification(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_WORK)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    /** Update the foreground "work" notification in place (same id used by [GenerateService]). */
    fun updateWork(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(WORK_NOTIF_ID, workNotification(context, title, text))
    }

    /** Dismiss the work/progress notification after the pipeline completes. */
    fun dismissWork(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(WORK_NOTIF_ID)
    }

    /**
     * Render a tiny, safe markdown subset (bold, italic, headings, inline code, line breaks) to HTML
     * so notifications show emphasis instead of literal '**' asterisks. The model text is HTML-escaped
     * first, so it cannot inject tags.
     */
    private fun markdownToHtml(md: String): String {
        var s = md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        s = s.replace(Regex("""(?m)^#{1,6}\s+(.*)$"""), """<b>$1</b><br>""")
        s = s.replace(Regex("""\*\*(.+?)\*\*"""), """<b>$1</b>""")
        s = s.replace(Regex("""\*([^*]+?)\*"""), """<i>$1</i>""")
        s = s.replace(Regex("""`(.+?)`"""), """$1""")
        s = s.replace("\n", "<br>")
        return s
    }
}
