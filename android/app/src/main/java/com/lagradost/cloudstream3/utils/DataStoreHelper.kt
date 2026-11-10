package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import io.github.sherazi5110.cloudstream.Profiles

/**
 * The official app's DataStoreHelper, as far as extensions use it (Ultima, WatchParty): its "accounts" are this
 * app's profiles, and resume-watching comes from this app's library (see ui.home.HomeViewModel).
 */
object DataStoreHelper {
    data class Account(
        @JsonProperty("keyIndex") val keyIndex: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("customImage") val customImage: String? = null,
        @JsonProperty("defaultImageIndex") val defaultImageIndex: Int,
        @JsonProperty("lockPin") val lockPin: String? = null,
    )

    data class PosDur(
        @JsonProperty("position") val position: Long,
        @JsonProperty("duration") val duration: Long,
    )

    data class ResumeWatchingResult(
        @JsonProperty("name") override val name: String,
        @JsonProperty("url") override val url: String,
        @JsonProperty("apiName") override val apiName: String,
        @JsonProperty("type") override var type: TvType? = null,
        @JsonProperty("posterUrl") override var posterUrl: String?,
        @JsonProperty("watchPos") val watchPos: PosDur?,
        @JsonProperty("id") override var id: Int?,
        @JsonProperty("parentId") val parentId: Int?,
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("season") val season: Int?,
        @JsonProperty("isFromDownload") val isFromDownload: Boolean,
        @JsonProperty("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("score") override var score: Score? = null,
    ) : SearchResponse

    var accounts: Array<Account>
        get() = Profiles.all().mapIndexed { i, p -> Account(i, p.name, null, i, p.pin) }.toTypedArray()
        set(_) { /* Profiles are managed in this app's own screens. */ }

    var selectedKeyIndex: Int
        get() = Profiles.all().indexOfFirst { it.id == Profiles.current.id }.coerceAtLeast(0)
        set(value) { Profiles.all().getOrNull(value)?.let { Profiles.switchTo(it.id) } }

    val currentAccount: String get() = selectedKeyIndex.toString()
}
