package com.example.tellme.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the generated brief text keyed by schedule id + occurrence time, so the
 * notification can be shown at the exact scheduled moment regardless of when generation finished.
 */
object NotificationStore {

    private const val PREFS = "tellme.briefs"
    private const val KEY_MODEL_URL = "model_url"
    private const val KEY_MODEL_TOKEN = "model_token"

    /**
     * Default on-device model: Qwen2.5-1.5B-Instruct (Apache-2.0, NOT gated) packaged as a
     * MediaPipe `.task` by the LiteRT community. It downloads anonymously (no API key, no billing)
     * and is tiny (~0.5B params) so it loads fast and fits the "super small LLM" goal.
     *
     * Gemma-3-1B-IT would be higher quality but is gated behind the Gemma license on HuggingFace
     * (401 without a free read token). To use it, paste its URL and an optional token in Settings.
     */
    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Unique key for a specific occurrence of a schedule. */
    fun key(scheduleId: String, triggerMillis: Long): String = "brief_${scheduleId}_$triggerMillis"

    fun saveBrief(scheduleId: String, triggerMillis: Long, title: String, body: String) {
        prefs.edit()
            .putString(key(scheduleId, triggerMillis) + "_title", title)
            .putString(key(scheduleId, triggerMillis) + "_body", body)
            .apply()
    }

    /** Save article sources alongside the brief so the detail screen can display them. */
    fun saveSources(scheduleId: String, triggerMillis: Long, sources: List<ArticleSource>) {
        val json = com.google.gson.Gson().toJson(sources)
        prefs.edit()
            .putString(key(scheduleId, triggerMillis) + "_sources", json)
            .apply()
    }

    fun loadSources(scheduleId: String, triggerMillis: Long): List<ArticleSource> {
        val json = prefs.getString(key(scheduleId, triggerMillis) + "_sources", null) ?: return emptyList()
        return runCatching {
            com.google.gson.Gson().fromJson(json, Array<ArticleSource>::class.java).toList()
        }.getOrDefault(emptyList())
    }

    /** Returns a (title, body) pair, or null if no brief is ready yet for this occurrence. */
    fun loadBrief(scheduleId: String, triggerMillis: Long): Pair<String, String>? {
        val base = key(scheduleId, triggerMillis)
        val title = prefs.getString(base + "_title", null)
        val body = prefs.getString(base + "_body", null)
        return if (title != null && body != null) title to body else null
    }

    fun clearBrief(scheduleId: String, triggerMillis: Long) {
        val base = key(scheduleId, triggerMillis)
        prefs.edit().remove(base + "_title").remove(base + "_body").apply()
    }

    /** Delete a brief and its sources from the store. */
    fun deleteBrief(scheduleId: String, triggerMillis: Long) {
        val base = key(scheduleId, triggerMillis)
        prefs.edit()
            .remove(base + "_title")
            .remove(base + "_body")
            .remove(base + "_sources")
            .apply()
    }

    /** Metadata for one stored brief, used by the history screen. */
    data class BriefEntry(
        val scheduleId: String,
        val triggerMillis: Long,
        val title: String,
        val body: String,
    )

    /** Return every stored brief, newest first. */
    fun listAllBriefs(): List<BriefEntry> {
        val all = prefs.all
        return all.keys
            .filter { it.endsWith("_title") }
            .mapNotNull { titleKey ->
                val prefix = titleKey.removeSuffix("_title")
                // prefix = "brief_{scheduleId}_{triggerMillis}"
                if (!prefix.startsWith("brief_")) return@mapNotNull null
                val rest = prefix.removePrefix("brief_")
                val lastUnderscore = rest.lastIndexOf('_')
                if (lastUnderscore < 0) return@mapNotNull null
                val scheduleId = rest.substring(0, lastUnderscore)
                val triggerStr = rest.substring(lastUnderscore + 1)
                val trigger = triggerStr.toLongOrNull() ?: return@mapNotNull null
                val title = prefs.getString(titleKey, null) ?: return@mapNotNull null
                val body = prefs.getString(prefix + "_body", null) ?: return@mapNotNull null
                BriefEntry(scheduleId, trigger, title, body)
            }
            .sortedByDescending { it.triggerMillis }
    }

    /** Returns the configured URL, or the built-in key-free default when none is set. */
    fun getModelUrl(): String = prefs.getString(KEY_MODEL_URL, DEFAULT_MODEL_URL) ?: DEFAULT_MODEL_URL

    fun setModelUrl(url: String) {
        // Empty string resets back to the default key-free model.
        prefs.edit().putString(KEY_MODEL_URL, url.trim()).apply()
    }

    /** Optional HuggingFace token used only for gated models (e.g. Gemma). Blank = anonymous. */
    fun getModelToken(): String = prefs.getString(KEY_MODEL_TOKEN, "") ?: ""

    fun setModelToken(token: String) {
        prefs.edit().putString(KEY_MODEL_TOKEN, token.trim()).apply()
    }
}

/** Metadata for a single article source displayed in the brief detail screen. */
data class ArticleSource(
    val headline: String,
    val url: String,
    val snippet: String,
)
