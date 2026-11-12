package com.lagradost.cloudstream3.linux

import android.content.FileSharedPreferences
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings (including the list of installed extensions) must survive a restart after more than one save.
 * Windows refuses to rename a file onto an existing one, which once lost every save after the first there;
 * the Windows CI runs this test on Windows.
 */
class PersistenceTest {
    @Test
    fun `settings saved again and again are read back after a restart`() {
        val dir = Files.createTempDirectory("prefs").toFile()
        try {
            val file = File(dir, "settings.json")
            val prefs = FileSharedPreferences(file)
            prefs.edit().putString("plugins", "one").apply()
            prefs.edit().putString("plugins", "one,two").apply()
            prefs.edit().putString("plugins", "one,two,three").apply()
            assertEquals("one,two,three", FileSharedPreferences(file).getString("plugins", null))
            assertEquals(false, File(dir, "settings.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `newer settings left in a tmp file by older Windows versions are recovered`() {
        val dir = Files.createTempDirectory("prefs").toFile()
        try {
            val file = File(dir, "settings.json").apply { writeText("""{"plugins":"one"}"""); setLastModified(System.currentTimeMillis() - 60_000) }
            File(dir, "settings.json.tmp").writeText("""{"plugins":"one,two,three"}""")
            assertEquals("one,two,three", FileSharedPreferences(file).getString("plugins", null))
            // A broken leftover is ignored.
            File(dir, "settings.json.tmp").writeText("{not json")
            assertEquals("one", FileSharedPreferences(file).getString("plugins", null))

            val state = File(dir, "downloads.json").apply { writeText("[]"); setLastModified(System.currentTimeMillis() - 60_000) }
            File(dir, "downloads.json.tmp").writeText("""[{"id":"a"}]""")
            assertEquals(File(dir, "downloads.json.tmp"), Os.freshest(state))
        } finally {
            dir.deleteRecursively()
        }
    }
}
