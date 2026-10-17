package eu.kanade.tachiyomi.animeextension.pt.animeshentai

import eu.kanade.tachiyomi.animeextension.pt.animeshentai.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimesHentai : AnimeHttpSource() {

    override val name = "Animes Hentai"
    override val baseUrl = "https://animeshentai.biz"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val universalExtractor by lazy { UniversalExtractor(client) }

    private val epNumberRegex = Regex("""Epis[oó]dio\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    // ===== POPULAR =====
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/episodio/page/$page/")

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    // ===== LATEST =====
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodio/page/$page/")

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)

    // ===== SEARCH =====
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/page/$page/?s=$q")
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    // ===== HELPERS =====
    private fun safeBody(response: Response): String = response.body?.string().orEmpty()

    private fun Response.currentPage(): Int {
        val segs = request.url.pathSegments
        val idx = segs.indexOf("page")
        return if (idx >= 0 && idx + 1 < segs.size) segs[idx + 1].toIntOrNull() ?: 1 else 1
    }

    private fun parseListing(response: Response): AnimesPage {
        val doc = Jsoup.parse(safeBody(response), response.request.url.toString())
        val items = doc.select("article.item").mapNotNull { it.toSAnime() }
        val page = response.currentPage()
        val hasNext = doc.select("div.pagination a[href*='/page/']").any { a ->
            val num = a.attr("href").substringAfterLast("/page/").substringBefore("/").toIntOrNull()
            num != null && num > page
        }
        return AnimesPage(items, hasNext)
    }

    private fun Element.toSAnime(): SAnime? {
        val link = selectFirst("div.eptitle h3 a, div.data h3 a") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.contains("/episodio/") && !href.contains("/hentai/")) return null
        val cardTitle = link.text().trim().ifBlank {
            selectFirst("div.poster img")?.attr("alt").orEmpty()
        }
        val thumb = selectFirst("div.poster img")?.attr("src").orEmpty()
        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = cardTitle
            thumbnail_url = thumb
        }
    }

    // ===== DETAILS =====
    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(safeBody(response), response.request.url.toString())
        return SAnime.create().apply {
            title = doc.selectFirst("div.data h1")?.text()
                ?.replace("Todos os Episodios Online", "")
                ?.trim()
                .orEmpty()
            thumbnail_url = doc.selectFirst("div.poster img")?.attr("src")
            genre = doc.select("a[rel=tag][href*=/genero/]")
                .joinToString(", ") { it.text().trim() }
            description = doc.selectFirst("div.wp-content")?.text()?.trim()
            status = SAnime.UNKNOWN
        }
    }

    // ===== EPISODES =====
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(safeBody(response), response.request.url.toString())
        return doc.select("ul.episodios li").mapNotNull { li ->
            val a = li.selectFirst("div.episodiotitle a") ?: return@mapNotNull null
            val pageUrl = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val epTitle = a.text().trim()
            val epNum = epNumberRegex.find(epTitle)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            SEpisode.create().apply {
                url = pageUrl
                name = epTitle
                episode_number = epNum
            }
        }.reversed()
    }

    // ===== VIDEOS =====
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url)

    override fun videoListParse(response: Response): List<Video> = runBlocking {
        val doc = Jsoup.parse(safeBody(response), response.request.url.toString())
        val iframes = doc.select("iframe.metaframe, div#playex iframe, iframe[src]")
            .mapNotNull { it.attr("src").takeIf { s -> s.isNotBlank() } }
            .distinct()
        if (iframes.isEmpty()) return@runBlocking emptyList()

        val results = mutableListOf<Video>()
        iframes.forEachIndexed { idx, src ->
            val suffix = if (iframes.size == 1) {
                ""
            } else {
                if (idx == 0) " Principal" else " Alternativo ${idx + 1}"
            }
            results.addAll(universalExtractor.videosFromUrl(src, headers, suffix))
        }
        results.distinctBy { "${it.url}|${it.quality}" }
    }
}
