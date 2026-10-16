package eu.kanade.tachiyomi.animeextension.pt.hentaistube.extractors

import android.util.Log
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder

class UniversalExtractor(private val client: OkHttpClient) {

    private val tag = "HentaisTube-Extractor"
    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun videosFromUrl(pageUrl: String, iframeUrl: String): List<Video> {
        Log.e(tag, "Page URL: $pageUrl | Iframe: ${iframeUrl.take(150)}")

        val headers = Headers.Builder()
            .add("User-Agent", uaDesktop)
            .add("Referer", pageUrl)
            .build()

        // 🎯 P2 PLAYER (muitohentai.com/players/p2/?padrao=...)
        // Detecta e resolve direto via p2.php → redirect 302 → .mp4
        if (iframeUrl.contains("/players/p2/") && iframeUrl.contains("padrao=")) {
            val p2Videos = extractP2Player(iframeUrl)
            if (p2Videos.isNotEmpty()) return p2Videos
        }

        // 0. MP4 DIRETO (server4.baixarhentais.com e afins)
        extractDirectMp4(iframeUrl, pageUrl)?.let { return listOf(it) }

        // 1. Blogger direto (Principal)
        runCatching {
            val videos = runBlocking {
                bloggerExtractor.videosFromUrl(iframeUrl, headers, "Blogger")
            }
            if (videos.isNotEmpty()) return sortByQuality(videos)
        }

        // 2. Resolve redirects
        val resolvedUrl = resolveRedirect(iframeUrl, pageUrl) ?: iframeUrl

        // 2.1. MP4 direto após redirect
        extractDirectMp4(resolvedUrl, pageUrl)?.let { return listOf(it) }

        runCatching {
            val videos = runBlocking {
                bloggerExtractor.videosFromUrl(resolvedUrl, headers, "Blogger")
            }
            if (videos.isNotEmpty()) return sortByQuality(videos)
        }

        // 3. HTML → iframe interno do Blogger
        val htmlContent = fetchHtml(resolvedUrl, headers) ?: return emptyList()

        val innerIframeRegex = """<iframe[^>]+src=["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        val innerIframeMatch = innerIframeRegex.find(htmlContent)?.groupValues?.get(1)

        if (!innerIframeMatch.isNullOrBlank()) {
            val fullInnerUrl = if (innerIframeMatch.startsWith("http")) {
                innerIframeMatch
            } else {
                "https:" + innerIframeMatch.removePrefix("https:").removePrefix("http:")
            }
            runCatching {
                val videos = runBlocking {
                    bloggerExtractor.videosFromUrl(fullInnerUrl, headers, "Blogger")
                }
                if (videos.isNotEmpty()) return sortByQuality(videos)
            }
        }

        // 4. Fallback regex no HTML
        val extractedVideos = extractMp4FromHtml(htmlContent)
        if (extractedVideos.isNotEmpty()) return sortByQuality(extractedVideos)

        Log.e(tag, "Nenhum vídeo válido pôde ser extraído de $iframeUrl")
        return emptyList()
    }

    // ==========================================================
    //  🎯 P2 PLAYER (muitohentai.com/players/p2/?padrao=...)
    //
    //  Fluxo descoberto via DevTools:
    //   1. ?padrao=BASE64 → wrapper
    //   2. JS cria: p2.php?id=BASE64 (MESMO base64)
    //   3. p2.php → 302 Location: server4.baixarhentais.com/lote1/...mp4
    //   4. mp4 toca
    // ==========================================================

    private fun extractP2Player(iframeUrl: String): List<Video> {
        val padrao = iframeUrl
            .substringAfter("padrao=", "")
            .substringBefore("&")
            .trim()

        if (padrao.isBlank()) {
            Log.e(tag, "P2: padrao vazio")
            return emptyList()
        }

        val baseHost = runCatching {
            val u = URI(iframeUrl)
            "${u.scheme}://${u.host}"
        }.getOrDefault("https://www.muitohentai.com")

        val p2Url = "$baseHost/players/p2/p2.php?id=$padrao"
        Log.e(tag, "P2 URL: $p2Url")

        return try {
            // ⚠️ NÃO seguir redirect — precisamos ler o header Location
            val noRedirect = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val req = Request.Builder()
                .url(p2Url)
                .header("User-Agent", uaDesktop)
                .header("Referer", "$baseHost/players/p2/")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .build()

            noRedirect.newCall(req).execute().use { resp ->
                val location = resp.header("Location")
                if (location.isNullOrBlank()) {
                    Log.e(tag, "P2: sem Location header")
                    return emptyList()
                }

                Log.e(tag, "P2 Location: $location")

                // Filtro anti-ad (pre-roll)
                if (isAdUrl(location)) {
                    Log.e(tag, "P2: Location é ad/pre-roll — descartando")
                    return emptyList()
                }

                if (!location.contains(".mp4", ignoreCase = true)) {
                    Log.e(tag, "P2: Location não é mp4")
                    return emptyList()
                }

                val decodedName = runCatching {
                    URLDecoder.decode(location.substringAfterLast('/'), "UTF-8")
                }.getOrDefault(location.substringAfterLast('/'))

                val quality = detectMp4Quality(decodedName)

                val videoHeaders = Headers.Builder()
                    .add("User-Agent", uaDesktop)
                    .add("Referer", "https://www.muitohentai.com/")
                    .add("Accept", "*/*")
                    .add("Accept-Encoding", "identity")
                    .build()

                listOf(Video(location, quality, location, videoHeaders))
            }
        } catch (e: Exception) {
            Log.e(tag, "P2 erro: ${e.message}")
            emptyList()
        }
    }

    // ==========================================================
    //  MP4 DIRETO
    // ==========================================================

    private fun extractDirectMp4(url: String, referer: String): Video? {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http")) return null

        val path = runCatching { URI(cleanUrl).path.orEmpty() }.getOrDefault("")
        if (!path.endsWith(".mp4", ignoreCase = true)) return null

        if (isAdUrl(cleanUrl)) {
            Log.e(tag, "Ignorando ad direto: $cleanUrl")
            return null
        }

        val decodedName = runCatching {
            URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        }.getOrDefault(path)

        val quality = detectMp4Quality(decodedName)

        val mp4Referer = runCatching {
            val u = URI(cleanUrl)
            "${u.scheme}://${u.host}/"
        }.getOrDefault(referer)

        val mp4Headers = Headers.Builder()
            .add("User-Agent", uaDesktop)
            .add("Referer", mp4Referer)
            .add("Accept", "video/*")
            .add("Accept-Encoding", "identity")
            .build()

        Log.e(tag, "MP4 direto: $cleanUrl | qual=$quality")
        return Video(cleanUrl, quality, cleanUrl, mp4Headers)
    }

    // ==========================================================
    //  DETECÇÃO DE QUALIDADE
    // ==========================================================

    /**
     * Lê a qualidade a partir do nome do arquivo.
     * - Se tiver resolução explícita (1080p, 720p, 480p, 360p) → usa direto.
     * - Senão, usa tag SD/HD sem inventar número.
     */
    private fun detectMp4Quality(fileName: String): String {
        val upper = fileName.uppercase()
        // Se tiver resolução explícita, usa
        Regex("""(\d{3,4})P""").find(upper)?.let {
            return "${it.groupValues[1]}p"
        }
        // Senão, usa tag SD/HD sem inventar número
        return when {
            upper.contains("HD") -> "HD"
            upper.contains("SD") -> "SD"
            upper.contains("480") -> "480p"
            else -> "MP4"
        }
    }

    // ==========================================================
    //  ANTI-AD
    // ==========================================================

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "pre-roll", "preroll", "pre_roll",
            "/ads/", "/ad/", "/advert",
            "promo", "trailer", "sample",
            "sacanas",
        ).any { lower.contains(it) }
    }

    // ==========================================================
    //  HELPERS
    // ==========================================================

    private fun sortByQuality(videos: List<Video>): List<Video> = videos.sortedByDescending { extractResolution(it.quality) }

    private fun extractResolution(label: String): Int {
        val match = Regex("""(\d{3,4})p""").find(label)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun detectQuality(url: String): String? = when {
        url.contains("itag=37") -> "1080p"
        url.contains("itag=22") -> "720p"
        url.contains("itag=59") -> "480p"
        url.contains("itag=18") -> "360p"
        url.contains("itag=17") -> "144p"
        else -> null
    }

    private fun fetchHtml(url: String, headers: Headers): String? = try {
        val request = Request.Builder().url(url).headers(headers).build()
        client.newCall(request).execute().use { response ->
            response.body?.string()
        }
    } catch (e: Exception) {
        Log.e(tag, "Erro ao buscar HTML: ${e.message}")
        null
    }

    private fun extractMp4FromHtml(html: String): List<Video> {
        val videoList = mutableListOf<Video>()

        val regex = """https?://[^\s"'<>]+?(?:googlevideo\.com/videoplayback|\.mp4)[^\s"'<>]*""".toRegex()
        val matches = regex.findAll(html).map { it.value }.distinct().toList()

        matches.forEachIndexed { index, rawUrl ->
            val cleanUrl = rawUrl
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("\\/", "/")
                .trimEnd('\\', '"', '\'', ';')

            if (isAdUrl(cleanUrl)) {
                Log.e(tag, "Ignorando ad no HTML: $cleanUrl")
                return@forEachIndexed
            }

            if (cleanUrl.contains("googlevideo.com") || cleanUrl.contains(".mp4")) {
                val videoHeaders = Headers.Builder()
                    .add("User-Agent", uaDesktop)
                    .add("Referer", "https://www.muitohentai.com/")
                    .build()

                val quality = detectQuality(cleanUrl) ?: "Vídeo ${index + 1}"
                videoList.add(Video(cleanUrl, quality, cleanUrl, videoHeaders))
            }
        }
        return videoList
    }

    private fun resolveRedirect(url: String, referer: String): String? = try {
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var currentUrl = url
        var hops = 0

        while (hops < 5) {
            hops++
            val request = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", uaDesktop)
                .header("Referer", referer)
                .build()

            val resp = noRedirect.newCall(request).execute()
            resp.use {
                if (it.isRedirect) {
                    val location = it.header("Location") ?: return currentUrl
                    currentUrl = if (location.startsWith("http")) {
                        location
                    } else {
                        URI(currentUrl).resolve(location).toString()
                    }
                    continue
                }
                return currentUrl
            }
        }
        currentUrl
    } catch (e: Exception) {
        null
    }
}
