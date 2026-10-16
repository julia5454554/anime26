package eu.kanade.tachiyomi.animeextension.pt.porndude

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

class PornDude : AnimeHttpSource() {

    override val name = "3D Porn Dude"
    override val baseUrl = "https://3dporndude.com"
    override val lang = "pt"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            baseUrl
        } else {
            "$baseUrl/?mode=async&function=get_block&block_id=list_videos_most_recent_videos&sort_by=post_date&from=$page&_=${System.currentTimeMillis()}"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseVideoCards(document)
        val hasNextPage = document.selectFirst("a.next") != null || animes.isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.trim().replace(" ", "+")
        val url = if (page == 1) {
            "$baseUrl/search/?q=$encodedQuery"
        } else {
            "$baseUrl/search/$encodedQuery/page/$page/"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseVideoCards(document)
        val hasNextPage = document.selectFirst("a.next") != null ||
            document.select("ul.pagination a[href*='page']").isNotEmpty() ||
            animes.isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(response.request.url.toString())

        anime.title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Sem título"

        anime.thumbnail_url = document.selectFirst("div.fp-poster img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        anime.description = extractDescription(document)

        val script = document.select("script").firstOrNull { it.html().contains("flashvars") || it.html().contains("video_url") }
        if (script != null) {
            val categories = extractFlashvar(script.html(), "video_categories")
            if (!categories.isNullOrBlank()) {
                anime.genre = categories.split(",").joinToString(", ") { it.trim() }
            }
        }

        anime.status = SAnime.COMPLETED
        return anime
    }

    // =========================== Episode List ============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Vídeo"
            episode_number = 1f
            date_upload = 0L
        }
        return listOf(episode)
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()

        val videos = extractVideosFromDocument(document, pageUrl)
        if (videos.isNotEmpty()) return videos

        val videoId = extractVideoId(pageUrl)
        if (videoId != null) {
            val embedUrl = "$baseUrl/embed/$videoId"
            try {
                client.newCall(GET(embedUrl, headers)).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val embedDoc = resp.asJsoup()
                        val embedVideos = extractVideosFromDocument(embedDoc, embedUrl)
                        if (embedVideos.isNotEmpty()) return embedVideos
                    }
                }
            } catch (_: Exception) {
            }
        }

        return emptyList()
    }

    // ============================= Utilities ==============================
    private fun parseVideoCards(document: Document): List<SAnime> {
        return document.select("div.thumb-itm").mapNotNull { element ->
            val link = element.selectFirst("a[href*='/video/']") ?: return@mapNotNull null
            val title = link.attr("title").trim()
            val href = link.attr("href")
            val thumbnail = element.selectFirst("img")?.attr("data-webp")
                ?: element.selectFirst("img")?.attr("src")

            SAnime.create().apply {
                this.title = title
                this.setUrlWithoutDomain(href)
                this.thumbnail_url = thumbnail?.let { if (it.startsWith("http")) it else baseUrl + it }
            }
        }
    }

    private fun extractDescription(document: Document): String {
        val selectors = listOf(
            "div.video-description",
            "div.description",
            "div.wp-content",
            "div.entry-content",
            "div.post-content",
            "div.video-info",
            "meta[name='description']",
            "meta[property='og:description']",
        )
        for (selector in selectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                if (element.tagName() == "meta") {
                    val content = element.attr("content").trim()
                    if (content.isNotBlank()) return content
                } else {
                    val text = element.text().trim()
                    if (text.isNotBlank()) return text
                }
            }
        }
        return ""
    }

    private fun extractVideosFromDocument(document: Document, pageUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        val html = document.html()

        val pairRegex = Regex("""(video_url|video_alt_url\d*)\s*:\s*['"]([^'"]+)['"]""")
        val textRegex = Regex("""(video_url_text|video_alt_url\d*_text)\s*:\s*['"]([^'"]+)['"]""")

        val qualityMap = textRegex.findAll(html).associate {
            val key = it.groupValues[1].removeSuffix("_text")
            key to it.groupValues[2]
        }

        pairRegex.findAll(html).forEach { match ->
            val key = match.groupValues[1]
            var url = match.groupValues[2].replace("&amp;", "&").trim()

            if (url.isNotBlank() && !url.startsWith("function") && url.startsWith("http")) {
                val quality = qualityMap[key] ?: when {
                    "4k" in url.lowercase() -> "4K"
                    "1080" in url -> "1080p"
                    "720" in url -> "720p"
                    "480" in url -> "480p"
                    "360" in url -> "360p"
                    else -> "HD"
                }

                val videoHeaders = headers.newBuilder()
                    .set("Referer", pageUrl)
                    .set("Origin", baseUrl)
                    .set("Accept", "*/*")
                    .build()

                videos.add(Video(url, quality, url, videoHeaders))
            }
        }

        if (videos.isNotEmpty()) return videos.distinctBy { it.url }

        val mp4Regex = Regex("""https?://[^\s"'<>]+?\.mp4[^\s"'<>]*""")
        mp4Regex.findAll(html).forEach { match ->
            val url = match.value.replace("&amp;", "&")
            val videoHeaders = headers.newBuilder()
                .set("Referer", pageUrl)
                .set("Origin", baseUrl)
                .set("Accept", "*/*")
                .build()
            videos.add(Video(url, "MP4 Direct", url, videoHeaders))
        }

        if (videos.isNotEmpty()) return videos.distinctBy { it.url }

        document.select("video source, video").forEach { element ->
            var src = element.attr("src").ifBlank { element.attr("data-src") }
            if (src.isNotBlank()) {
                if (src.startsWith("//")) {
                    src = "https:$src"
                } else if (src.startsWith("/")) {
                    src = "$baseUrl$src"
                }

                val videoHeaders = headers.newBuilder()
                    .set("Referer", pageUrl)
                    .set("Origin", baseUrl)
                    .set("Accept", "*/*")
                    .build()
                videos.add(Video(src, "Video", src, videoHeaders))
            }
        }

        return videos.distinctBy { it.url }
    }

    private fun extractVideoId(url: String): String? = Regex("""/(?:video|embed)/(\d+)""").find(url)?.groupValues?.get(1)

    private fun extractFlashvar(script: String, key: String): String? {
        val regexSingle = Regex("""$key\s*:\s*'([^']*)'""", RegexOption.DOT_MATCHES_ALL)
        regexSingle.find(script)?.let { return it.groupValues[1].trim() }

        val regexDouble = Regex("""$key\s*:\s*"([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
        regexDouble.find(script)?.let { return it.groupValues[1].trim() }

        return null
    }
}
