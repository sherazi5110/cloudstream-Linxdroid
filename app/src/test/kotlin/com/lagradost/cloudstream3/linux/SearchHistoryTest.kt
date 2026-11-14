package com.lagradost.cloudstream3.linux

import kotlin.test.Test
import kotlin.test.assertEquals

/** Recent searches: newest first, no repeats (any letter case), at most 20, removable, per profile. */
class SearchHistoryTest {
    @Test
    fun `recent searches keep the newest first without repeats`() {
        Platform.init()
        SearchHistory.clear()
        listOf("Frieren", "Heart of the Beast", "frieren ", "  ", "Student of the Year").forEach(SearchHistory::add)
        assertEquals(listOf("Student of the Year", "frieren", "Heart of the Beast"), SearchHistory.all())
        SearchHistory.remove("frieren")
        assertEquals(listOf("Student of the Year", "Heart of the Beast"), SearchHistory.all())
        (1..30).forEach { SearchHistory.add("q$it") }
        assertEquals(20, SearchHistory.all().size)
        assertEquals("q30", SearchHistory.all().first())
        SearchHistory.clear()
        assertEquals(emptyList(), SearchHistory.all())
    }
}
