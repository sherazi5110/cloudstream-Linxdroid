package com.lagradost.cloudstream3.linux

import android.content.res.AssetManager
import android.content.res.Resources
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Extensions read files they ship under assets/ through resources.assets, as on Android. */
class AssetsTest {
    private fun extension(dir: File, name: String, files: Map<String, String>) = File(dir, name).apply {
        ZipOutputStream(outputStream()).use { z ->
            z.putNextEntry(ZipEntry("classes.dex")); z.closeEntry()
            files.forEach { (path, text) -> z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry() }
        }
    }

    @Test
    fun `an extension's assets open through its resources and the shared context`() {
        val dir = Files.createTempDirectory("assets").toFile()
        try {
            val cs3 = extension(dir, "Vision.cs3", mapOf("assets/keys.json" to """{"k":1}""", "assets/js/decode.js" to "x()"))
            val own = Resources(AssetManager(cs3)).getAssets()
            assertEquals("""{"k":1}""", own.open("keys.json").bufferedReader().readText())
            assertEquals("x()", own.open("js/decode.js").bufferedReader().readText())
            assertEquals(setOf("keys.json", "js"), own.list("").toSet())
            assertFailsWith<java.io.FileNotFoundException> { own.open("missing.txt") }

            // context.resources.assets: every installed extension.
            AssetManager.register(cs3)
            assertEquals("x()", Resources.EMPTY.getAssets().open("js/decode.js").bufferedReader().readText())
            assertTrue("keys.json" in Platform.context.getResources().getAssets().list(""))
            AssetManager.unregister(cs3)
            assertFailsWith<java.io.FileNotFoundException> { Resources.EMPTY.getAssets().open("keys.json") }
        } finally {
            dir.deleteRecursively()
        }
    }
}
