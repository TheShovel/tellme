package com.example.tellme.ui

import android.content.Context
import android.util.Log
import com.example.tellme.data.ArticleSource
import com.example.tellme.data.NotificationStore
import com.example.tellme.model.OnDeviceModel

private const val TAG = "TellMe.BriefExpander"

suspend fun expandBrief(context: Context, originalBrief: String, sources: List<ArticleSource>): String {
    val modelState = OnDeviceModel.ensure(context)
    if (!modelState.ready || modelState.path == null) return "Model not available: ${modelState.message}"

    val sb = StringBuilder()
    sb.append(originalBrief).append("\n\n")
    if (sources.isNotEmpty()) {
        sb.append("SOURCE MATERIAL:\n")
        for (s in sources) {
            sb.append(s.headline).append("\n")
            if (s.snippet.isNotBlank()) sb.append(s.snippet).append("\n")
            sb.append("\n")
        }
    }

    val system = "Expand a brief news summary. Given the original short summary and source material, write a detailed expanded version. Include specific details, context, and background. Write 3-5 paragraphs in natural prose. Max 300 words. Do NOT repeat the original summary. Continue from where it left off. Do NOT mention publisher names."

    val promptText = system + "\n\n" + sb.toString()
    val isGemma = NotificationStore.getModelUrl().contains("Gemma", ignoreCase = true)
    val fullPrompt = if (isGemma) {
        "start_of_turn" + "user\n" + promptText + "end_of_turn\nstart_of_turn" + "model\n"
    } else {
        "im_start" + "system\n" + system + "im_end\nim_start" + "user\n" + promptText + "im_end\nim_start" + "assistant\n"
    }
    val llm = OnDeviceModel.createInstance(context, modelState.path, maxTokens = 1024)
    try {
        val response = llm.generateResponse(fullPrompt)
        val cleaned = response.replace("</s", "").replace("<end_of_turn>", "").trim()
        llm.close()
        return cleaned
    } catch (e: Exception) {
        Log.e(TAG, "expand generation failed", e)
        llm.close()
        return "Expansion failed: " + e.message
    }
}
