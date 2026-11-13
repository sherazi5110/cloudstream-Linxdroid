package com.lagradost.cloudstream3.ui.player

// The official app's player types (ui/player/IPlayer.kt, PlayerSubtitleHelper.kt), for extensions that control
// the player (WatchParty, Ultima). This app's player is different, so they find no player to control.

enum class CSPlayerEvent(val value: Int) {
    Pause(0), Play(1), SeekForward(2), SeekBack(3), SkipCurrentChapter(4), NextEpisode(5), PrevEpisode(6),
    PlayPauseToggle(7), ToggleMute(8), Restart(9), PlayAsAudio(10),
}

enum class PlayerEventSource { UI, Player, Sync }

enum class SubtitleOrigin { URL, DOWNLOADED_FILE, EMBEDDED_IN_VIDEO }

data class SubtitleData(
    val originalName: String,
    val nameSuffix: String,
    val url: String,
    val origin: SubtitleOrigin,
    val mimeType: String,
    val headers: Map<String, String>,
    val languageCode: String?,
) {
    val name: String get() = "$originalName $nameSuffix".trim()
}

interface IPlayer {
    fun getPlaybackSpeed(): Float
    fun setPlaybackSpeed(speed: Float)
    fun getIsPlaying(): Boolean
    fun getDuration(): Long?
    fun getPosition(): Long?
    fun seekTo(time: Long, source: PlayerEventSource = PlayerEventSource.UI)
    fun setPreferredSubtitles(subtitle: SubtitleData?): Boolean
    fun getCurrentPreferredSubtitle(): SubtitleData?
    fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource = PlayerEventSource.UI)
    fun isActive(): Boolean
}
