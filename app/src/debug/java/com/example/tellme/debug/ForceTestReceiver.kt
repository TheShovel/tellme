package com.example.tellme.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tellme.data.Schedule
import com.example.tellme.data.ScheduleStore
import com.example.tellme.worker.BriefGenerator
import com.example.tellme.scheduler.PostReceiver
import com.example.tellme.scheduler.Scheduler
import com.example.tellme.web.WebSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class ForceTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FORCE_CANCEL -> {
                val scheduleId = intent.getStringExtra(Scheduler.EXTRA_SCHEDULE_ID) ?: return
                Scheduler.cancel(context, scheduleId)
                Log.i(TAG, "forced CANCEL for $scheduleId")
            }

            ACTION_FORCE_PRE -> {
                val scheduleId = intent.getStringExtra(Scheduler.EXTRA_SCHEDULE_ID) ?: run {
                    Log.w(TAG, "FORCE_PRE missing schedule_id")
                    return
                }
                val trigger = intent.getLongExtra(Scheduler.EXTRA_TRIGGER, 0L)
                val customPrompt = intent.getStringExtra("prompt")
                if (ScheduleStore.get(scheduleId) == null || customPrompt != null) {
                    val testSchedule = Schedule(
                        id = scheduleId,
                        hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                        minute = Calendar.getInstance().get(Calendar.MINUTE),
                        days = setOf(Calendar.getInstance().get(Calendar.DAY_OF_WEEK)),
                        enabled = true,
                        title = "Test Brief",
                        prompt = customPrompt ?: "What are the top news stories today?",
                    )
                    ScheduleStore.upsert(testSchedule)
                    Log.i(TAG, "auto-created test schedule '$scheduleId'")
                }
                Log.i(TAG, "forced PRE for $scheduleId trigger=$trigger")
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        BriefGenerator.run(context, scheduleId, trigger)
                        Log.i(TAG, "pipeline completed for $scheduleId")
                    } catch (e: Exception) {
                        Log.e(TAG, "pipeline failed for $scheduleId", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_FORCE_POST -> {
                val scheduleId = intent.getStringExtra(Scheduler.EXTRA_SCHEDULE_ID) ?: return
                val trigger = intent.getLongExtra(Scheduler.EXTRA_TRIGGER, 0L)
                val i = Intent(context, PostReceiver::class.java).apply {
                    action = Scheduler.ACTION_POST
                    putExtra(Scheduler.EXTRA_SCHEDULE_ID, scheduleId)
                    putExtra(Scheduler.EXTRA_TRIGGER, trigger)
                }
                context.sendBroadcast(i)
                Log.i(TAG, "forced POST for $scheduleId trigger=$trigger")
            }

            ACTION_SEARCH_TEST -> {
                Log.i(TAG, "=== SEARCH QUALITY TEST START ===")
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runSearchQualityTest()
                    } catch (e: Exception) {
                        Log.e(TAG, "search test failed", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    private suspend fun runSearchQualityTest() {
        val prompts = listOf(
            "weather in London today",
            "will it rain in New York tomorrow",
            "Android16 release date",
            "iOS 20 new features",
            "ChatGPT latest update",
            "GTA 6 release date updates",
            "Elden Ring DLC news",
            "Nintendo Switch 2 news",
            "Bitcoin price today",
            "S&P 500 market update",
            "best savings accounts 2026",
            "Premier League scores today",
            "NFL preseason results",
            "F1 race results",
            "new movies this week",
            "Spotify top songs",
            "Bad Bunny tour dates",
            "mechanical keyboard group buys",
            "3D printing news",
            "home automation deals",
            "iPhone deals eBay",
            "laptop sales best buy",
            "Amazon Prime Day deals",
            "NASA Mars mission update",
            "new vaccine research",
            "restaurants near me opening",
            "traffic updates downtown",
        )

        Log.i(TAG, "--- Testing ${prompts.size} prompts ---")
        var pass = 0
        var fail = 0
        var partial = 0

        for ((i, prompt) in prompts.withIndex()) {
            Log.i(TAG, "[${i + 1}/${prompts.size}] Prompt: \"$prompt\"")
            try {
                val results = WebSearch.search(prompt, 3)
                if (results.isEmpty()) {
                    Log.w(TAG, "  NO RESULTS")
                    fail++
                } else {
                    val hasSnippet = results.any { it.snippet.length > 20 }
                    Log.i(TAG, "  ${results.size} results (${if (hasSnippet) "with content" else "headlines only"})")
                    results.forEachIndexed { j, r ->
                        Log.i(TAG, "    [$j] ${r.title.take(70)}")
                        Log.i(TAG, "        url: ${r.url.take(60)}")
                        Log.i(TAG, "        snippet: ${r.snippet.take(100).replace("\n", " ")}")
                    }
                    if (hasSnippet) pass++ else partial++
                }
            } catch (e: Exception) {
                Log.e(TAG, "  ERROR: ${e.message}")
                fail++
            }
        }

        Log.i(TAG, "=== SEARCH QUALITY TEST DONE ===")
        Log.i(TAG, "Results: $pass passed, $partial partial, $fail failed out of ${prompts.size}")
    }

    companion object {
        const val ACTION_FORCE_PRE = "com.example.tellme.debug.FORCE_PRE"
        const val ACTION_FORCE_POST = "com.example.tellme.debug.FORCE_POST"
        const val ACTION_FORCE_CANCEL = "com.example.tellme.debug.FORCE_CANCEL"
        const val ACTION_SEARCH_TEST = "com.example.tellme.debug.FORCE_SEARCH_TEST"
        private const val TAG = "TellMe.ForceTest"
    }
}
