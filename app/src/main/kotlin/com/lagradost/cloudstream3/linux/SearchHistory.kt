package com.lagradost.cloudstream3.linux

import org.json.JSONArray

/** Recent searches, per profile (newest first), to search again with one click. Same as the Android app's. */
object SearchHistory {
    private const val MAX = 20
    private val prefs get() = Platform.context.getSharedPreferences(Profiles.file("search_history"), 0)

    fun all(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun save(list: List<String>) {
        prefs.edit().putString("items", JSONArray(list.take(MAX)).toString()).apply()
    }

    fun add(query: String) {
        if (query.isBlank()) return
        save(merge(all(), query))
    }

    /** [query] first, without an older copy of it (any letter case), at most [MAX]. */
    fun merge(existing: List<String>, query: String): List<String> {
        val q = query.trim()
        return (listOf(q) + existing.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
    }

    fun remove(query: String) = save(all().filterNot { it == query })

    fun clear() = save(emptyList())
}
