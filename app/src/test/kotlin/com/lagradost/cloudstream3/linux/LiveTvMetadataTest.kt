package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 6.0.1: Live TV (M3U + XMLTV, Xtream, Stremio TV add-ons), watched controls and the metadata hub. */
class LiveTvMetadataTest {
    private val tests = File(System.getProperty("tests.dir"))
    private lateinit var server: Process
    private var port = 0
    private val base get() = "http://localhost:$port"

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
        CloudStreamApp.setKey("demo_base_url", base)
        System.setProperty("cs.subtitles.meta", "$base/stremio/meta")
        System.setProperty("cs.tmdb.base", "$base/tmdb/3")
        System.setProperty("cs.tmdb.images", "$base/tmdbimg/")
        System.setProperty("cs.anilist", "$base/anilist")
        System.setProperty("cs.aniskip", "$base/aniskip")
    }

    @AfterTest
    fun stop() {
        server.destroy()
        LiveTv.saved().forEach { LiveTv.remove(it.id) }
        StremioAddons.all().forEach { StremioAddons.remove(it.url) }
        Metadata.tmdbKey = ""
        Metadata.clearCache()
        PluginManager.loaded.keys.toList().forEach { PluginManager.unloadPlugin(it) }
    }

    @Test
    fun `M3U playlists with headers, a TV guide, favourites and playback data`() = runBlocking {
        val (source, count) = LiveTv.add(LiveTv.Kind.M3U, "$base/iptv/playlist.m3u", "Test IPTV", "$base/iptv/guide.xml")
        assertEquals(5, count)
        val channels = LiveTv.channels(source.id)
        assertEquals(listOf("News 24", "Sport One", "Cartoon Fun", "Movie Max", "Planet Docs"), channels.map { it.name })
        assertEquals(listOf("News", "Sports", "Kids", "Movies", "Documentary"), channels.map { it.group })
        // "url|Header=value" and #EXTVLCOPT referrer both become request headers.
        assertEquals("demo", channels[0].headers["X-CS-Token"])
        assertTrue(channels[0].url.endsWith("/hls/master.m3u8"))
        assertEquals("$base/", channels[3].headers["Referer"])
        assertEquals("GB", channels[0].country)

        LiveTv.loadGuides(force = true)
        val (now, next) = LiveTv.nowNext(channels[0])
        assertNotNull(now); assertNotNull(next)
        assertTrue(now.start <= System.currentTimeMillis() && System.currentTimeMillis() < now.stop)
        assertTrue(now.title.startsWith("News 24 show"))
        // The guide grid: six half-hour programmes, in order, inside the window asked for.
        val t = System.currentTimeMillis()
        val grid = LiveTv.programmes(channels[0], t - 3600_000L, t + 6 * 3600_000L)
        assertEquals(6, grid.size)
        assertEquals(grid.sortedBy { it.start }, grid)
        assertTrue(LiveTv.hasGuide())
        assertEquals(1, LiveTv.programmes(channels[0], now.start, now.stop).size)

        assertTrue(LiveTv.toggleFavourite(channels[2]))
        assertEquals(setOf(channels[2].key), LiveTv.favourites())
        assertFalse(LiveTv.toggleFavourite(channels[2]))
        LiveTv.watched(channels[1]); LiveTv.watched(channels[0])
        assertEquals(listOf(channels[0].key, channels[1].key), LiveTv.recent().take(2))

        // Playing: the live API turns a channel into a link with its headers; the HLS answers through the proxy.
        val response = LiveApi.load(LiveApi.data(channels[0]))!!
        assertEquals(TvType.Live, response.type)
        val links = mutableListOf<ExtractorLink>()
        LiveApi.loadLinks(Engine.playables(response).single().data, false, {}, { links += it })
        val link = links.single()
        assertEquals("demo", link.headers["X-CS-Token"])
        assertEquals(200, app.get(StreamProxy.wrap(link.url, link.headers)).code)

        // Live TV never shows up as a Home/Search source.
        assertTrue(Engine.allProviders().none { it.name == LiveApi.name })
    }

    @Test
    fun `playlist files, web pages and channel logos`() = runBlocking {
        // A playlist file on this computer.
        val file = File.createTempFile("channels", ".m3u").apply {
            writeText("#EXTM3U\n#EXTINF:-1 group-title=\"News\",One\n$base/hls/master.m3u8\n#EXTINF:-1,Two\n$base/hls/master.m3u8\n")
            deleteOnExit()
        }
        val (local, n) = LiveTv.add(LiveTv.Kind.M3U, file.toURI().toString())
        assertEquals(2, n)
        assertEquals(file.nameWithoutExtension, local.name)

        // Web pages aren't playlists; GitHub page links become raw file links.
        val page = runCatching { LiveTv.add(LiveTv.Kind.M3U, "$base/webpage") }.exceptionOrNull()?.message.orEmpty()
        assertTrue("web page" in page, page)
        assertEquals("https://raw.githubusercontent.com/u/r/main/list.m3u", LiveTv.rawGithub("https://github.com/u/r/blob/main/list.m3u"))

        // Logos from the iptv-org database: by tvg-id, then by name; SVGs skipped.
        System.setProperty("cs.logos.base", "$base/logos-api")
        File(AppDirs.data, "cache/livetv/iptv-org-logos.json").delete()
        val news = LiveTv.Channel("k", "s", "News 24 HD (1080p)", null, "News", "u", epgId = "News24.uk@SD")
        assertEquals(listOf("$base/media/poster.png"), ChannelLogos.candidates(news))
        val cartoon = LiveTv.Channel("k2", "s", "Cartoon Fun Kids [Geo-blocked]", "http://broken/logo.png", "Kids", "u")
        assertEquals(listOf("http://broken/logo.png", "$base/media/ep_1.jpg"), ChannelLogos.candidates(cartoon))
        Unit
    }

    @Test
    fun `a channel that doesn't play says why, and the opt-in plain-http retry`() = runBlocking {
        // The reason, from one request to the channel's address.
        val token = mapOf("X-CS-Token" to "demo")
        assertEquals(null, LiveTv.diagnose("$base/hls/master.m3u8", token))
        assertTrue("refused to send it (HTTP 403)" in LiveTv.diagnose("$base/hls/master.m3u8").orEmpty())
        assertTrue("no longer exists (HTTP 404)" in LiveTv.diagnose("$base/no-such-channel.m3u8").orEmpty())
        assertEquals("its server's address no longer exists", LiveTv.diagnose("http://channel.invalid/live.m3u8"))
        val closed = java.net.ServerSocket(0).use { it.localPort }
        assertEquals("its server refuses connections", LiveTv.diagnose("http://127.0.0.1:$closed/live.m3u8"))
        val msg = LiveTv.failureMessage("Cine.AR", "$base/no-such-channel.m3u8")
        assertTrue(msg.startsWith("Cine.AR can't play: its stream address no longer exists"), msg)
        assertTrue("off the air" in LiveTv.failureMessage("Alpo TV", "$base/hls/master.m3u8", token))

        // Retry without encryption: off by default; then a second, http link for https playlist channels only.
        val (source, _) = LiveTv.add(LiveTv.Kind.M3U, "$base/iptv/playlist.m3u", "Test IPTV")
        val secure = LiveTv.Channel("k", source.id, "Cine.AR", null, null, "https://tv.example/live/cinear/playlist.m3u8")
        suspend fun links(c: LiveTv.Channel) = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>().also { out ->
            LiveApi.loadLinks(LiveApi.data(c), false, {}) { out += it }
        }
        assertFalse(LiveTv.retryPlain)
        assertEquals(1, links(secure).size)
        LiveTv.retryPlain = true
        try {
            val both = links(secure)
            assertEquals(listOf("https://tv.example/live/cinear/playlist.m3u8", "http://tv.example/live/cinear/playlist.m3u8"), both.map { it.url })
            assertEquals(LiveApi.PLAIN, both[1].name)
            // Not for addresses that are already http, nor channels from logins (Xtream addresses carry the password).
            assertEquals(1, links(secure.copy(url = "http://tv.example/a.m3u8")).size)
            assertEquals(1, links(secure.copy(source = "xtream-login")).size)
        } finally {
            LiveTv.retryPlain = false
        }
        Unit
    }

    @Test
    fun `Free-TV style playlists - marks become tags, YouTube and Twitch channels open on their sites`() = runBlocking {
        // As in github.com/Free-TV/IPTV: Ⓢ SD, Ⓖ region-locked, Ⓨ YouTube, Ⓣ Twitch.
        val list = LiveTv.parseM3u("free", """
            #EXTM3U x-tvg-url="https://epg.example/a.xml.gz, https://epg.example/b.xml.gz"
            #EXTINF:-1 tvg-name="Kanali 7" tvg-id="Kanali7.al" tvg-country="AL" group-title="Albania",Kanali 7 Ⓢ
            https://fe.example/delta/105/out/u/1200_1.m3u8
            #EXTINF:-1 tvg-name="SBS" tvg-chno="5" group-title="Australia",SBS Ⓖ
            https://i.example/.r/sbs.m3u8
            #EXTINF:-1 tvg-name="ABC News Albania" group-title="Albania",ABC News Albania Ⓣ
            https://www.twitch.tv/abcnewsal
            #EXTINF:-1 tvg-name="Euronews Albania" group-title="Albania",Euronews Albania Ⓨ
            https://www.youtube.com/@EuronewsAlbania/live
            #EXTINF:-1 group-title="France",France 24 Ⓨ Ⓖ
            https://youtu.be/abc
        """.trimIndent())
        assertEquals(listOf("Kanali 7", "SBS", "ABC News Albania", "Euronews Albania", "France 24"), list.map { it.name })
        assertEquals(listOf(false, true, false, false, true), list.map { it.regionLocked })
        assertEquals(listOf(null, null, "Twitch", "YouTube", "YouTube"), list.map { LiveTv.webPage(it) })
        assertEquals(5, list[1].number)

        // They play in the service's own embed player: which one, for each kind of address.
        fun embed(url: String) = LiveTv.webEmbed(LiveTv.Channel("k", "s", "n", null, null, url))
        assertEquals(LiveTv.WebEmbed("Twitch", "twitch", "abcnewsal"), embed("https://www.twitch.tv/abcnewsal"))
        assertEquals(LiveTv.WebEmbed("YouTube", "youtube-video", "ABVQXgr2LW4"), embed("https://www.youtube.com/watch?v=ABVQXgr2LW4"))
        assertEquals(LiveTv.WebEmbed("YouTube", "youtube-video", "ABVQXgr2LW4"), embed("https://youtu.be/ABVQXgr2LW4"))
        assertEquals(LiveTv.WebEmbed("YouTube", "youtube-channel", "UC6YundoLrEuBJaPp_oEPWaA"), embed("https://www.youtube.com/channel/UC6YundoLrEuBJaPp_oEPWaA/live"))
        assertEquals("youtube-page", embed("https://www.youtube.com/@EuronewsAlbania/live")?.kind)
        assertEquals("youtube-page", embed("https://www.youtube.com/c/todonoticias/live")?.kind)
        assertEquals(LiveTv.WebEmbed("Dailymotion", "dailymotion", "x3b68jn"), embed("https://www.dailymotion.com/video/x3b68jn"))
        assertNull(embed("https://cdn.example/live.m3u8"))
        // A channel page's live video, from its canonical link; else its channel id.
        assertEquals("youtube-video", LiveTv.youtubeLiveVideo("\"https://m.youtube.com/@x/live https://www.youtube.com/watch?v=ABVQXgr2LW4\"")?.kind)
        assertEquals("youtube-channel", LiveTv.youtubeLiveVideo("https://www.youtube.com/channel/UC6YundoLrEuBJaPp_oEPWaA")?.kind)
        assertNull(LiveTv.youtubeLiveVideo("https://www.youtube.com/@x/live"))
        // The page: Twitch's parent is the page's host; YouTube gets the page's origin and reports errors.
        val twitch = LiveTv.embedHtml(LiveTv.WebEmbed("Twitch", "twitch", "abcnewsal"), "https://sherazi5110.github.io")!!
        assertTrue("player.twitch.tv/?channel=abcnewsal&parent=sherazi5110.github.io" in twitch, twitch)
        val yt = LiveTv.embedHtml(LiveTv.WebEmbed("YouTube", "youtube-channel", "UC6YundoLrEuBJaPp_oEPWaA"), "http://localhost:4242")!!
        assertTrue("embed/live_stream?channel=UC6YundoLrEuBJaPp_oEPWaA" in yt && "origin=http%3A%2F%2Flocalhost%3A4242" in yt && "StreamGoat.error" in yt, yt)
        // Desktop serves the page itself, at a localhost address.
        val url = StreamProxy.page { origin -> LiveTv.embedHtml(LiveTv.WebEmbed("Twitch", "twitch", "abcnewsal"), origin) }!!
        assertTrue(url.startsWith("http://localhost:"), url)
        val served = java.net.URI(url.replace("localhost", "127.0.0.1")).toURL().readText()
        assertTrue("parent=localhost" in served, served)
        Unit
    }

    @Test
    fun `Xtream logins and Stremio TV add-ons go to Live TV only`() = runBlocking {
        val bad = runCatching { LiveTv.add(LiveTv.Kind.XTREAM, "$base/xtream", null, null, "demo", "wrong") }
        assertTrue(bad.isFailure)
        val (xt, n) = LiveTv.add(LiveTv.Kind.XTREAM, "$base/xtream", "My IPTV", null, "demo", "secret")
        assertEquals(2, n)
        val xc = LiveTv.channels(xt.id)
        assertEquals(listOf("News", "Kids"), xc.map { it.group })
        assertEquals("$base/xtream/live/demo/secret/11.m3u8", xc[0].url)
        LiveTv.loadGuides(force = true)
        assertNotNull(LiveTv.nowNext(xc[0]).first, "Xtream guide comes from xmltv.php")

        // A Stremio add-on with TV catalogs, installed the usual way, appears as a Live TV source…
        StremioAddons.install("$base/addon-tv")
        val tvSource = LiveTv.sources().single { it.kind == LiveTv.Kind.STREMIO }
        val tv = LiveTv.channels(tvSource.id)
        assertEquals(5, tv.size)
        // …and not on Home or in Search.
        assertTrue(Engine.allProviders().none { it is StremioApi })
        // Its channels play through the add-on's streams.
        val addon = StremioAddons.all().single()
        val api = StremioApi(addon)
        val r = Engine.load(api, tv[0].url)!!
        assertEquals(TvType.Live, r.type)
        val links = mutableListOf<ExtractorLink>()
        api.loadLinks(Engine.playables(r).single().data, false, { _: SubtitleFile -> }, { links += it })
        assertEquals("demo", links.single().headers["X-CS-Token"])
    }

    @Test
    fun `mark as watched and the metadata hub`() = runBlocking {
        val demo = PluginManager.loadPlugin(File(System.getProperty("demo.repo"), "Demo.cs3"), PluginData("Demo", null, false, "Demo.cs3", 1))
            .getOrThrow().providers.single()
        // Watched / unwatched / remove from Continue Watching.
        val entry = WatchEntry(demo.name, "$base/title/x", "X", null, "x|1", "S1E1", 300.0, 1000.0, 1)
        Library.record(entry)
        assertEquals(1, Library.continueWatching().count { it.url == entry.url })
        Library.markWatched(entry)
        assertTrue(Library.continueWatching().none { it.url == entry.url })
        assertTrue(Library.isWatched(demo.name, "x|1"))
        assertTrue(Library.lastWatched(demo.name, entry.url)!!.finished)
        Library.markUnwatched(demo.name, entry.url, listOf("x|1"))
        assertFalse(Library.isWatched(demo.name, "x|1"))
        Library.markWatched(demo.name, "$base/title/y", "Y", null, listOf("a" to "E1", "b" to "E2"))
        assertTrue(Library.isWatched(demo.name, "a") && Library.isWatched(demo.name, "b"))
        Library.markUnwatched(demo.name, "$base/title/y", listOf("a", "b"))

        // Titles from release names.
        assertEquals("Avaraachan & Sons" to 2026, Metadata.cleanTitle("Avaraachan & Sons (2026) WEB-DL [Hindi (DD5.1) & Tamil]"))
        assertEquals("Mirzapur: The Movie" to 2026, Metadata.cleanTitle("Mirzapur: The Movie (2026) DS4K EXTENDED 1080p"))

        // TMDB key check.
        assertNotNull(Metadata.testKey("ffffffffffffffffffffffffffffffff"))
        assertNull(Metadata.testKey("0123456789abcdef0123456789abcdef"))
        assertNull(Metadata.testKey("test-read-token"), "v4 read tokens work too")

        // Without a key: IMDb rating through Cinemeta only.
        val film = Engine.load(demo, "$base/title/night_of_the_test_pattern")!!
        val noKey = Metadata.details(film)!!
        assertNull(noKey.tmdbVote)
        assertEquals(8.4, noKey.imdbRating)

        // With a key: ratings, artwork, cast, trailer, similar titles; poster ratings.
        Metadata.tmdbKey = "0123456789abcdef0123456789abcdef"
        val info = Metadata.details(film)!!
        assertEquals(7.9, info.tmdbVote)
        assertEquals("tt9000002", info.imdbId)
        assertEquals("$base/tmdbimg/w1280/backdrop_night_of_the_test_pattern.jpg", info.backdrop)
        assertEquals("$base/tmdbimg/w500/logo.png", info.logo)
        assertEquals("PG", info.contentRating)
        assertEquals(listOf("Ada Scanline", "Vic Raster"), info.cast.map { it.name })
        assertEquals("https://www.youtube.com/watch?v=abc123", info.trailer)
        assertEquals("Metropolis Bars", info.similar.single().title)
        val series = Engine.load(demo, "$base/title/pixel_city")!!
        assertEquals("TV-14", Metadata.details(series)!!.contentRating)
        assertEquals("TMDB 7.9", Metadata.posterRating(demo.newMovieSearchResponse("Night of the Test Pattern (1968) 1080p WEB-DL", "u", TvType.Movie)))
        assertNull(Metadata.posterRating(demo.newMovieSearchResponse("Something Else Entirely", "u2", TvType.Movie)))
        // "Wrong match?": pick another TMDB entry by hand, then "no match", then back to automatic.
        val candidates = Metadata.searchCandidates("Pixel")
        assertEquals(listOf("Pixel City"), candidates.map { it.title })
        Metadata.chooseMatch(film.apiName, film.url, candidates.single())
        assertEquals(601, Metadata.details(film)!!.tmdbId)
        Metadata.chooseMatch(film.apiName, film.url, null)
        assertNull(Metadata.details(film)?.tmdbId)
        Metadata.chooseMatch(film.apiName, film.url, null, automatic = true)
        assertEquals(501, Metadata.details(film)!!.tmdbId)
        // Skip intro: the MyAnimeList id comes from AniList, the times from AniSkip.
        val anime = demo.newAnimeLoadResponse("Pixel Anime", "$base/anime/pixel", TvType.Anime) {}
        val segs = SkipTimes.forEpisode(anime, 1, 1_420_000)
        assertEquals(listOf(SkipTimes.Kind.INTRO, SkipTimes.Kind.CREDITS), segs.map { it.kind })
        assertEquals(5500L to 95_000L, segs[0].start to segs[0].end)
        assertTrue(SkipTimes.forEpisode(film, 1, 1_000_000).isEmpty(), "only anime")
        // AniList for anime when TMDB has nothing.
        Metadata.tmdbKey = ""
        assertEquals("AniList 8.4", Metadata.posterRating(demo.newMovieSearchResponse("Pixel Anime", "u3", TvType.Anime)))
    }
}
