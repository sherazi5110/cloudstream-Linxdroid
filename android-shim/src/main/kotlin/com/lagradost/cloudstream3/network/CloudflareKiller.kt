package com.lagradost.cloudstream3.network

import android.webkit.CookieManager
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI

/**
 * The official app's Cloudflare helper, with the same public members. On Android it opens an invisible WebView to
 * pass Cloudflare's browser check; the desktop has no WebView, so here requests go through as they are, with any
 * cookies saved for the site (for example from [CookieManager]). Sites without the check work normally.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /** The headers with the cookies saved for [url]'s site. */
    fun getCookieHeaders(url: String): Headers {
        val host = runCatching { URI(url).host }.getOrNull() ?: return Headers.headersOf()
        val cookies = savedCookies[host] ?: CookieManager.getInstance().getCookie(url)?.let(::parseCookieMap)?.takeIf { it.isNotEmpty() }
        return if (cookies.isNullOrEmpty()) Headers.headersOf()
        else Headers.headersOf("cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val saved = savedCookies[request.url.host]
        if (saved.isNullOrEmpty()) return chain.proceed(request)
        val cookie = (request.header("Cookie")?.let { parseCookieMap(it) } ?: emptyMap()) + saved
        return chain.proceed(request.newBuilder().header("Cookie", cookie.entries.joinToString("; ") { "${it.key}=${it.value}" }).build())
    }
}
