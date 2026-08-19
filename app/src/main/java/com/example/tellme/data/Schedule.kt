package com.example.tellme.data

import java.util.Calendar

/**
 * A single scheduled brief, configured alarm-clock style.
 *
 * @param id        Stable unique id (used as part of PendingIntent request codes).
 * @param hour      Hour of day in 24h format (0..23).
 * @param minute    Minute of hour (0..59).
 * @param days      Set of [Calendar] weekday constants (Calendar.SUNDAY = 1 .. Calendar.SATURDAY = 7).
 *                  Empty set means the schedule is effectively disabled even if [enabled] is true.
 * @param enabled   Whether the schedule is active.
 * @param title     Short label shown in the notification title.
 * @param prompt    The user's instruction for the on-device model (what to search + how to summarize).
 */
data class Schedule(
    val id: String,
    val hour: Int,
    val minute: Int,
    val days: Set<Int>,
    val enabled: Boolean,
    val title: String,
    val prompt: String,
) {
    companion object {
        /** Weekday labels for UI, indexed by Calendar weekday constant (1..7). */
        val DAY_LABELS = mapOf(
            Calendar.SUNDAY to "Sun",
            Calendar.MONDAY to "Mon",
            Calendar.TUESDAY to "Tue",
            Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu",
            Calendar.FRIDAY to "Fri",
            Calendar.SATURDAY to "Sat",
        )
    }
}
