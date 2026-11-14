package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adult content off: ordinary film extensions (reported with MovieBoxIN, searching "sex") return pornographic titles
 * without marking them adult. Those, and a row that is plainly an adult catalogue, are hidden; mainstream titles stay.
 */
class AdultFilterTest {
    private val api = object : MainAPI() { override var name = "MovieBoxIN"; override var mainUrl = "https://example.com" }
    private fun item(name: String, type: TvType = TvType.Movie) = api.newMovieSearchResponse(name, "https://example.com/" + name.hashCode(), type)

    // Titles from the reported search, as the extension returned them.
    private val adultRow = listOf(
        "FAMILYXXX - Stepsister Wants a Ride", "Step-son shares bed with MILF stepmom", "She Teases in Black Stockings Before Going Out",
        "My Boss's Personal Pleasure Toy", "\"Good morning, honey. It's time for…", "Hardcore Dominating Sex Compilation #7",
        "I got naked in front of my stepbrother", "busty 18yo TEEN enjoys pussy fucking", "I Always Wanted to Fuck My Mother in Law",
        "Watery Woman",
    )
    private val mainstream = listOf(
        "Sex and the City", "Sex and the City 2", "Sex Education", "Sexy Beast", "Sex Tape", "Sex Drive", "Sexmission",
        "Sex, Lies, and Videotape", "The Sex Lives of College Girls", "Friends Sex and Games", "Teenage Sex and Death at Camp Miasma",
        "Blood, Sex & Royalty", "I Want Your Sex", "Stepmom", "Step Brothers", "The Naked Gun", "Teen Wolf", "Jane the Virgin",
        "Hardcore Henry", "Striptease", "xXx", "xXx: Return of Xander Cage", "MaXXXine", "The Affair", "Desperate Housewives", "Banana Fish", "Fruits Basket",
    )

    @Test
    fun `plainly pornographic titles are recognised, mainstream ones are not`() {
        for (t in listOf("FAMILYXXX - Stepsister Wants a Ride", "Step-son shares bed with MILF stepmom", "Hentai",
            "She Teases in Black Stockings Before Going Out", "Hardcore Dominating Sex Compilation #7",
            "I got naked in front of my stepbrother", "busty 18yo TEEN enjoys pussy fucking")) assertTrue(AdultTitles.explicit(t), t)
        for (t in mainstream) assertFalse(AdultTitles.explicit(t), t)
        assertTrue(AdultTitles.adultTag("Hentai")); assertFalse(AdultTitles.adultTag("Adult Animation"))
    }

    @Test
    fun `with adult content off, an adult row goes completely and mainstream rows stay`() {
        Platform.init()
        val before = Settings.showNsfw
        try {
            Settings.showNsfw = false
            assertEquals(emptyList(), ContentFilter.filter(adultRow.map(::item)))
            val mixed = mainstream.map(::item) + item("Hentai") + item("Some title", TvType.NSFW)
            assertEquals(mainstream, ContentFilter.filter(mixed).map { it.name })
            // On: everything shows, as before.
            Settings.showNsfw = true
            assertEquals(adultRow.size, ContentFilter.filter(adultRow.map(::item)).size)
        } finally {
            Settings.showNsfw = before
        }
    }
}
