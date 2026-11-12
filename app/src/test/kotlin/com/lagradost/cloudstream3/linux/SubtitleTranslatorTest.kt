package com.lagradost.cloudstream3.linux

import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Subtitle translation: timings kept, lines inside a cue kept, results cached. */
class SubtitleTranslatorTest {
    private val tests = File(System.getProperty("tests.dir"))
    private lateinit var server: Process
    private var port = 0

    @BeforeTest
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = ProcessBuilder(System.getProperty("test.python", "python3"), "-I", tests.resolve("mock-archive/server.py").path, port.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        repeat(50) {
            if (runCatching { URI("http://127.0.0.1:$port/advancedsearch.php").toURL().readText() }.isSuccess) return@repeat
            Thread.sleep(100)
        }
        Platform.init()
        Network.init()
        System.setProperty("cs.translate.base", "http://localhost:$port")
    }

    @AfterTest
    fun stop() {
        server.destroy()
        System.clearProperty("cs.translate.base")
    }

    @Test
    fun `srt and vtt are read with their timings`() {
        val srt = SubtitleTranslator.parse("﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\nthere\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nBye\r\n")
        assertEquals(listOf("00:00:01,000 --> 00:00:02,500", "00:00:03,000 --> 00:00:04,000"), srt.map { it.timing })
        assertEquals("Hello\nthere", srt[0].text)
        val vtt = SubtitleTranslator.parse("WEBVTT\n\nNOTE made by hand\n\ncue-1\n00:01.500 --> 00:04.000 align:start\n<i>Hi</i>\n")
        assertEquals(1, vtt.size)
        assertEquals("00:00:01,500 --> 00:00:04,000", vtt[0].timing)
    }

    @Test
    fun `a file is translated cue by cue and cached`() = runBlocking {
        val dir = Files.createTempDirectory("translate").toFile()
        val input = File(dir, "in.srt")
        // Enough cues for several batches.
        input.writeText((1..400).joinToString("\n") { i -> "$i\n00:00:${"%02d".format(i % 60)},000 --> 00:00:${"%02d".format(i % 60)},900\nline $i\nsecond $i\n" })
        val out = SubtitleTranslator.translateFile(input, "ur", File(dir, "cache"))
        val cues = SubtitleTranslator.parse(out.readText())
        assertEquals(400, cues.size)
        assertEquals("ur: LINE 1\nSECOND 1", cues[0].text)
        assertEquals("00:00:01,000 --> 00:00:01,900", cues[0].timing)
        assertEquals("ur: LINE 400\nSECOND 400", cues[399].text)
        // Cached: the same file comes back without asking the service (now gone).
        server.destroy(); server.waitFor()
        assertEquals(out, SubtitleTranslator.translateFile(input, "ur", File(dir, "cache")))
        assertTrue(SubtitleTranslator.languages.map { it.first }.containsAll(listOf("hi", "ur", "zh-CN", "en", "fr", "ar", "es")))
        dir.deleteRecursively()
        Unit
    }
}
