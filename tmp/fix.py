#!/usr/bin/env python3
# Build BriefExpander.kt content using chr() to avoid XML parsing issues

NL = chr(10)
BS = chr(92)
DQ = chr(34)

def q(s):
    """Wrap in double quotes"""
    return DQ + s + DQ

lines = []
lines.append("package com.example.tellme.ui")
lines.append("")
lines.append("import android.content.Context")
lines.append("import android.util.Log")
lines.append("import com.example.tellme.data.ArticleSource")
lines.append("import com.example.tellme.data.NotificationStore")
lines.append("import com.example.tellme.model.OnDeviceModel")
lines.append("")
lines.append("private const val TAG = " + q("TellMe.BriefExpander"))
lines.append("")
lines.append("suspend fun expandBrief(context: Context, originalBrief: String, sources: List" + chr(60) + "ArticleSource" + chr(62) + "): String {")
lines.append("    val modelState = OnDeviceModel.ensure(context)")
lines.append("    if (!modelState.ready || modelState.path == null) return " + q("Model not available: ${modelState.message}"))
lines.append("")
lines.append("    val sb = StringBuilder()")
lines.append("    sb.append(originalBrief).append(" + q(BS + "n" + BS + "n") + ")")
lines.append("    if (sources.isNotEmpty()) {")
lines.append("        sb.append(" + q("SOURCE MATERIAL:" + BS + "n") + ")")
lines.append("        for (s in sources) {")
lines.append("            sb.append(s.headline).append(" + q(BS + "n") + ")")
lines.append("            if (s.snippet.isNotBlank()) sb.append(s.snippet).append(" + q(BS + "n") + ")")
lines.append("            sb.append(" + q(BS + "n") + ")")
lines.append("        }")
lines.append("    }")
lines.append("")
lines.append("    val system = " + q("Expand a brief news summary. Given the original short summary and source material, write a detailed expanded version. Include specific details, context, and background. Write 3-5 paragraphs in natural prose. Max 300 words. Do NOT repeat the original summary. Continue from where it left off. Do NOT mention publisher names."))
lines.append("")
lines.append("    val promptText = system + " + q(BS + "n" + BS + "n") + " + sb.toString()")
lines.append("    val isGemma = NotificationStore.getModelUrl().contains(" + q("Gemma") + ", ignoreCase = true)")
lines.append("    val fullPrompt = if (isGemma) {")
# Build Gemma template using concat to avoid angle brackets being parsed as XML
sot = "start_of_turn"
eot = "end_of_turn"
gt = "model"
lines.append("        " + q(sot) + " + " + q("user" + BS + "n") + " + promptText + " + q(eot + BS + "n" + sot) + " + " + q(gt + BS + "n"))
lines.append("    } else {")
imo = "im_start"
imc = "im_end"
lines.append("        " + q(imo) + " + " + q("system" + BS + "n") + " + system + " + q(imc + BS + "n" + imo) + " + " + q("user" + BS + "n") + " + promptText + " + q(imc + BS + "n" + imo) + " + " + q("assistant" + BS + "n"))
lines.append("    }")
lines.append("    val llm = OnDeviceModel.createInstance(context, modelState.path, maxTokens = 1024)")
lines.append("    try {")
lines.append("        val response = llm.generateResponse(fullPrompt)")
# Build cleanup replacements using chr() for angle bracket chars
lt = chr(60)
gt2 = chr(62)
lines.append("        val cleaned = response.replace(" + q(lt + "/s") + ", " + q("") + ").replace(" + q(lt + "end_of_turn" + gt2) + ", " + q("") + ").trim()")
lines.append("        llm.close()")
lines.append("        return cleaned")
lines.append("    } catch (e: Exception) {")
lines.append("        Log.e(TAG, " + q("expand generation failed") + ", e)")
lines.append("        llm.close()")
lines.append("        return " + q("Expansion failed: ") + " + e.message")
lines.append("    }")
lines.append("}")
lines.append("")

content = NL.join(lines)

path = "app/src/main/java/com/example/tellme/ui/BriefExpander.kt"
with open(path, "w") as f:
    f.write(content)
print(f"Wrote {len(content)} bytes to {path}")
