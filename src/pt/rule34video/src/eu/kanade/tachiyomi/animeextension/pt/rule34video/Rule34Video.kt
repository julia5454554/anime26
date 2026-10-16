package eu.kanade.tachiyomi.animeextension.pt.rule34video

import eu.kanade.tachiyomi.animeextension.pt.rule34video.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Rule34Video : AnimeHttpSource() {

    override val name = "Rule34Video"
    override val baseUrl = "https://rule34video.co"
    override val lang = "pt"
    override val supportsLatest = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val extractor by lazy { UniversalExtractor(client) }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", userAgent)
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    // ==================== LISTAGEM ====================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.posts > div.post").mapNotNull { animeFromElement(it) }
        val hasNextPage = document.selectFirst("div.paginator a:contains(Next)") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    private fun animeFromElement(element: Element): SAnime? {
        val linkElement = element.selectFirst("h2.link > a, a[href*=/watch/]") ?: return null
        val href = linkElement.attr("abs:href").ifBlank { linkElement.attr("href") }
        if (href.isBlank()) return null
        if (!href.contains("/watch/")) return null

        val title = linkElement.text().trim()
            .ifBlank { linkElement.attr("title").trim() }
            .ifBlank { return null }

        val imageUrl = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-src").ifBlank { img.attr("abs:src") }
        } ?: ""

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            this.thumbnail_url = imageUrl
        }
    }

    // ==================== DETALHES ====================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        anime.title = document.selectFirst("h1.post-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: ""

        anime.thumbnail_url = document.selectFirst("#video-cover img")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""

        anime.description = document.selectFirst(".imagempost p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: ""

        anime.genre = document
            .select(".video-tags a[rel=tag], .video-category a[rel~=category]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ==================== EPISÓDIOS ====================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episode = SEpisode.create().apply {
            name = document.selectFirst("h1.post-title")?.text()?.trim() ?: "Assistir"
            setUrlWithoutDomain(response.request.url.toString())
            episode_number = 1f
            date_upload = 0L
        }
        return listOf(episode)
    }

    // ==================== VÍDEO ====================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val html = document.outerHtml()
        val pageUrl = response.request.url.toString()
        return extractor.videosFromHtml(html, pageUrl, headers)
    }

    // ==================== BUSCA ====================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) {
            "$baseUrl/?s=$encodedQuery"
        } else {
            "$baseUrl/page/$page/?s=$encodedQuery"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.posts > div.post").mapNotNull { animeFromElement(it) }
        val hasNextPage = document.selectFirst("div.paginator a:contains(Next)") != null
        return AnimesPage(animeList, hasNextPage)
    }
}
