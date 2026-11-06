package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Backup and restore of every settings store; extension language / media type filters. */
class BackupTest {
    @Test
    fun `a backup restores settings of every kind`() {
        Platform.init()
        val p = Platform.context.getSharedPreferences("backup_test", 0)
        p.edit().clear().putString("s", "text").putBoolean("b", true).putInt("i", 7).putLong("l", 1L shl 40)
            .putFloat("f", 1.5f).putStringSet("set", setOf("a", "b")).commit()
        val json = Backup.export()
        p.edit().clear().putString("other", "x").commit()
        assertTrue(Backup.import(json) > 0)
        assertEquals("text", p.getString("s", null))
        assertTrue(p.getBoolean("b", false))
        assertEquals(7, p.getInt("i", 0))
        assertEquals(1L shl 40, p.getLong("l", 0))
        assertEquals(1.5f, p.getFloat("f", 0f))
        assertEquals(setOf("a", "b"), p.getStringSet("set", null))
        assertEquals(null, p.getString("other", null), "a restored store replaces what was there")
        assertFailsWith<IllegalArgumentException> { Backup.import("""{"format":"something else","stores":{}}""") }
        p.edit().clear().commit()
    }

    @Test
    fun `extension languages and media types hide other extensions`() {
        Platform.init()
        val hindi = object : MainAPI() { override var name = "HindiTest"; override var lang = "hi"; override val supportedTypes = setOf(TvType.Movie) }
        val anime = object : MainAPI() { override var name = "AnimeTest"; override var lang = "en"; override val supportedTypes = setOf(TvType.Anime) }
        try {
            assertTrue(ContentFilter.allows(hindi) && ContentFilter.allows(anime))
            Settings.extensionLanguages = setOf("en")
            assertFalse(ContentFilter.allows(hindi)); assertTrue(ContentFilter.allows(anime))
            Settings.extensionLanguages = emptySet()
            Settings.mediaTypes = setOf("Movie")
            assertTrue(ContentFilter.allows(hindi)); assertFalse(ContentFilter.allows(anime))
        } finally {
            Settings.extensionLanguages = emptySet(); Settings.mediaTypes = emptySet()
        }
    }
}
