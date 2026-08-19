package com.example.tellme.worker

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import android.app.Service
import com.example.tellme.NotificationHelper
import com.example.tellme.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service launched directly by [com.example.tellme.scheduler.PreAlarmReceiver] ~2 minutes
 * before a scheduled brief.
 *
 * Why a service instead of WorkManager? On the genuine scheduling path the app is usually in the
 * background, and the OS **defers** background WorkManager work — which left the user stuck forever
 * on a "Preparing your brief…" notice (the PostReceiver fired, found no brief, and looped). A
 * foreground service (type [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]) starts immediately even
 * from the background, which is what an alarm-clock-style brief needs.
 *
 * It runs the shared [BriefGenerator] pipeline (ensure model -> web search -> load -> generate ->
 * close/unload -> store), then stops itself so the model is fully unloaded right after generation.
 */
class GenerateService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val activeJobs = mutableMapOf<Int, Job>()
    // Reuses NotificationHelper.WORK_NOTIF_ID for the foreground work notification.

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != Scheduler.ACTION_PRE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val scheduleId = intent.getStringExtra(Scheduler.EXTRA_SCHEDULE_ID)
        val trigger = intent.getLongExtra(Scheduler.EXTRA_TRIGGER, 0L)
        if (scheduleId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Promote to foreground immediately so the OS won't kill us during the heavy model load.
        val notification = NotificationHelper.workNotification(
            this, "TellMe", "Preparing your brief… (loading model)",
        )
        ServiceCompat.startForeground(
            this,
            NotificationHelper.WORK_NOTIF_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        val job = scope.launch {
            try {
                BriefGenerator.run(this@GenerateService, scheduleId, trigger)
            } finally {
                synchronized(activeJobs) { activeJobs.remove(startId) }
                // Stop only once every in-flight occurrence has finished.
                if (activeJobs.isEmpty()) stopSelf()
            }
        }
        synchronized(activeJobs) { activeJobs[startId] = job }

        // The brief is durable (stored in SharedPreferences), so if the OS kills us we don't need a
        // redelivery that would risk a duplicate run — START_NOT_STICKY is the efficient choice.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
        // Clear the foreground state if still active.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}
