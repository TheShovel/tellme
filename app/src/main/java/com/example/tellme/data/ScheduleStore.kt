package com.example.tellme.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists the list of [Schedule]s in SharedPreferences as JSON.
 * A SharedPreferences-backed singleton is enough here; no Room needed.
 */
object ScheduleStore {

    private const val PREFS = "tellme.schedules"
    private const val KEY_LIST = "schedule_list"

    private val gson = Gson()
    private val listType = object : TypeToken<List<Schedule>>() {}.type

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun all(): List<Schedule> {
        val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<Schedule>>(json, listType) }.getOrDefault(emptyList())
    }

    fun get(id: String): Schedule? = all().firstOrNull { it.id == id }

    fun upsert(schedule: Schedule) {
        val current = all().toMutableList()
        val idx = current.indexOfFirst { it.id == schedule.id }
        if (idx >= 0) current[idx] = schedule else current.add(schedule)
        prefs.edit().putString(KEY_LIST, gson.toJson(current, listType)).apply()
    }

    fun remove(id: String) {
        val current = all().toMutableList()
        current.removeAll { it.id == id }
        prefs.edit().putString(KEY_LIST, gson.toJson(current, listType)).apply()
    }

    fun enabled(): List<Schedule> = all().filter { it.enabled && it.days.isNotEmpty() }
}
