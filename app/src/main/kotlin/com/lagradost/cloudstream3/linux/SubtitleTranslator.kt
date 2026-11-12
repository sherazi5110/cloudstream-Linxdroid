package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Translates subtitle files (SRT, WebVTT) into another language with Google's neural translation, keeping every
 * cue's timing. Text goes in batches (a few thousand characters per request); a batch whose lines don't come back
 * one-for-one is redone cue by cue. Results are cached, so watching again is instant.
 */
object SubtitleTranslator {
    /** The most-spoken languages first; codes as Google Translate takes them. */
    val languages: List<Pair<String, String>> = listOf(
        "en" to "English", "hi" to "Hindi", "ur" to "Urdu", "zh-CN" to "Chinese (Simplified)", "es" to "Spanish",
        "fr" to "French", "ar" to "Arabic", "bn" to "Bengali", "pt" to "Portuguese", "ru" to "Russian",
        "id" to "Indonesian", "de" to "German", "ja" to "Japanese", "tr" to "Turkish", "ta" to "Tamil",
        "te" to "Telugu", "pa" to "Punjabi", "ko" to "Korean", "it" to "Italian", "fa" to "Persian",
        "ms" to "Malay", "vi" to "Vietnamese", "th" to "Thai", "zh-TW" to "Chinese (Traditional)",
    )

    fun languageName(code: String) = languages.firstOrNull { it.first == code }?.second ?: code

    private val base get() = System.getProperty("cs.translate.base") ?: "https://translate.googleapis.com"

    data class Cue(val timing: String, val text: String)

    /** Reads SRT or WebVTT cues (other formats: an error that says so). */
    fun parse(content: String): List<Cue> {
        val text = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        if (text.trimStart().startsWith("[Script Info]")) throw IOException("ASS/SSA subtitles can't be translated yet; pick an SRT or VTT one")
        val timing = Regex("""^\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3}\s*-->\s*(\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3}.*$""")
        val cues = mutableListOf<Cue>()
        text.split(Regex("\n\\s*\n")).forEach { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val i = lines.indexOfFirst { timing.matches(it) }
            if (i < 0) return@forEach
            val body = lines.drop(i + 1).joinToString("\n").trim()
            if (body.isNotEmpty()) cues += Cue(lines[i].trim().replace('.', ',').let(::normalizeTiming), body)
        }
        if (cues.isEmpty()) throw IOException("No subtitle lines found in that file")
        return cues
    }

    // "00:01.500 --> 00:04.000" (WebVTT, no hours) becomes "00:00:01,500 --> 00:00:04,000" for SRT.
    private fun normalizeTiming(t: String): String = t.split("-->").joinToString(" --> ") { part ->
        val p = part.trim().substringBefore(' ')
        if (p.count { it == ':' } == 1) "00:$p" else p
    }

    fun toSrt(cues: List<Cue>): String = cues.mapIndexed { i, c -> "${i + 1}\n${c.timing}\n${c.text}\n" }.joinToString("\n")

    /** Translates [input] into [lang]; returns the translated SRT file (from the cache when it exists). */
    suspend fun translateFile(input: File, lang: String, cacheDir: File): File = withContext(Dispatchers.IO) {
        val bytes = input.readBytes()
        val key = MessageDigest.getInstance("SHA-1").digest(bytes + lang.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        val out = File(cacheDir.apply { mkdirs() }, "translated-$key.$lang.srt")
        if (out.isFile && out.length() > 0) return@withContext out
        val cues = parse(String(bytes, Charsets.UTF_8))
        val translated = translateTexts(cues.map { it.text }, lang)
        out.writeText(toSrt(cues.zip(translated) { c, t -> c.copy(text = t) }))
        out
    }

    /** Each text translated; line breaks inside a cue are kept. */
    suspend fun translateTexts(texts: List<String>, lang: String): List<String> {
        val out = ArrayList<String>(texts.size)
        var i = 0
        while (i < texts.size) {
            var end = i
            var chars = 0
            while (end < texts.size && (end == i || chars + texts[end].length < 3500)) { chars += texts[end].length + 1; end++ }
            val batch = texts.subList(i, end)
            // One line per cue: breaks inside a cue become " ␤ " and come back afterwards.
            val joined = batch.joinToString("\n") { it.replace("\n", " ␤ ") }
            val lines = request(joined, lang).trimEnd('\n').split("\n")
            if (lines.size == batch.size) out += lines.map(::restore)
            else batch.forEach { out += restore(request(it.replace("\n", " ␤ "), lang).trim()) }
            i = end
        }
        return out
    }

    private fun restore(s: String) = s.replace(Regex("\\s*␤\\s*"), "\n").trim()

    private fun request(text: String, lang: String): String {
        val body = FormBody.Builder().add("q", text).build()
        val req = Request.Builder().url("$base/translate_a/single?client=gtx&sl=auto&tl=$lang&dt=t").post(body)
            .header("User-Agent", USER_AGENT).build()
        app.baseClient.newCall(req).execute().use { r ->
            // Too many requests from one connection: Google answers 429 or sends to its "sorry" page.
            if (r.code == 429 || r.request.url.encodedPath.startsWith("/sorry"))
                throw IOException("Google Translate is limiting this connection; try again later (a VPN or another network also helps)")
            if (!r.isSuccessful) throw IOException("Translation failed (HTTP ${r.code})")
            val parts = JSONArray(r.body.string()).optJSONArray(0) ?: throw IOException("Unexpected answer from the translation service")
            return (0 until parts.length()).joinToString("") { parts.optJSONArray(it)?.optString(0).orEmpty() }
        }
    }
}
