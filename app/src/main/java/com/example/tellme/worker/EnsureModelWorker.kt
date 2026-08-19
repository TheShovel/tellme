package com.example.tellme.worker

import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.tellme.NotificationHelper
import com.example.tellme.model.OnDeviceModel

/**
 * Verifies (and downloads if configured) the on-device model before generation runs.
 * Runs as a foreground worker so it is not killed while a large download happens.
 */
class EnsureModelWorker(context: android.content.Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("Preparing the on-device model…"))
        // Always report success: if the model is missing, GenerateWorker detects it and stores a
        // clear error brief instead of silently dropping the notification.
        OnDeviceModel.ensure(applicationContext) { p ->
            // progress only used while downloading; lightweight, just update the notification text.
            updateProgress(p)
        }
        return Result.success()
    }

    private fun updateProgress(p: Int) {
        val n = NotificationHelper.workNotification(applicationContext, "TellMe", "Downloading model… $p%")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (applicationContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(WORK_ID, n)
        }
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val n = NotificationHelper.workNotification(applicationContext, "TellMe", text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(WORK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(WORK_ID, n)
        }
    }

    companion object {
        const val WORK_ID = 4200
    }
}
