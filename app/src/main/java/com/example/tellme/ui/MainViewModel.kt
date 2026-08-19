package com.example.tellme.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tellme.data.Schedule
import com.example.tellme.data.ScheduleStore
import com.example.tellme.model.OnDeviceModel
import com.example.tellme.scheduler.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _schedules = MutableStateFlow(ScheduleStore.all())
    val schedules: StateFlow<List<Schedule>> = _schedules

    // Model download state
    sealed class ModelUiState {
        data object Checking : ModelUiState()
        data class Downloading(val progress: Int, val status: String) : ModelUiState()
        data object Ready : ModelUiState()
        data class Error(val message: String) : ModelUiState()
    }

    private val _modelState = MutableStateFlow<ModelUiState>(ModelUiState.Checking)
    val modelState: StateFlow<ModelUiState> = _modelState

    init {
        checkModel()
    }

    fun checkModel() {
        viewModelScope.launch {
            _modelState.value = ModelUiState.Checking
            val ctx = getApplication<Application>()
            val file = OnDeviceModel.modelFile(ctx)
            if (file.exists() && file.length() > 0) {
                _modelState.value = ModelUiState.Ready
                return@launch
            }
            _modelState.value = ModelUiState.Downloading(0, "Preparing download…")
            val result = withContext(Dispatchers.IO) {
                OnDeviceModel.ensure(ctx) { p ->
                    viewModelScope.launch {
                        _modelState.value = ModelUiState.Downloading(
                            p,
                            if (p in 0..99) "Downloading model… $p%" else "Almost done…"
                        )
                    }
                }
            }
            _modelState.value = if (result.ready) {
                ModelUiState.Ready
            } else {
                ModelUiState.Error(result.message)
            }
        }
    }

    private fun refresh() { _schedules.value = ScheduleStore.all() }

    fun upsert(schedule: Schedule) {
        ScheduleStore.upsert(schedule)
        if (schedule.enabled && schedule.days.isNotEmpty()) {
            Scheduler.schedule(getApplication(), schedule)
        } else {
            Scheduler.cancel(getApplication(), schedule.id)
        }
        refresh()
    }

    fun delete(id: String) {
        ScheduleStore.remove(id)
        Scheduler.cancel(getApplication(), id)
        refresh()
    }

    fun toggle(id: String, enabled: Boolean) {
        val s = ScheduleStore.get(id) ?: return
        val updated = s.copy(enabled = enabled)
        ScheduleStore.upsert(updated)
        if (enabled && updated.days.isNotEmpty()) Scheduler.schedule(getApplication(), updated)
        else Scheduler.cancel(getApplication(), id)
        refresh()
    }
}
