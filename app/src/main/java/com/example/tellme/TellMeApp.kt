package com.example.tellme

import android.app.Application
import com.example.tellme.data.NotificationStore
import com.example.tellme.data.ScheduleStore

/** Initializes process-wide singletons before any receiver or UI runs. */
class TellMeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScheduleStore.init(this)
        NotificationStore.init(this)
        NotificationHelper.init(this)
    }
}
