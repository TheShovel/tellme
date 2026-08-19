package com.example.tellme.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.tellme.data.Schedule
import com.example.tellme.data.ScheduleStore
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditScreen(nav: NavHostController, id: String, vm: MainViewModel = viewModel()) {
    val existing = if (id != "new") ScheduleStore.get(id) else null

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var prompt by remember { mutableStateOf(existing?.prompt ?: "") }
    var hour by remember { mutableStateOf(existing?.hour ?: 8) }
    var minute by remember { mutableStateOf(existing?.minute ?: 0) }
    var days by remember { mutableStateOf(existing?.days ?: setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)) }
    var showTimePicker by remember { mutableStateOf(false) }

    val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute)
    val canSave = prompt.isNotBlank() && days.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New brief" else "Edit brief") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    val schedule = Schedule(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        hour = hour,
                        minute = minute,
                        days = days,
                        enabled = true,
                        title = title.trim(),
                        prompt = prompt.trim(),
                    )
                    vm.upsert(schedule)
                    nav.popBackStack()
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Save") }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Label (optional)") },
                placeholder = { Text("e.g. Morning headline digest") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("What should it look into?") },
                placeholder = { Text("Summarize the top AI news and the weather in Tokyo today.") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Time", style = MaterialTheme.typography.labelLarge)
            Button(onClick = { showTimePicker = true }) {
                Text("%02d:%02d".format(hour, minute))
            }

            Text("Days", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in Calendar.SUNDAY..Calendar.SATURDAY) {
                    val label = Schedule.DAY_LABELS[d] ?: continue
                    FilterChip(
                        selected = days.contains(d),
                        onClick = {
                            days = if (days.contains(d)) days - d else days + d
                        },
                        label = { Text(label) },
                    )
                }
            }

            if (showTimePicker) {
                AlertDialog(
                    onDismissRequest = { showTimePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            hour = timeState.hour
                            minute = timeState.minute
                            showTimePicker = false
                        }) { Text("OK") }
                    },
                    dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
                    title = { Text("Pick a time") },
                    text = { TimePicker(state = timeState) },
                )
            }
        }
    }
}
