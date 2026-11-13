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
}
