package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The web app's server (`cloudstream-linux serve`): the same engine as the desktop app, without a window,
 * for the phone web app in `resources/web`. It serves the app's pages, a JSON API (home, search, title pages,
 * sources, progress, library, Live TV, profiles), and the stream / image / subtitle proxies, all behind one
 * password. Meant to sit behind an HTTPS reverse proxy (Caddy, see scripts/install-server.sh).
 */
object WebServer {
    private val sessions get() = Platform.context.getSharedPreferences("web_sessions", 0)
    private val config get() = Platform.context.getSharedPreferences("web_server", 0)
    private val failures = AtomicInteger(0)

    // ---------- password ----------

    val hasPassword get() = config.getString("hash", null) != null

    fun setPassword(password: String) {
        require(password.length >= 6) { "Use at least 6 characters" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        config.edit().putString("salt", Base64.getEncoder().encodeToString(salt)).putString("hash", hash(password, salt)).commit()
        sessions.edit().clear().commit() // a new password signs every device out
    }

    private fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    fun checkPassword(password: String): Boolean {
        val salt = config.getString("salt", null)?.let { Base64.getDecoder().decode(it) } ?: return false
        val expected = config.getString("hash", null) ?: return false
        return MessageDigest.isEqual(hash(password, salt).toByteArray(), expected.toByteArray())
    }

    private fun newSession(): String {
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sessions.edit().putLong(token, System.currentTimeMillis()).commit()
        return token
    }

    private fun session(ex: HttpExchange): String? = ex.requestHeaders["Cookie"].orEmpty()
        .flatMap { it.split(';') }.map { it.trim() }
        .firstOrNull { it.startsWith("cs_session=") }?.substringAfter('=')
        ?.takeIf { it.length == 64 && sessions.contains(it) }

    // ---------- server ----------

    fun start(host: String, port: Int): HttpServer {
        check(hasPassword) { "Set a password first: cloudstream-linux web-password" }
        val server = HttpServer.create(InetSocketAddress(host, port), 128)
        server.createContext("/") { ex -> safely(ex) { route(ex) } }
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "web").apply { isDaemon = true } }
        server.start()
        return server
    }

    private fun safely(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            System.err.println("W/web: ${ex.requestMethod} ${ex.requestURI.path}: $t")
            runCatching { json(ex, 500, JSONObject().put("error", t.message ?: t.toString())) }
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        when {
            path == "/api/login" -> return login(ex)
            path == "/api/logout" -> { session(ex)?.let { sessions.edit().remove(it).commit() }; return json(ex, 200, JSONObject().put("ok", true)) }
            path.startsWith("/api/") || path.startsWith("/s/") || path == "/img" || path == "/sub" ->
                if (session(ex) == null) return json(ex, 401, JSONObject().put("error", "login"))
            else -> return static(ex, path)
        }
        val q = query(ex)
        when (path) {
            "/api/me" -> me(ex)
            "/api/profile" -> switchProfile(ex)
            "/api/providers" -> providers(ex)
            "/api/home" -> home(ex, q)
            "/api/search" -> search(ex, q)
            "/api/load" -> load(ex, q)
            "/api/links" -> links(ex, q)
            "/api/progress" -> progress(ex)
            "/api/library" -> library(ex)
            "/api/bookmark" -> bookmark(ex)
            "/api/watched" -> watched(ex)
            "/api/live" -> live(ex)
            "/api/live/play" -> livePlay(ex, q)
            "/img" -> image(ex, q)
            "/sub" -> subtitle(ex, q)
            else -> if (path.startsWith("/s/")) StreamProxy.handle(ex, local = false) else json(ex, 404, JSONObject().put("error", "not found"))
        }
    }

    // ---------- plumbing ----------

    private fun query(ex: HttpExchange): Map<String, String> = ex.requestURI.rawQuery.orEmpty().split('&')
        .filter { it.contains('=') }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun body(ex: HttpExchange): JSONObject =
        runCatching { JSONObject(ex.requestBody.readBytes().decodeToString().ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun json(ex: HttpExchange, code: Int, value: Any) {
        val bytes = value.toString().toByteArray()
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private val types = mapOf(
        "html" to "text/html; charset=utf-8", "js" to "text/javascript; charset=utf-8", "css" to "text/css; charset=utf-8",
        "png" to "image/png", "svg" to "image/svg+xml", "webmanifest" to "application/manifest+json", "json" to "application/json",
    )

    private fun static(ex: HttpExchange, path: String) {
        val name = path.trimStart('/').ifBlank { "index.html" }.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) } ?: "index.html"
        val bytes = WebServer::class.java.getResourceAsStream("/web/$name")?.use { it.readBytes() }
            ?: WebServer::class.java.getResourceAsStream("/web/index.html")!!.use { it.readBytes() }
        val ext = if (WebServer::class.java.getResource("/web/$name") != null) name.substringAfterLast('.') else "html"
        ex.responseHeaders.set("Content-Type", types[ext] ?: "application/octet-stream")
        ex.responseHeaders.set("Cache-Control", if (ext == "html" || ext == "js" || ext == "css") "no-cache" else "max-age=86400")
        ex.responseHeaders.set("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.set("Referrer-Policy", "no-referrer")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun headersParam(h: Map<String, String>?) =
        h?.takeIf { it.isNotEmpty() }?.let { Base64.getUrlEncoder().encodeToString(JSONObject(it).toString().toByteArray()) }
    private fun decodeHeaders(s: String?): Map<String, String> = s?.let {
        val o = JSONObject(String(Base64.getUrlDecoder().decode(it)))
        o.keys().asSequence().associateWith { k -> o.getString(k) }
    }.orEmpty()

    /** Posters go through the server: no mixed content, and the extension's headers are sent. */
    private fun img(url: String?, headers: Map<String, String>? = null): String? =
        url?.takeIf { it.startsWith("http") }?.let { "/img?u=${enc(it)}" + (headersParam(headers)?.let { h -> "&h=$h" } ?: "") }

    // ---------- login and profiles ----------

    private fun login(ex: HttpExchange) {
        val password = body(ex).optString("password")
        // Slows guessing down: each wrong try waits longer (up to 10 s), for everyone.
        Thread.sleep((failures.get() * 500L).coerceAtMost(10_000))
        if (!checkPassword(password)) {
            failures.incrementAndGet()
            return json(ex, 403, JSONObject().put("error", "Wrong password"))
        }
        failures.set(0)
        val secure = ex.requestHeaders.getFirst("X-Forwarded-Proto") == "https"
        ex.responseHeaders.add("Set-Cookie", "cs_session=${newSession()}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax" + if (secure) "; Secure" else "")
        json(ex, 200, JSONObject().put("ok", true))
    }

    private fun profileJson(p: Profile) = JSONObject().put("id", p.id).put("name", p.name).put("avatar", p.avatar)
        .put("kids", p.kids).put("locked", Profiles.unlockFor(p) != null && p.id != Profiles.current.id)

    private fun me(ex: HttpExchange) {
        val current = Profiles.current
        json(ex, 200, JSONObject()
            .put("profile", profileJson(current))
            .put("profiles", JSONArray(Profiles.all().map(::profileJson)))
            .put("accent", runCatching { com.lagradost.cloudstream3.linux.ui.Theme.accent }.getOrNull() ?: "#e8194f")
            .put("live", LiveTv.availableToProfile && LiveTv.sources().any { it.enabled })
            .put("version", com.lagradost.cloudstream3.linux.ui.AppInfo.VERSION))
    }

    private fun switchProfile(ex: HttpExchange) {
        val b = body(ex)
        val target = Profiles.all().firstOrNull { it.id == b.optString("id") } ?: return json(ex, 404, JSONObject().put("error", "No such profile"))
        val unlock = Profiles.unlockFor(target)
        if (unlock != null && !unlock(b.optString("pin"))) return json(ex, 403, JSONObject().put("error", "Wrong PIN"))
        Profiles.switchTo(target.id)
        json(ex, 200, JSONObject().put("ok", true))
    }

    // ---------- browsing ----------

    private fun api(name: String?): MainAPI? = name?.let { n -> Engine.providers().firstOrNull { it.name == n } }

    private fun item(api: MainAPI, s: SearchResponse) = JSONObject().put("api", api.name).put("url", s.url).put("name", s.name)
        .put("poster", img(s.posterUrl, s.posterHeaders)).put("type", s.type?.name ?: "")

    private fun providers(ex: HttpExchange) {
        json(ex, 200, JSONArray(Engine.providers().filter { it.hasMainPage || true }.map { a ->
            JSONObject().put("name", a.name).put("lang", (a.lang as String?) ?: "").put("home", a.hasMainPage && a.mainPage.isNotEmpty())
                .put("types", JSONArray(a.supportedTypes.map { it.name }))
        }))
    }

    private fun home(ex: HttpExchange, q: Map<String, String>) {
        val api = api(q["provider"]) ?: Engine.providers().firstOrNull { it.hasMainPage && it.mainPage.isNotEmpty() }
            ?: return json(ex, 200, JSONObject().put("provider", JSONObject.NULL).put("sections", JSONArray()))
        val rows = runBlocking { withTimeoutOrNull(60_000) { Engine.home(listOf(api)).toList() }.orEmpty() }.sortedBy { it.order }
        json(ex, 200, JSONObject().put("provider", api.name).put("sections", JSONArray(rows.map { r ->
            JSONObject().put("name", r.list.name).put("horizontal", r.list.isHorizontalImages)
                .put("items", JSONArray(r.list.list.take(40).map { item(api, it) }))
        })))
    }

    private fun search(ex: HttpExchange, q: Map<String, String>) {
        val query = q["q"].orEmpty().trim()
        val apis = q["provider"]?.let { listOfNotNull(api(it)) } ?: Engine.providers()
        if (query.length < 2) return json(ex, 200, JSONArray())
        val batches = runBlocking { Engine.search(query, apis).toList() }
        json(ex, 200, JSONArray(batches.filter { it.results.isNotEmpty() }.map { b ->
            JSONObject().put("provider", b.api.name).put("items", JSONArray(b.results.take(40).map { item(b.api, it) }))
        }))
    }

    private fun load(ex: HttpExchange, q: Map<String, String>) {
        val api = api(q["api"]) ?: return json(ex, 404, JSONObject().put("error", "That extension isn't installed"))
        val url = q["url"].orEmpty()
        val r = runBlocking { Engine.load(api, url) } ?: return json(ex, 404, JSONObject().put("error", "${api.name} returned nothing"))
        if (!ContentFilter.allows(r)) return json(ex, 403, JSONObject().put("error", "Not available in this profile"))
        val playables = Engine.playables(r)
        val last = Library.lastWatched(api.name, url)
        val target = com.lagradost.cloudstream3.linux.ui.playTarget(api.name, url, playables)
        json(ex, 200, JSONObject()
            .put("api", api.name).put("url", url).put("name", r.name).put("type", r.type.name)
            .put("poster", img(r.posterUrl, r.posterHeaders)).put("backdrop", img(r.backgroundPosterUrl ?: r.posterUrl, r.posterHeaders))
            .put("plot", r.plot ?: "").put("year", r.year ?: JSONObject.NULL).put("tags", JSONArray(r.tags.orEmpty().take(6)))
            .put("rating", r.score?.toDouble(10)?.takeIf { it > 0 } ?: JSONObject.NULL).put("contentRating", r.contentRating ?: "")
            .put("duration", r.duration ?: JSONObject.NULL)
            .put("series", r is TvSeriesLoadResponse || r is AnimeLoadResponse || playables.size > 1)
            .put("bookmarked", Library.isBookmarked(api.name, url))
            .put("resume", target?.let { t -> JSONObject().put("data", t.data).put("label", t.label)
                .put("position", if (last != null && last.episodeData == t.data && !last.finished) last.position else 0.0) } ?: JSONObject.NULL)
            .put("episodes", JSONArray(playables.map { p ->
                val pr = Library.progress(api.name, p.data)
                JSONObject().put("data", p.data).put("label", p.label).put("name", p.name ?: "").put("season", p.season ?: JSONObject.NULL)
                    .put("episode", p.episode ?: JSONObject.NULL).put("dub", p.dub ?: "").put("description", p.description ?: "")
                    .put("poster", img(p.posterUrl, r.posterHeaders))
                    .put("progress", pr?.takeIf { it.duration > 0 }?.let { it.position / it.duration } ?: 0.0)
                    .put("position", pr?.position ?: 0.0)
            }))
            .put("recommendations", JSONArray(r.recommendations.orEmpty().filter(ContentFilter::allows).take(20).map { item(api, it) })))
    }

    /** Sources the phone can play: HLS and direct files, through the proxy when they need headers or plain http. */
    private fun links(ex: HttpExchange, q: Map<String, String>) {
        val api = api(q["api"]) ?: return json(ex, 404, JSONObject().put("error", "That extension isn't installed"))
        val data = q["data"].orEmpty()
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        var error: String? = null
        runBlocking {
            Engine.links(api, data).collect { e ->
                when (e) {
                    is LinkEvent.Link -> if (e.link.source != OfflineApi.SOURCE) links += e.link
                    is LinkEvent.Subtitle -> subs += e.subtitle
                    is LinkEvent.Done -> error = e.error?.message
                }
            }
        }
        val playable = Engine.rank(links.distinctBy { it.url }).filter { it.type == ExtractorLinkType.VIDEO || it.type == ExtractorLinkType.M3U8 }
        json(ex, 200, JSONObject()
            .put("links", JSONArray(playable.map { l ->
                val url = (l as? ExtractorLinkPlayList)?.playlist?.firstOrNull()?.url ?: l.url
                val quality = com.lagradost.cloudstream3.utils.Qualities.getStringByInt(l.quality)
                JSONObject().put("name", if (quality.isBlank() || l.name.contains(quality, true)) l.name else "${l.name} · $quality")
                    .put("url", playUrl(url, l.getAllHeaders()))
                    .put("hls", l.type == ExtractorLinkType.M3U8 || url.substringBefore('?').endsWith(".m3u8"))
                    .put("mkv", url.substringBefore('?').lowercase().endsWith(".mkv"))
            }))
            .put("subtitles", JSONArray(subs.distinctBy { it.url }.map { s ->
                // Subtitle hosts often want the same Referer as the video.
                val h = s.headers?.takeIf { it.isNotEmpty() } ?: playable.firstOrNull()?.getAllHeaders()
                JSONObject().put("lang", s.lang).put("url", "/sub?u=${enc(s.url)}" + (headersParam(h)?.let { "&h=$it" } ?: ""))
            }))
            .put("error", if (playable.isEmpty()) (error ?: if (links.isEmpty()) "No sources found" else "No source this phone can play (only DASH or torrents)") else JSONObject.NULL))
    }

    private fun playUrl(url: String, headers: Map<String, String>): String {
        val needed = headers.filterKeys { !it.equals("User-Agent", true) }
        return if (url.startsWith("https://") && needed.isEmpty()) url
        else StreamProxy.register(url, headers + if (headers.keys.none { it.equals("User-Agent", true) }) mapOf("User-Agent" to USER_AGENT) else emptyMap())
    }

    // ---------- library ----------

    private fun progress(ex: HttpExchange) {
        val b = body(ex)
        val pos = b.optDouble("position", 0.0)
        val dur = b.optDouble("duration", 0.0)
        if (dur <= 0 || b.optString("api").isBlank()) return json(ex, 400, JSONObject().put("error", "bad progress"))
        Library.record(WatchEntry(b.getString("api"), b.getString("url"), b.optString("name"), b.optString("poster").ifBlank { null }?.let(::originalPoster),
            b.getString("data"), b.optString("label").ifBlank { null }, pos, dur, System.currentTimeMillis()))
        json(ex, 200, JSONObject().put("ok", true))
    }

    /** The web app sends back the /img address; the library keeps the real one. */
    private fun originalPoster(p: String): String = if (p.startsWith("/img?u=")) URLDecoder.decode(p.removePrefix("/img?u=").substringBefore('&'), "UTF-8") else p

    private fun entry(e: WatchEntry) = JSONObject().put("api", e.apiName).put("url", e.url).put("name", e.name).put("poster", img(e.posterUrl))
        .put("data", e.episodeData).put("label", e.episodeLabel ?: "").put("position", e.position).put("duration", e.duration).put("progress", e.progress)
        .put("updated", e.updatedAt)

    private fun library(ex: HttpExchange) {
        json(ex, 200, JSONObject()
            .put("continue", JSONArray(Library.continueWatching().take(30).map(::entry)))
            .put("history", JSONArray(Library.history().take(100).map(::entry)))
            .put("bookmarks", JSONArray(Library.bookmarks().map { b ->
                JSONObject().put("api", b.apiName).put("url", b.url).put("name", b.name).put("poster", img(b.posterUrl)).put("type", b.type ?: "")
            })))
    }

    private fun bookmark(ex: HttpExchange) {
        val b = body(ex)
        val on = Library.toggleBookmark(Bookmark(b.getString("api"), b.getString("url"), b.optString("name"),
            b.optString("poster").ifBlank { null }?.let(::originalPoster), b.optString("type").ifBlank { null }, System.currentTimeMillis()))
        json(ex, 200, JSONObject().put("bookmarked", on))
    }

    private fun watched(ex: HttpExchange) {
        val b = body(ex)
        val api = b.getString("api"); val url = b.getString("url")
        val eps = b.optJSONArray("episodes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        if (b.optBoolean("watched", true)) Library.markWatched(api, url, b.optString("name"), b.optString("poster").ifBlank { null }?.let(::originalPoster),
            eps.map { it.getString("data") to it.optString("label").ifBlank { null } })
        else Library.markUnwatched(api, url, eps.map { it.getString("data") })
        json(ex, 200, JSONObject().put("ok", true))
    }

    // ---------- Live TV ----------

    private fun live(ex: HttpExchange) {
        if (!LiveTv.availableToProfile) return json(ex, 200, JSONObject().put("channels", JSONArray()))
        val channels = runBlocking { LiveTv.channels() }
        runCatching { runBlocking { withTimeoutOrNull(20_000) { LiveTv.loadGuides() } } }
        val favs = LiveTv.favourites()
        json(ex, 200, JSONObject().put("channels", JSONArray(channels.take(3000).map { c ->
            val (now, next) = LiveTv.nowNext(c)
            JSONObject().put("key", c.key).put("name", c.name).put("group", c.group ?: "Other").put("logo", img(c.logo)).put("number", c.number)
                .put("favourite", c.key in favs)
                .put("now", now?.let { JSONObject().put("title", it.title).put("start", it.start).put("stop", it.stop) } ?: JSONObject.NULL)
                .put("next", next?.let { JSONObject().put("title", it.title).put("start", it.start) } ?: JSONObject.NULL)
        })))
    }

    private fun livePlay(ex: HttpExchange, q: Map<String, String>) {
        val c = runBlocking { LiveTv.channels() }.firstOrNull { it.key == q["key"] } ?: return json(ex, 404, JSONObject().put("error", "No such channel"))
        LiveTv.watched(c)
        if (c.source.startsWith("stremio:")) {
            val addon = StremioAddons.all().firstOrNull { "stremio:" + it.manifest.id == c.source } ?: return json(ex, 404, JSONObject().put("error", "Add-on missing"))
            val api = StremioApi(addon)
            val link = runBlocking {
                val r = Engine.load(api, c.url) ?: return@runBlocking null
                val p = Engine.playables(r).firstOrNull() ?: return@runBlocking null
                Engine.allLinks(api, p.data).links.firstOrNull()
            } ?: return json(ex, 404, JSONObject().put("error", "No stream for ${c.name}"))
            return json(ex, 200, JSONObject().put("name", c.name).put("url", playUrl(link.url, link.getAllHeaders())).put("hls", link.type == ExtractorLinkType.M3U8))
        }
        json(ex, 200, JSONObject().put("name", c.name).put("url", StreamProxy.register(c.url, c.headers + mapOf("User-Agent" to USER_AGENT)))
            .put("hls", true))
    }

    // ---------- images and subtitles ----------

    private fun image(ex: HttpExchange, q: Map<String, String>) {
        val url = q["u"]?.takeIf { it.startsWith("http") } ?: return json(ex, 400, JSONObject().put("error", "bad image"))
        val req = Request.Builder().url(url).headers((mapOf("User-Agent" to USER_AGENT) + decodeHeaders(q["h"])).toHeaders()).build()
        app.baseClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return ex.sendResponseHeaders(r.code, -1)
            val bytes = r.body.bytes()
            ex.responseHeaders.set("Content-Type", r.header("Content-Type") ?: "image/jpeg")
            ex.responseHeaders.set("Cache-Control", "private, max-age=604800")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.write(bytes)
        }
    }

    /** Subtitles as WebVTT, the only format Safari shows (SRT is converted). */
    private fun subtitle(ex: HttpExchange, q: Map<String, String>) {
        val url = q["u"] ?: return json(ex, 400, JSONObject().put("error", "bad subtitle"))
        val text = if (url.startsWith("http")) {
            val req = Request.Builder().url(url).headers((mapOf("User-Agent" to USER_AGENT) + decodeHeaders(q["h"])).toHeaders()).build()
            app.baseClient.newCall(req).execute().use { r -> if (!r.isSuccessful) return ex.sendResponseHeaders(r.code, -1); r.body.string() }
        } else return json(ex, 400, JSONObject().put("error", "bad subtitle"))
        val vtt = toVtt(text)
        val bytes = vtt.toByteArray()
        ex.responseHeaders.set("Content-Type", "text/vtt; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    fun toVtt(text: String): String {
        val t = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        if (t.trimStart().startsWith("WEBVTT")) return t
        val body = t.replace(Regex("(\\d{1,2}:\\d{2}:\\d{2}),(\\d{3})"), "$1.$2")
        return "WEBVTT\n\n$body"
    }

    /** Runs the server until the process is stopped (systemd, Ctrl+C). */
    fun serve(host: String, port: Int) {
        println("Loading extensions…")
        val failed = runBlocking { PluginManager.loadAll() }
        failed.forEach { (n, e) -> System.err.println("warn: $n failed to load: $e") }
        println("${Engine.allProviders().size} providers loaded")
        runCatching { runBlocking { if (Settings.autoUpdateExtensions) PluginManager.updateAll() } }
        start(host, port)
        println("Web app on http://$host:$port  (stop with Ctrl+C)")
        Thread.currentThread().join()
    }

}
