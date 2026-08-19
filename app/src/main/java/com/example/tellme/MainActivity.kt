package com.example.tellme

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.AlarmManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.tellme.data.ScheduleStore
import com.example.tellme.model.OnDeviceModel
import com.example.tellme.scheduler.Scheduler
import com.example.tellme.ui.AlarmPermissionDialog
import com.example.tellme.ui.BriefDetailScreen
import com.example.tellme.ui.BriefHistoryScreen
import com.example.tellme.ui.MainViewModel
import com.example.tellme.ui.ModelDownloadDialog
import com.example.tellme.ui.ScheduleEditScreen
import com.example.tellme.ui.ScheduleListScreen
import com.example.tellme.worker.EnsureModelWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        armSchedulesAndModel()
        setContent { TellMeRoot(intent) }
    }

    private fun armSchedulesAndModel() {
        Scheduler.rescheduleAll(this)
        if (ScheduleStore.enabled().isNotEmpty() && !OnDeviceModel.modelFile(this).exists()) {
            WorkManager.getInstance(this).enqueueUniqueWork(
                "tellme-auto-model",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EnsureModelWorker>().build(),
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}

@Composable
fun TellMeRoot(launchIntent: Intent? = null) {
    val vm: MainViewModel = viewModel()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val modelState by vm.modelState.collectAsState()
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("tellme_setup", 0)
    var showAlarmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(modelState) {
        if (modelState is MainViewModel.ModelUiState.Ready && !showAlarmDialog) {
            val am = ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val needsPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                !am.canScheduleExactAlarms()
            } else false
            if (needsPermission) {
                showAlarmDialog = true
            }
        }
    }

    val showModelDownload = modelState !is MainViewModel.ModelUiState.Ready
            && modelState !is MainViewModel.ModelUiState.Error

    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            if (showModelDownload) {
                when (val state = modelState) {
                    is MainViewModel.ModelUiState.Checking -> {
                        ModelDownloadDialog(progress = 0, statusText = "Checking model…")
                    }

                    is MainViewModel.ModelUiState.Downloading -> {
                        ModelDownloadDialog(progress = state.progress, statusText = state.status)
                    }

                    else -> {}
                }
            }

            if (showAlarmDialog) {
                AlarmPermissionDialog(onDismiss = {
                    showAlarmDialog = false
                    prefs.edit().putBoolean("alarm_dialog_seen", true).apply()
                })
            }

            if (modelState is MainViewModel.ModelUiState.Error) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("Model error") },
                    text = { Text((modelState as MainViewModel.ModelUiState.Error).message) },
                    confirmButton = {
                        TextButton(onClick = { vm.checkModel() }) {
                            Text("Retry")
                        }
                    },
                )
            }

            if (modelState is MainViewModel.ModelUiState.Ready) {
                val nav = rememberNavController()
                val startDest = remember(launchIntent) {
                    if (launchIntent?.action == NotificationHelper.ACTION_OPEN_BRIEF) "brief" else "list"
                }
                val briefScheduleId = remember(launchIntent) {
                    launchIntent?.getStringExtra(NotificationHelper.EXTRA_SCHEDULE_ID) ?: ""
                }
                val briefTrigger = remember(launchIntent) {
                    launchIntent?.getLongExtra(NotificationHelper.EXTRA_TRIGGER_MILLIS, 0L) ?: 0L
                }

                NavHost(navController = nav, startDestination = startDest) {
                    composable("list") { ScheduleListScreen(nav, vm) }
                    composable(
                        route = "edit/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { back ->
                        val id = back.arguments?.getString("id") ?: "new"
                        ScheduleEditScreen(nav, id, vm)
                    }


                    composable("brief/{scheduleId}/{triggerMillis}") { back ->
                        val sid = back.arguments?.getString("scheduleId") ?: ""
                        val trigger = back.arguments?.getString("triggerMillis")?.toLongOrNull() ?: 0L
                        BriefDetailScreen(nav, sid, trigger)
                    }
                    // Legacy route for direct notification launch
                    composable("brief") {
                        BriefDetailScreen(nav, briefScheduleId, briefTrigger)
                    }
                    composable("history") {
                        BriefHistoryScreen(nav)
                    }
                }
            }
        }
    }
}
