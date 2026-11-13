package com.lagradost.cloudstream3.linux

import android.os.Handler
import android.os.SystemClock
import android.webkit.CookieManager
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android/app classes extensions use that the desktop provides itself (found by checking the Phisher repo). */
class ShimTest {
    @Test
    fun `handler runs, delays and cancels work`() {
        val h = Handler()
        val ran = AtomicInteger()
        val cancelled = Runnable { ran.addAndGet(100) }
        val later = CountDownLatch(1)
        h.postDelayed(cancelled, 150)
        h.postDelayed({ ran.incrementAndGet(); later.countDown() }, 50)
        h.removeCallbacks(cancelled)
        assertTrue(later.await(2, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(1, ran.get())
        val a = SystemClock.elapsedRealtime(); Thread.sleep(20)
        assertTrue(SystemClock.elapsedRealtime() - a >= 15)
    }

    @Test
    fun `CookieManager shares the app's cookies and Gson and CloudflareKiller exist`() {
        Platform.init()
        Network.init()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.setCookie("https://example.org/", "cf_clearance=abc; Path=/")
        assertEquals("cf_clearance=abc", cm.getCookie("https://example.org/page"))
        // The app's own requests send it too.
        assertEquals(listOf("cf_clearance=abc"), Network.cookies.loadForRequest("https://example.org/x".toHttpUrl()).map { "${it.name}=${it.value}" })
        val killer = CloudflareKiller()
        assertEquals("cf_clearance=abc", killer.getCookieHeaders("https://example.org/")["cookie"])
        cm.removeAllCookies(null)
        assertEquals(null, cm.getCookie("https://example.org/"))
        // Gson, which the official app ships and extensions use for JSON.
        val json = com.google.gson.JsonParser.parseString("""{"a":[1,2]}""").asJsonObject
        assertEquals(2, json.getAsJsonArray("a").size())
    }

    @Test
    fun `LruCache, system services and Uri fromFile (LiveSports from repository 759)`() {
        val evicted = mutableListOf<String>()
        val cache = object : android.util.LruCache<String, String>(10) {
            override fun sizeOf(key: String, value: String) = value.length
            override fun entryRemoved(evicted0: Boolean, key: String, oldValue: String, newValue: String?) { if (evicted0) evicted += key }
        }
        cache.put("a", "12345"); cache.put("b", "1234"); cache.get("a"); cache.put("c", "123")
        assertEquals(listOf("b"), evicted)
        assertEquals(setOf("a", "c"), cache.snapshot().keys)
        Platform.init()
        val am = Platform.context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        assertEquals(false, am.isLowRamDevice())
        assertTrue(am.getMemoryClass() >= 128)
        assertEquals(null, Platform.context.getSystemService("something-else"))
        assertTrue(android.net.Uri.fromFile(java.io.File("/tmp/x.jpg")).toString().startsWith("file:///"))
    }
}
