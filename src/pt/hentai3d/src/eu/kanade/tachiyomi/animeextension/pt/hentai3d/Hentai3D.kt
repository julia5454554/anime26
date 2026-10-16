package eu.kanade.tachiyomi.animeextension.pt.hentai3d

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class Hentai3D : AnimeHttpSource() {

    override val name = "Hentai3D"
    override val baseUrl = "https://hentais3d.net"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/?page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.item.min-w-0")
        val animeList = elements.mapNotNull { element ->
            val linkElement = element.selectFirst("a.image") ?: return@mapNotNull null
            val title = linkElement.attr("title").ifBlank {
                element.selectFirst("a.title")?.text()?.trim() ?: ""
            }
            val url = linkElement.attr("href")
            val thumbnail = element.selectFirst("img.thumb")?.attr("src")
            SAnime.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = thumbnail?.let { fixUrl(it) }
            }
        }
        val hasNextPage = elements.isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "%20")
        val url = if (page == 1) {
            "$baseUrl/search?q=$encodedQuery"
        } else {
            "$baseUrl/search?q=$encodedQuery&page=$page"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(response.request.url.toString())
        anime.title = document.selectFirst("h1, h2.title, .entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Sem título"
        anime.thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("video[poster]")?.attr("poster")
            ?: document.selectFirst("img.thumb")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")
        anime.description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("div.description, div.entry-content, div.video-description, p")?.text()

        val genres = mutableListOf<String>()
        document.select("a[href*='/category/']").forEach { genres.add(it.text().trim()) }
        document.select("a.taxonomy-tag, a[href*='/tags/']").forEach { genres.add(it.text().trim()) }
        document.select("a[rel='tag']").forEach { genres.add(it.text().trim()) }
        anime.genre = genres.distinct().joinToString(", ")

        return anime
    }

    // =========================== Episode List ============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Vídeo"
            episode_number = 1f
        }
        return listOf(episode)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        // 1. Tags <video> e <source>
        document.select("video source, video").forEach { element ->
            val rawSrc = element.attr("src").ifBlank { element.attr("data-src") }
            if (rawSrc.isNotBlank() && !rawSrc.contains("preview.mp4")) {
                val src = fixUrl(rawSrc)
                val quality = element.attr("label").ifBlank { element.attr("title") }.ifBlank { "HD" }
                videos.add(Video(src, quality, src, videoHeaders(pageUrl)))
            }
        }

        // 2. Extração de Iframe (Embeds)
        document.select("iframe[src]").forEach { iframe ->
            val iframeUrl = fixUrl(iframe.attr("src"))
            if (iframeUrl.isNotBlank() && iframeUrl.contains(baseUrl)) {
                try {
                    client.newCall(GET(iframeUrl, headers)).execute().use { embedResp ->
                        if (embedResp.isSuccessful) {
                            val embedDoc = embedResp.asJsoup()
                            videos.addAll(extractFromHtml(embedDoc.html(), iframeUrl))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 3. Extração via JS e Regex no HTML principal
        videos.addAll(extractFromHtml(document.html(), pageUrl))

        return videos.distinctBy { it.videoUrl }
    }

    // ============================= Utilities ==============================
    private fun extractFromHtml(html: String, pageUrl: String): List<Video> {
        val videos = mutableListOf<Video>()

        // Captura do player KVS (video_url, video_alt_url, etc.)
        val pairRegex = Regex("""(?:video_url|video_alt_url\d*|file)\s*:\s*["']([^"']+)["']""")
        val textRegex = Regex("""(?:video_url_text|video_alt_url\d*_text|quality)\s*:\s*["']([^"']+)["']""")

        val qualityMap = textRegex.findAll(html).associate { match ->
            val key = match.groupValues[1].removeSuffix("_text")
            key to match.groupValues[2]
        }

        pairRegex.findAll(html).forEach { match ->
            val rawUrl = match.groupValues[1]
            var url = rawUrl.replace("\\/", "/").replace("&amp;", "&").trim()

            if (url.isNotBlank() && !url.startsWith("function") && !url.contains("preview.mp4")) {
                url = fixUrl(url)
                if (url.startsWith("http")) {
                    val quality = qualityMap[match.groupValues[0]] ?: when {
                        "1080" in url -> "1080p"
                        "720" in url -> "720p"
                        "480" in url -> "480p"
                        "360" in url -> "360p"
                        else -> "HD"
                    }
                    videos.add(Video(url, quality, url, videoHeaders(pageUrl)))
                }
            }
        }

        // Regex genérica para links .mp4 / .m3u8 (incluindo relativos)
        val mediaRegex = Regex("""["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""")
        mediaRegex.findAll(html).forEach { match ->
            var url = match.groupValues[1].replace("\\/", "/").replace("&amp;", "&").trim()
            if (!url.contains("preview.mp4") && !url.endsWith(".png") && !url.endsWith(".jpg")) {
                url = fixUrl(url)
                if (url.startsWith("http")) {
                    videos.add(Video(url, "Direto", url, videoHeaders(pageUrl)))
                }
            }
        }

        return videos
    }

    private fun fixUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> url
    }

    private fun videoHeaders(pageUrl: String): Headers = Headers.Builder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Referer", pageUrl)
        .set("Origin", baseUrl)
        .set("Accept", "*/*")
        .build()
}
