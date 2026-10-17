package eu.kanade.tachiyomi.animeextension.pt.rule34video.extractors

import android.util.Base64
import android.util.Log
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder

class UniversalExtractor(private val client: OkHttpClient) {

    private val tag = "Rule34Video-Extractor"

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private val uaDesktop =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val obfuscatedRegex = """obfuscated\s*=\s*"([^"]+)"""".toRegex()
    private val ogVideoRegex = Regex(
        """<meta[^>]+property=["']og:video(?::secure_url)?["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val jsonLdContentUrlRegex = Regex(""""contentUrl"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val mp4Regex = Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun videosFromHtml(html: String, pageUrl: String, baseHeaders: Headers): List<Video> {
        val videos = mutableListOf<Video>()
        Log.e(tag, "=== Iniciando extração | pageUrl=$pageUrl ===")

        obfuscatedRegex.find(html)?.let { match ->
            deobfuscate(match.groupValues[1])?.let { url ->
                Log.e(tag, "[1] URL via obfuscated: $url")
                videos.add(buildVideo(url, pageUrl, baseHeaders))
            } ?: Log.e(tag, "[1] Falhou ao desofuscar.")
        } ?: Log.e(tag, "[1] 'obfuscated' não encontrado.")

        if (videos.isEmpty()) {
            jsonLdContentUrlRegex.find(html)?.groupValues?.getOrNull(1)?.let { raw ->
                val url = raw.replace("\\/", "/").replace("\\u0026", "&")
                if (url.startsWith("http")) {
                    Log.e(tag, "[2] URL via JSON-LD: $url")
                    videos.add(buildVideo(url, pageUrl, baseHeaders))
                }
            }
        }

        if (videos.isEmpty()) {
            ogVideoRegex.find(html)?.groupValues?.getOrNull(1)?.let { url ->
                if (url.startsWith("http")) {
                    Log.e(tag, "[3] URL via og:video: $url")
                    videos.add(buildVideo(url, pageUrl, baseHeaders))
                }
            }
        }

        if (videos.isEmpty()) {
            val iframes = iframeRegex.findAll(html).map { it.groupValues[1] }.toList()
            Log.e(tag, "[4] ${iframes.size} iframes encontrados.")
            for (raw in iframes) {
                if (raw.contains("shockedguard") || raw.contains("ads") || raw.contains("doubleclick")) {
                    continue
                }
                val fullUrl = normalizeIframeUrl(raw, pageUrl)
                Log.e(tag, "[4] Testando iframe: $fullUrl")

                runCatching {
                    val bloggerVideos = runBlocking {
                        bloggerExtractor.videosFromUrl(fullUrl, baseHeaders, "Blogger")
                    }
                    if (bloggerVideos.isNotEmpty()) {
                        Log.e(tag, "[4a] Blogger retornou ${bloggerVideos.size} vídeo(s).")
                        bloggerVideos.forEach { v ->
                            Log.e(tag, "[4a] URL Blogger: ${v.url}")
                            videos.add(buildVideo(v.url, pageUrl, baseHeaders, quality = v.quality))
                        }
                    }
                }.onFailure { Log.e(tag, "[4a] Erro Blogger: ${it.message}") }

                if (videos.isNotEmpty()) {
                    break
                }
            }
        }

        if (videos.isEmpty()) {
            mp4Regex.findAll(html).forEach { m ->
                val url = sanitize(m.value)
                if (videos.none { it.url == url }) {
                    Log.e(tag, "[5] mp4 direto: $url")
                    videos.add(buildVideo(url, pageUrl, baseHeaders))
                }
            }
        }

        if (videos.isEmpty()) {
            Log.e(tag, "!!! Nenhuma estratégia retornou vídeo.")
        } else {
            Log.e(tag, "=== Total: ${videos.size} vídeo(s) ===")
        }

        return sortByQuality(videos.distinctBy { it.url })
    }

    // ==================== HELPERS ====================

    private fun buildVideo(
        url: String,
        pageUrl: String,
        baseHeaders: Headers,
        quality: String? = null,
    ): Video {
        val cleanUrl = sanitize(url)
        val isGoogle = cleanUrl.contains("googlevideo.com")

        val (finalUrl, finalHeaders, variant) = if (isGoogle) {
            pickGoogleVideoHeaders(cleanUrl, pageUrl)
        } else {
            val h = siteHeaders(cleanUrl, pageUrl, baseHeaders)
            val (u, _) = probeUrl(cleanUrl, h, pageUrl)
            Triple(u, h, "site")
        }

        Log.e(tag, "Variante escolhida: $variant")
        Log.e(tag, "URL final: ${finalUrl.take(150)}")

        val q = quality ?: detectQuality(finalUrl)
        return Video(finalUrl, q, finalUrl, finalHeaders)
    }

    private fun pickGoogleVideoHeaders(url: String, pageUrl: String): Triple<String, Headers, String> {
        val variants = listOf(
            "blogger" to Headers.Builder()
                .add("User-Agent", uaDesktop)
                .add("Referer", "https://www.blogger.com/")
                .add("Origin", "https://www.blogger.com")
                .add("Accept", "*/*")
                .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                .add("Sec-Fetch-Dest", "video")
                .add("Sec-Fetch-Mode", "no-cors")
                .add("Sec-Fetch-Site", "cross-site")
                .build(),
            "site" to Headers.Builder()
                .add("User-Agent", uaDesktop)
                .add("Referer", pageUrl)
                .add("Origin", "https://rule34video.co")
                .add("Accept", "*/*")
                .build(),
            "bare" to Headers.Builder()
                .add("User-Agent", uaDesktop)
                .add("Accept", "*/*")
                .build(),
        )

        for ((name, h) in variants) {
            val (u, code) = probeUrl(url, h, pageUrl)
            Log.e(tag, "  variante=$name → status=$code")
            if (code in 200..299) {
                return Triple(u, h, name)
            }
        }
        return Triple(url, variants.first().second, "blogger-fallback")
    }

    private fun probeUrl(url: String, headers: Headers, referer: String): Pair<String, Int> {
        var currentUrl = url
        var hops = 0
        val startUrl = url

        while (hops < 6) {
            hops++
            val req = try {
                Request.Builder()
                    .url(currentUrl)
                    .headers(headers)
                    .header("Range", "bytes=0-1")
                    .build()
            } catch (e: Exception) {
                Log.e(tag, "URL inválida: ${e.message}")
                return startUrl to -1
            }

            try {
                noRedirectClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    Log.e(tag, "  hop#$hops → $code | ${currentUrl.take(100)}")
                    if (code in 300..399) {
                        val loc = resp.header("Location")
                        if (loc.isNullOrBlank()) {
                            return currentUrl to code
                        }
                        currentUrl = if (loc.startsWith("http")) {
                            loc
                        } else {
                            java.net.URI(currentUrl).resolve(loc).toString()
                        }
                    } else {
                        return currentUrl to code
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Erro no probe: ${e.message}")
                return currentUrl to -1
            }
        }
        return currentUrl to -2
    }

    private fun siteHeaders(url: String, pageUrl: String, baseHeaders: Headers): Headers {
        val origin = Regex("""^(https?://[^/]+)""").find(pageUrl)?.value ?: "https://rule34video.co"
        val cookies = try {
            val httpUrl = url.toHttpUrlOrNull()
            if (httpUrl != null) {
                client.cookieJar.loadForRequest(httpUrl)
                    .joinToString("; ") { "${it.name}=${it.value}" }
                    .takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

        return baseHeaders.newBuilder()
            .add("Accept", "*/*")
            .add("Referer", pageUrl)
            .add("Origin", origin)
            .apply { if (!cookies.isNullOrBlank()) add("Cookie", cookies) }
            .build()
    }

    private fun sanitize(url: String): String = url
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .replace("\\/", "/")
        .replace("\\\\", "\\")
        .trimEnd('\\', '"', '\'', ';')

    private fun normalizeIframeUrl(raw: String, pageUrl: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http") -> raw
        raw.startsWith("/") -> {
            val origin = Regex("""^(https?://[^/]+)""").find(pageUrl)?.value ?: ""
            origin + raw
        }
        else -> raw
    }

    private fun sortByQuality(videos: List<Video>): List<Video> = videos.sortedByDescending { extractResolution(it.quality) }

    private fun extractResolution(label: String): Int {
        val m = Regex("""(\d{3,4})p""").find(label)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun detectQuality(url: String): String = when {
        url.contains("itag=37") || url.contains("1080p", true) -> "1080p"
        url.contains("itag=22") || url.contains("720p", true) -> "720p"
        url.contains("itag=59") || url.contains("480p", true) -> "480p"
        url.contains("itag=18") || url.contains("360p", true) -> "360p"
        else -> "Default"
    }

    private fun deobfuscate(obfuscated: String): String? = try {
        val reversed = obfuscated.reversed()
        val urlDecoded = URLDecoder.decode(reversed, "UTF-8")
        val bytes = Base64.decode(urlDecoded, Base64.DEFAULT)
        val asLatin1 = String(bytes, Charsets.ISO_8859_1)
        val rawUrl = asLatin1.substringBefore("|")
        val finalUrl = URLDecoder.decode(rawUrl, "UTF-8").trim()
        finalUrl.takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        Log.e(tag, "deobfuscate erro: ${e.message}")
        null
    }
}
