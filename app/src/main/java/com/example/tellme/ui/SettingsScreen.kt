package com.example.tellme.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.tellme.data.NotificationStore
import com.example.tellme.model.OnDeviceModel
import com.example.tellme.worker.EnsureModelWorker
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val modelFile = remember { OnDeviceModel.modelFile(context) }
    val modelReady = remember { modelFile.exists() && modelFile.length() > 0 }

    var url by remember { mutableStateOf(NotificationStore.getModelUrl()) }
    var token by remember { mutableStateOf(NotificationStore.getModelToken()) }
    var status by remember { mutableStateOf<String?>(null) }

    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("On-device model", style = MaterialTheme.typography.titleMedium)
            Text(
                if (modelReady) {
                    "Model ready: ${modelFile.name} (%.1f MB)".format(
                        Locale.US, modelFile.length() / (1024.0 * 1024.0),
                    )
                } else {
                    "Model not found: ${modelFile.absolutePath}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "The app downloads the model itself. The default is Qwen2.5-0.5B-Instruct, a small " +
                    "open model that downloads with no API key and no billing. You can instead paste a " +
                    "different MediaPipe .task URL (e.g. Gemma-3-1B-IT for higher quality).\n" +
                    "  adb push model.task ${modelFile.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Model download URL (.task)") },
                placeholder = { Text("https://huggingface.co/.../model.task") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("HuggingFace token (optional)") },
                placeholder = { Text("hf_... only needed for gated models like Gemma") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    NotificationStore.setModelUrl(url.trim())
                    NotificationStore.setModelToken(token.trim())
                    status = "Settings saved."
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save settings") }

            Button(
                onClick = {
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "tellme-settings-download",
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<EnsureModelWorker>().build(),
                    )
                    status = "Download started — watch the background-work notification. The model is large; keep the app open."
                },
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download model now") }

            if (!canExact) {
                Text(
                    "Exact-alarm permission is required for notifications to fire on time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant exact-alarm permission") }
            }

            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text(
                "TellMe runs fully on-device: no API keys, no cloud billing. Web search uses " +
                    "key-free sources (DuckDuckGo / Wikipedia). The on-device model is loaded only " +
                    "in the ~2 minutes before each scheduled time and unloaded right after generating.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
