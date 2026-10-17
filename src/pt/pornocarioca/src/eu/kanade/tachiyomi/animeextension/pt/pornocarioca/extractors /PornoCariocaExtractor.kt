package eu.kanade.tachiyomi.animeextension.pt.pornocarioca.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class PornoCariocaExtractor(
    private val client: OkHttpClient,
    private val appHeaders: Headers,
) {

    private val adPatterns = listOf(
        "cdn.sources.network", "assets.sources.network",
        "sources.network", "sacanas", "/ads/", "/ad/",
        "pre-roll", "preroll", "pre_roll",
        "doubleclick", "googlesyndication", "adservice", "adsystem",
        "pornocariocapremium",
    )

    private fun isAd(url: String): Boolean = adPatterns.any { url.contains(it, ignoreCase = true) }

    fun videosFromPage(referer: String, document: Document): List<Video> {
        val videos = mutableListOf<Video>()

        // Não adicionar "Range" aqui: quebra o seek em alguns CDNs.
        // Usa o User-Agent do próprio app para ficar consistente com o client.
        val playerHeaders = Headers.Builder()
            .add("Referer", referer)
            .add("Origin", "https://www.pornocarioca.com")
            .add("User-Agent", appHeaders["User-Agent"] ?: DEFAULT_UA)
            .build()

        try {
            // 1. Iframe do player self-hosted
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
                if (src.contains("videos.pornocarioca.com/embed/")) {
                    extractFromIframe(src, referer, playerHeaders, videos)
                }
            }

            // 2. <video> / <source> direto na página
            if (videos.isEmpty()) {
                document.select("video[src], video source[src], source[src]").forEach { source ->
                    val src = resolveUrl(source.attr("src"), referer)
                    if (src.startsWith("http") && !isAd(src)) {
                        val quality = source.attr("label")
                            .ifBlank { if (src.contains(".m3u8")) "HLS" else "MP4" }
                        videos.add(Video(src, quality, src, playerHeaders))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return videos
            .filter { it.url.startsWith("http") && it.url.length < 2048 }
            .distinctBy { it.url }
    }

    private fun extractFromIframe(
        iframeUrl: String,
        referer: String,
        playerHeaders: Headers,
        out: MutableList<Video>,
    ) {
        try {
            val reqHeaders = playerHeaders.newBuilder()
                .set("Referer", referer)
                .build()

            val req = Request.Builder().url(iframeUrl).headers(reqHeaders).build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (body.isBlank()) return

            val iframeDoc = Jsoup.parse(body, iframeUrl)
            val html = iframeDoc.toString()

            // a) <video> / <source> no embed
            iframeDoc.select("video[src], video source[src], source[src]").forEach { source ->
                val src = source.attr("src")
                if (src.isBlank() || isAd(src)) return@forEach
                val resolved = resolveUrl(src, iframeUrl)
                if (!resolved.startsWith("http")) return@forEach
                val quality = source.attr("label")
                    .ifBlank { source.attr("data-res") }
                    .ifBlank { if (resolved.contains(".m3u8")) "HLS" else "MP4" }
                out.add(Video(resolved, quality, resolved, playerHeaders))
            }

            // b) variáveis JS típicas
            if (out.isEmpty()) {
                val jsPatterns = listOf(
                    Regex("""["']urlMP4["']\s*:\s*["']([^"']+)["']"""),
                    Regex("""["']urlHLS["']\s*:\s*["']([^"']+)["']"""),
                    Regex("""["']file["']\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
                    Regex("""["']source["']\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
                )
                jsPatterns.forEach { regex ->
                    regex.findAll(html).forEach { m ->
                        val url = m.groupValues[1].replace("\\/", "/")
                        if (!url.startsWith("http") || isAd(url)) return@forEach
                        val q = if (url.contains(".m3u8")) "HLS" else "MP4"
                        out.add(Video(url, q, url, playerHeaders))
                    }
                }
            }

            // c) Regex genérica por extensão
            if (out.isEmpty()) {
                val mp4 = Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""")
                val m3u8 = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
                (mp4.findAll(html) + m3u8.findAll(html)).forEach { m ->
                    val url = m.value
                    if (isAd(url)) return@forEach
                    val q = if (url.contains(".m3u8")) "HLS" else "MP4"
                    out.add(Video(url, q, url, playerHeaders))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveUrl(src: String, base: String): String = when {
        src.startsWith("http") -> src
        src.startsWith("//") -> "https:$src"
        else -> base.toHttpUrlOrNull()?.resolve(src)?.toString() ?: src
    }

    private companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
