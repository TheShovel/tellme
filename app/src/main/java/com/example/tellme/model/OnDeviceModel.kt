package com.example.tellme.model

import android.content.Context
import android.util.Log
import com.example.tellme.data.NotificationStore
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wraps the on-device LLM via LiteRT-LM:
 *  - [ensure] verifies (and optionally downloads) the model file in the app's private storage.
 *  - [generate] creates an Engine, runs a single generation pass, and closes it immediately,
 *    so the model is only resident in memory during generation (then unloaded => efficient).
 */
object OnDeviceModel {

    private const val TAG = "TellMe.OnDeviceModel"

    private fun currentUrl(context: Context): String = NotificationStore.getModelUrl()

    fun modelFile(context: Context): File =
        File(context.filesDir, currentUrl(context).substringAfterLast('/'))

    data class ModelState(val ready: Boolean, val message: String, val path: String?)

    suspend fun ensure(context: Context, onProgress: (Int) -> Unit = {}): ModelState =
        withContext(Dispatchers.IO) {
            val file = modelFile(context)
            if (file.exists() && file.length() > 0) {
                return@withContext ModelState(true, "Model ready", file.absolutePath)
            }
            val url = currentUrl(context)
            if (url.isBlank()) {
                return@withContext ModelState(false, "Model file missing. Set a download URL in Settings.", null)
            }
            try {
                download(url, file, NotificationStore.getModelToken(), onProgress)
                ModelState(true, "Model downloaded", file.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "download failed", e)
                ModelState(false, "Download failed: ${e.message}", null)
            }
        }

    private fun download(url: String, out: File, token: String, onProgress: (Int) -> Unit) {
        val effectiveUrl = if (token.isNotBlank()) {
            val sep = if (url.contains('?')) '&' else '?'
            "$url${sep}token=$token"
        } else url
        val conn = (URL(effectiveUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 0
            setRequestProperty("User-Agent", "TellMe/0.1 (Android)")
            connect()
        }
        if (conn.responseCode !in 200..299) {
            val msg = runCatching { conn.inputStream.bufferedReader().readText().take(300) }.getOrDefault("")
            error("HTTP ${conn.responseCode} ${conn.responseMessage} $msg")
        }
        val length = conn.contentLength
        conn.inputStream.use { input ->
            val tmp = File(out.parentFile, out.name + ".part")
            tmp.outputStream().use { output ->
                val buf = ByteArray(8192)
                var total = 0L
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    total += read
                    if (length > 0) onProgress(((total * 100 / length).toInt()).coerceIn(0, 100))
                }
            }
            if (!tmp.renameTo(out)) error("could not finalize download")
        }
    }

    /**
     * Run a single synchronous generation pass using LiteRT-LM Engine.
     * The engine is created and closed within this call, so memory is released right after.
     */
    fun generate(context: Context, modelPath: String, prompt: String, maxTokens: Int = 1024): String {
        Log.i(TAG, "loading model from $modelPath (maxTokens=$maxTokens, promptChars=${prompt.length})")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setMaxTopK(40)
            .build()
        val llm = LlmInference.createFromOptions(context, options)
        Log.i(TAG, "model loaded, generating...")
        val out = llm.generateResponse(prompt)
        Log.i(TAG, "generation complete chars=${out.length}")
        return out
    }

    /**
     * Create a reusable LLM instance. Caller must close() when done.
     * Avoids loading the model multiple times for two-pass generation.
     */
    fun createInstance(context: Context, modelPath: String, maxTokens: Int = 1024): LlmInference {
        Log.i(TAG, "creating reusable model from $modelPath")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setMaxTopK(40)
            .build()
        return LlmInference.createFromOptions(context, options)
    }
}
