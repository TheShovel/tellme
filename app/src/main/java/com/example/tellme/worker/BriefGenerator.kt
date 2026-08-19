package com.example.tellme.worker

import android.content.Context
import android.util.Log
import com.example.tellme.NotificationHelper
import com.example.tellme.data.ArticleSource
import com.example.tellme.data.NotificationStore
import com.example.tellme.data.Schedule
import com.example.tellme.data.ScheduleStore
import com.example.tellme.model.OnDeviceModel
import com.example.tellme.web.ApiClient
import com.example.tellme.web.WebSearch
import kotlinx.coroutines.withTimeout

/**
 * Core efficient pipeline (runs on-device, off the UI thread):
 *   1. ensure model (downloads if missing) -- the heavy ~2-minute prep step
 *   2. key-free web search
 *   3. load model -> generate -> close (unload) so memory is freed right after
 *   4. store the brief, and post immediately only if we're already at/after the exact time
 *      (otherwise the exact-time [com.example.tellme.scheduler.PostReceiver] shows it).
 *
 * Used by [GenerateService] so the work starts the moment the pre-alarm fires, even when the
 * app is in the background (WorkManager would otherwise defer it).
 */
object BriefGenerator {

    private const val TAG = "TellMe.BriefGenerator"

    /** Stable notification id per schedule (collapses across weekly occurrences). */
    fun briefNotificationId(scheduleId: String): Int {
        val h = scheduleId.hashCode()
        return if (h < 0) -h else h
    }

    suspend fun run(context: Context, scheduleId: String, trigger: Long) {
        Log.i(TAG, "run start schedule=$scheduleId trigger=$trigger")
        val schedule: Schedule = ScheduleStore.get(scheduleId) ?: run {
            Log.w(TAG, "schedule $scheduleId missing")
            return
        }
        Log.i(TAG, "schedule loaded title=${schedule.title}")

        // Phase feedback so the foreground notice reflects what's actually happening.
        NotificationHelper.updateWork(context, "TellMe", "Searching the web…")

        // 1. model
        Log.i(TAG, "PHASE: ensure model (skip download if cached)")
        val modelState = OnDeviceModel.ensure(context)
        Log.i(TAG, "ensure done ready=${modelState.ready} msg=${modelState.message}")
        if (!modelState.ready || modelState.path == null) {
            storeAndMaybePost(context, schedule, trigger, schedule.title.ifBlank { "TellMe" }, modelState.message)
            NotificationHelper.dismissWork(context)
            return
        }

        // 2. web search (bounded so a slow network can't stall the brief)
        Log.i(TAG, "PHASE: web search start query='${schedule.prompt}'")
        val results = runCatching {
            withTimeout(45_000) { WebSearch.search(schedule.prompt, 8) }
        }.getOrDefault(emptyList())
        Log.i(TAG, "web results=${results.size} first='${results.firstOrNull()?.title ?: ""}'")
        results.forEachIndexed { i, r ->
            Log.i(TAG, "  [search $i] ${r.title}")
            Log.i(TAG, "      snippet(${r.snippet.length}): ${r.snippet.take(160).replace("\n", " ")}")
        }

        NotificationHelper.updateWork(context, "TellMe", "Loading model & writing your brief…")

        // 3. Load model once, use for both passes
        Log.i(TAG, "PHASE: load model")
        val llm = runCatching {
            OnDeviceModel.createInstance(context, modelState.path, maxTokens = 1024)
        }.getOrElse { e ->
            Log.e(TAG, "model load failed", e)
            storeAndMaybePost(context, schedule, trigger, schedule.title.ifBlank { "TellMe" }, "Model load failed: ${e.message}")
            NotificationHelper.dismissWork(context)
            return
        }

        try {
            // Pass 1: Ask LLM which APIs to call
            Log.i(TAG, "PHASE: pass 1 - decide API calls")
            val toolPrompt = buildToolCallPrompt(schedule.prompt)
            val toolResponse = runCatching { llm.generateResponse(toolPrompt) }.getOrDefault("")
            Log.i(TAG, "pass 1 response: ${toolResponse.take(200)}")
            val toolCalls = ApiClient.parseToolCalls(toolResponse)
            Log.i(TAG, "parsed ${toolCalls.size} tool calls: $toolCalls")

            // Call the APIs the LLM requested
            NotificationHelper.updateWork(context, "TellMe", "Fetching live data…")
            val apiResults = mutableListOf<String>()
            for ((apiName, args) in toolCalls) {
                val data = runCatching {
                    withTimeout(10_000) { ApiClient.call(apiName, args) }
                }.getOrDefault("")
                if (data.isNotBlank()) {
                    apiResults.add(data)
                    Log.i(TAG, "API $apiName: ${data.take(100)}")
                }
            }
            val apiData = apiResults.joinToString("\n")
            Log.i(TAG, "total API data chars=${apiData.length}")

            // Pass 2: Generate the final brief
            Log.i(TAG, "PHASE: pass 2 - generate brief")
            val prompt = buildBriefPrompt(schedule.prompt, results, apiData)
            Log.i(TAG, "prompt built chars=${prompt.length}")
            val brief = runCatching { cleanup(llm.generateResponse(prompt)) }.getOrElse { e ->
                Log.e(TAG, "generation failed", e)
                "Couldn't generate a brief: ${e.message}"
            }
            Log.i(TAG, "PHASE: model generate done brief len=${brief.length}")

            NotificationHelper.updateWork(context, "TellMe", "Brief ready — saving…")

            // Store + post
            val sources = results.map { r ->
                ArticleSource(
                    headline = r.title.substringBefore(" — ").trim(),
                    url = r.url,
                    snippet = r.snippet.take(200),
                )
            }
            NotificationStore.saveSources(schedule.id, trigger, sources)
            storeAndMaybePost(context, schedule, trigger, schedule.title.ifBlank { "TellMe" }, brief)
        } finally {
            llm.close()
            Log.i(TAG, "model unloaded")
        }

        NotificationHelper.updateWork(context, "TellMe", "Brief ready — saving…")

        // 4. store + maybe post immediately (covers the late-generation edge case)
        // Save article sources for the detail screen
        val sources = results.map { r ->
            ArticleSource(
                headline = r.title.substringBefore(" — ").trim(),
                url = r.url,
                snippet = r.snippet.take(200),
            )
        }
        NotificationHelper.dismissWork(context)
    }

    private fun storeAndMaybePost(
        context: Context,
        schedule: Schedule,
        trigger: Long,
        title: String,
        body: String,
    ) {
        NotificationStore.saveBrief(schedule.id, trigger, title, body)
        // If we're at/after the exact time, the PostReceiver may have already fired; post now so
        // the user still receives the (final) brief. Same notification id => no duplicates.
        if (System.currentTimeMillis() >= trigger) {
            com.example.tellme.NotificationHelper.showBrief(
                context,
                briefNotificationId(schedule.id),
                title,
                body,
                scheduleId = schedule.id,
                triggerMillis = trigger,
            )
        }
    }

    /** Pass 1: Ask the LLM which API tools to call for this query. */
    private fun buildToolCallPrompt(userPrompt: String): String {
        val system = "You are a data-fetching assistant. Given a user query, decide which APIs to call.\n" +
                "AVAILABLE TOOLS (pick the best match):\n" +
                "- get_weather: current weather. Args: city=\"CityName\"\n" +
                "- get_forecast: multi-day forecast. Args: city=\"CityName\", days=\"3\"\n" +
                "- get_crypto_prices: crypto prices. Args: coins=\"bitcoin,ethereum,solana\"\n" +
                "" +
                "- get_exchange_rate: currency conversion. Args: from=\"USD\", to=\"EUR\"\n" +
                "- get_definition: word definitions. Args: word=\"serendipity\"\n" +
                "- get_nasa_apod: NASA picture of the day. No args.\n" +
                "- get_hackernews_top: top tech news. Args: count=\"5\"\n" +
                "" +
                "- get_holidays: public holidays. Args: country=\"US\"\n" +
                "- get_tv_show: TV show info. Args: query=\"Breaking Bad\"\n" +
                "- get_book: book search. Args: query=\"Dune\"\n" +
                "- get_random_joke: random joke. No args.\n" +
                "- get_wikipedia: encyclopedia. Args: topic=\"quantum computing\"\n" +
                "- get_ip_info: current IP info. No args.\n" +
                "- get_quote: random quote. No args.\n" +
                "OUTPUT FORMAT: [tool_name arg=\"value\"]\n" +
                "Examples:\n" +
                "- Weather: [get_weather city=\"Brasov\"]\n" +
                "- Bitcoin: [get_crypto_prices coins=\"bitcoin\"]\n" +
                "" +
                "- Currency: [get_exchange_rate from=\"USD\" to=\"GBP\"]\n" +
                "- Definition: [get_definition word=\"ephemeral\"]\n" +
                "- Multiple: [get_weather city=\"London\"] [get_crypto_prices coins=\"bitcoin\"]\n" +
                "If no tool fits, output: []"
        val user = userPrompt
        return wrapForModel(system, user)
    }

    /** Pass 2: Generate the final brief with search results + API data. */
    private fun buildBriefPrompt(userPrompt: String, results: List<WebSearch.SearchResult>, apiData: String = ""): String {
        val system =
            "You are a concise news briefing assistant. You receive REAL article headlines from the web.\n" +
                    "Write a 1-2 sentence summary in natural prose. Max 50 words.\n" +
                    "FORMAT: Write plain paragraphs. Do NOT use numbered lists, bullet points, dashes, or brackets.\n" +
                    "Do NOT mention publisher names like BBC, CNN, or Reuters. Focus only on the events.\n" +
                    "RULES:\n" +
                    "1. Summarize the key events from the headlines into natural sentences.\n" +
                    "2. Only state facts from the headlines and API data. Never invent details.\n" +
                    "3. Ignore dates in parentheses after publisher names.\n" +
                    "4. Do NOT say no articles available. The headlines are your source material.\n" +
                    "5. If real-time API data is provided, use it instead of headlines for that topic."
        val userBuilder = StringBuilder()
        userBuilder.append(userPrompt).append("\n\n")
        if (apiData.isNotBlank()) {
            userBuilder.append("REAL-TIME DATA:\n")
            userBuilder.append(apiData).append("\n\n")
        }
        if (results.isEmpty()) {
            userBuilder.append("(No articles were found.)\n")
        } else {
            val perSnippet = 300
            val cap = 2800
            var used = 0
            for ((i, r) in results.withIndex()) {
                val title = r.title.substringBefore(" - ").take(140)
                val snippet = r.snippet.take(perSnippet)
                val block = buildString {
                    append(title)
                    if (snippet.isNotBlank()) append("\n$snippet")
                    append("\n\n")
                }
                if (used + block.length > cap) break
                userBuilder.append(block)
                used += block.length
            }
        }
        return wrapForModel(system, userBuilder.toString())
    }

    private fun cleanup(s: String): String {
        var c = s
            .replace("<|im_end|>", "")
            .replace("<end_of_turn>", "", ignoreCase = true)
            .replace("</s>", "", ignoreCase = true)
            .replace("##", "")
            .replace("**", "")
            .replace("*", "")
            .replace("`", "")
            .replace("#", "")
            .trim()
        if (c.length > 200) {
            c = c.substring(0, 200)
            val last = c.lastIndexOf(" ")
            c = if (last > 120) c.substring(0, last) + "..." else c + "..."
        }
        return c
    }


    /** Wrap the system + user turns in the chat template expected by the configured model. */
    private fun wrapForModel(system: String, user: String): String {
        val url = NotificationStore.getModelUrl()
        return if (url.contains("Gemma", ignoreCase = true)) {
            "<start_of_turn>user\n$system\n\n$user<end_of_turn>\n<start_of_turn>model\n"
        } else {
            "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        }
    }
}
