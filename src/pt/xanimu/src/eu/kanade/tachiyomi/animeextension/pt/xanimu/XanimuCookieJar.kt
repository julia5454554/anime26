// language: Kotlin, file: src/pt/xanimu/src/eu/kanade/tachiyomi/animeextension/pt/xanimu/XanimuCookieJar.kt
// *jar com cf_clearance compartilhado entre xanimu.com e nosofiles.com*

package eu.kanade.tachiyomi.animeextension.pt.xanimu

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "XanimuCookie"

class XanimuCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    @Volatile var userAgent: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val list = store.getOrPut(host) { mutableListOf() }
        synchronized(list) {
            for (c in cookies) {
                list.removeAll { it.name == c.name && it.path == c.path }
                list.add(c)
            }
        }
        if (cookies.isNotEmpty()) {
            Log.d(TAG, "save host=$host names=${cookies.map { it.name }}")
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val now = System.currentTimeMillis()

        val result = mutableListOf<Cookie>()

        store[host]?.let { list ->
            synchronized(list) {
                result.addAll(list.filter { it.expiresAt > now })
            }
        }

        // *cf_clearance do xanimu serve para o CDN nosofiles — mesmo clearance*
        if (host.endsWith("nosofiles.com")) {
            store["xanimu.com"]?.let { list ->
                synchronized(list) {
                    result.addAll(
                        list.filter { it.expiresAt > now && it.name.startsWith("cf_") },
                    )
                }
            }
        }

        if (host.contains("nosofiles") || host.contains("xanimu")) {
            Log.d(TAG, "load host=$host names=${result.map { it.name }}")
        }

        return result
    }

    fun hasCookie(name: String, host: String): Boolean = store[host]?.any { it.name == name && it.expiresAt > System.currentTimeMillis() } == true

    fun setCookiesFromString(raw: String) {
        val hosts = listOf("xanimu.com", "xcdn1.nosofiles.com", "st1.nosofiles.com")
        for (host in hosts) {
            val list = store.getOrPut(host) { mutableListOf() }
            synchronized(list) {
                for (pair in raw.split(";")) {
                    val trimmed = pair.trim()
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    val name = trimmed.substring(0, eq).trim()
                    val value = trimmed.substring(eq + 1).trim()
                    if (name.isEmpty()) continue
                    val cookie = Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(host)
                        .path("/")
                        .expiresAt(System.currentTimeMillis() + 24L * 60 * 60 * 1000)
                        .build()
                    list.removeAll { it.name == name }
                    list.add(cookie)
                }
            }
        }
    }
}
