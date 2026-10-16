package eu.kanade.tachiyomi.animeextension.pt.porcore

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
import org.jsoup.nodes.Document

class Porcore : AnimeHttpSource() {

    override val name = "Porcore"
    override val baseUrl = "https://porcore.com"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/?ajax&p=$page"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseVideoCards(document)
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "%20")
        val url = if (page == 1) {
            "$baseUrl/show/$encodedQuery?ajax&sort=newest"
        } else {
            "$baseUrl/show/$encodedQuery?ajax&p=$page&sort=newest"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseVideoCards(document)
        return AnimesPage(animes, animes.isNotEmpty())
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            title = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                ?: "Sem título"
            thumbnail_url = document.selectFirst("div.video-player img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: ""
            description = extractDescription(document)
            status = SAnime.COMPLETED
        }
    }

    // =========================== Episode List ============================
    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Vídeo"
            episode_number = 1f
            date_upload = 0L
        },
    )

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return extractVideosFromDocument(document, response.request.url.toString())
    }

    // ============================= Utilities ==============================
    private fun parseVideoCards(document: Document): List<SAnime> = document.select("div.onevideothumb").mapNotNull { element ->
        val link = element.selectFirst("a.clip-link") ?: return@mapNotNull null
        val href = link.attr("href")
        if (href.isEmpty()) return@mapNotNull null
        val title = link.attr("title").ifBlank {
            link.selectFirst("h5")?.text()?.trim() ?: ""
        }
        val img = element.selectFirst("img")
        val thumbnail = img?.attr("src")
        SAnime.create().apply {
            this.title = title
            setUrlWithoutDomain(href)
            thumbnail_url = thumbnail?.let { if (it.startsWith("http")) it else baseUrl + it }
        }
    }

    private fun extractDescription(document: Document): String {
        val selectors = listOf(
            "div.video-description",
            "div.description",
            "div.entry-content",
            "div.post-content",
            "div.well",
            "meta[name='description']",
            "meta[property='og:description']",
        )
        for (selector in selectors) {
            val element = document.selectFirst(selector) ?: continue
            if (element.tagName() == "meta") {
                val content = element.attr("content").trim()
                if (content.isNotBlank()) return content
            } else {
                val text = element.text().trim()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun extractVideosFromDocument(document: Document, pageUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        val videoHeaders = buildVideoHeaders(pageUrl)

        val source = document.selectFirst("video#currentvideo_html5_api source[src]")
            ?: document.selectFirst("div.video-player source[src]")
            ?: document.selectFirst("video source[src]")

        if (source != null) {
            val src = normalizeUrl(source.attr("src").trim())
            if (src.isNotEmpty()) {
                val quality = if (src.contains(".mp4", ignoreCase = true)) "MP4" else "HLS"
                videos.add(Video(src, quality, src, videoHeaders))
            }
        }

        if (videos.isEmpty()) {
            document.select("video[src]").forEach { element ->
                val src = normalizeUrl(element.attr("src").trim())
                if (src.isNotEmpty()) {
                    val quality = if (src.contains(".mp4", ignoreCase = true)) "MP4" else "Video"
                    videos.add(Video(src, quality, src, videoHeaders))
                }
            }
        }

        if (videos.isEmpty()) {
            val mediaRegex = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""")
            mediaRegex.findAll(document.html()).forEach { match ->
                val url = match.value.replace("&amp;", "&")
                val quality = if (url.contains(".mp4", ignoreCase = true)) "MP4" else "HLS"
                videos.add(Video(url, quality, url, videoHeaders))
            }
        }

        return videos.distinctBy { it.url }
    }

    private fun normalizeUrl(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$baseUrl$raw"
        else -> raw
    }

    private fun buildVideoHeaders(pageUrl: String): Headers = headers.newBuilder()
        .set("Referer", pageUrl)
        .set("Accept", "video/webm,video/ogg,video/*;q=0.9,*/*;q=0.5")
        .set("Accept-Encoding", "identity")
        .set("Range", "bytes=0-")
        .set("Connection", "keep-alive")
        .build()
}
