package android.webkit

/** android.webkit.ValueCallback */
fun interface ValueCallback<T> {
    fun onReceiveValue(value: T)
}

/**
 * Android's WebView cookie store, for extensions that read or set cookies through it (often next to
 * CloudflareKiller). There is no WebView on the desktop, so it is backed by the app's own cookie store
 * ([backend], set by the app at start-up): cookies set here are sent with the app's requests and vice versa.
 */
abstract class CookieManager {
    /** What the app plugs in: its HTTP cookie store. */
    interface Backend {
        fun getCookie(url: String): String?
        fun setCookie(url: String, value: String)
        fun removeAll()
    }

    abstract fun getCookie(url: String?): String?
    abstract fun setCookie(url: String?, value: String?)
    abstract fun setCookie(url: String?, value: String?, callback: ValueCallback<Boolean>?)
    abstract fun removeAllCookies(callback: ValueCallback<Boolean>?)
    abstract fun removeSessionCookies(callback: ValueCallback<Boolean>?)
    abstract fun hasCookies(): Boolean
    abstract fun flush()
    abstract fun setAcceptCookie(accept: Boolean)
    abstract fun acceptCookie(): Boolean
    open fun setAcceptThirdPartyCookies(webview: WebView?, accept: Boolean) = Unit
    open fun acceptThirdPartyCookies(webview: WebView?): Boolean = true
    @Deprecated("Android API") open fun removeAllCookie() = removeAllCookies(null)
    @Deprecated("Android API") open fun removeSessionCookie() = removeSessionCookies(null)
    @Deprecated("Android API") open fun removeExpiredCookie() = Unit

    companion object {
        @JvmStatic @Volatile var backend: Backend? = null
        private val instance = object : CookieManager() {
            private val fallback = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, String>>()
            private var accept = true
            private fun host(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url

            override fun getCookie(url: String?): String? {
                url ?: return null
                backend?.let { return it.getCookie(url)?.takeIf { c -> c.isNotBlank() } }
                return fallback[host(url)]?.entries?.joinToString("; ") { "${it.key}=${it.value}" }?.takeIf { it.isNotBlank() }
            }
            override fun setCookie(url: String?, value: String?) {
                if (url == null || value == null || !accept) return
                backend?.let { it.setCookie(url, value); return }
                val pair = value.substringBefore(';').split("=", limit = 2)
                if (pair.size == 2) fallback.getOrPut(host(url)) { java.util.concurrent.ConcurrentHashMap() }[pair[0].trim()] = pair[1].trim()
            }
            override fun setCookie(url: String?, value: String?, callback: ValueCallback<Boolean>?) {
                setCookie(url, value); callback?.onReceiveValue(true)
            }
            override fun removeAllCookies(callback: ValueCallback<Boolean>?) {
                backend?.removeAll(); fallback.clear(); callback?.onReceiveValue(true)
            }
            override fun removeSessionCookies(callback: ValueCallback<Boolean>?) { callback?.onReceiveValue(true) }
            override fun hasCookies(): Boolean = fallback.isNotEmpty() || backend != null
            override fun flush() = Unit
            override fun setAcceptCookie(accept: Boolean) { this.accept = accept }
            override fun acceptCookie(): Boolean = accept
        }

        @JvmStatic fun getInstance(): CookieManager = instance
    }
}

/**
 * android.webkit.WebView: there is none on the desktop. This only lets extensions that mention it load (for example
 * to call CookieManager.setAcceptThirdPartyCookies); anything that needs a real web page fails, as expected.
 */
open class WebView(@Suppress("UNUSED_PARAMETER") context: android.content.Context?)

/** android.webkit.WebStorage: there is no WebView storage on the desktop, so clearing it does nothing. */
open class WebStorage {
    open fun deleteAllData() = Unit
    open fun deleteOrigin(origin: String?) = Unit

    companion object {
        private val instance = WebStorage()
        @JvmStatic fun getInstance(): WebStorage = instance
    }
}
