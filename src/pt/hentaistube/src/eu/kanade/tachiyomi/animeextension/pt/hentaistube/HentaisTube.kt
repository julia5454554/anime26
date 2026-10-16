package eu.kanade.tachiyomi.animeextension.pt.hentaistube

import eu.kanade.tachiyomi.animeextension.pt.hentaistube.extractors.UniversalExtractor
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

class HentaisTube : AnimeHttpSource() {

    override val name = "HentaisTube"
    override val baseUrl = "https://pt.hentais.tube"
    override val lang = "pt"
    override val supportsLatest = true

    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ================= POPULAR =================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/lista-de-hentais/"
        } else {
            "$baseUrl/lista-de-hentais/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.item.tvshows").mapNotNull { it.toAnime() }
        return AnimesPage(animes, document.hasNextPage())
    }

    // ================= LATEST =================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/ultimos-episodios-adicionados/"
        } else {
            "$baseUrl/ultimos-episodios-adicionados/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.item.se.episodes").mapNotNull { it.toAnime() }
        return AnimesPage(animes, document.hasNextPage())
    }

    // ================= SEARCH =================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) {
            "$baseUrl/buscar/$q"
        } else {
            "$baseUrl/buscar/$q/$page/"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.item").mapNotNull { it.toAnime() }
        return AnimesPage(animes, document.hasNextPage())
    }

    // ================= DETAILS =================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.url = response.request.url.toString()

        val isEpisodePage = response.request.url.encodedPath.contains("/episodios/")

        if (isEpisodePage) {
            val h1 = document.selectFirst("h1.epih1")?.text().orEmpty()
            anime.title = h1.substringBefore(" - ").trim().ifBlank { "Episódio" }
            anime.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            anime.description = document.selectFirst("div#info h6")?.text()
        } else {
            anime.title = document.selectFirst("div.sheader div.data h1")?.text()?.trim().orEmpty()
            anime.thumbnail_url = document.selectFirst("div.sheader div.poster img")?.attr("src")
            anime.description = document.selectFirst("div#info1 .wp-content p")?.text()
            anime.genre = document.select("div.sgeneros a").joinToString(", ") { it.text() }
        }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ================= EPISODES =================

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val isEpisodePage = response.request.url.encodedPath.contains("/episodios/")

        if (isEpisodePage) {
            val h1 = document.selectFirst("h1.epih1")?.text().orEmpty()
            val epName = h1.substringAfter(" - ").trim().ifBlank { "Episódio" }
            return listOf(
                SEpisode.create().apply {
                    url = pageUrl
                    name = epName
                    episode_number = 1f
                },
            )
        }

        return document.select("div#episodes article.item.se.episodes")
            .mapIndexedNotNull { index, el ->
                val link = el.selectFirst("div.season_m a[href]")?.attr("href")
                    ?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val epTitle = el.selectFirst("div.data h3")?.text()?.trim()
                    ?: el.selectFirst("div.season_m a span.c")?.text()?.trim()
                    ?: "Episódio ${index + 1}"
                val epNumber = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (index + 1).toFloat()
                SEpisode.create().apply {
                    url = link.toAbsolute()
                    name = epTitle
                    episode_number = epNumber
                }
            }
            .reversed()
    }

    // ================= VIDEOS =================

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        val videos = mutableListOf<Video>()

        val iframes = document.select("div#playex iframe[src], div.playex .play-box-iframe iframe[src]")
        iframes.forEachIndexed { index, iframe ->
            val src = iframe.attr("src").trim()
            if (src.isBlank()) return@forEachIndexed

            val serverName = if (index == 0) "Principal" else "Alternativo"

            val extracted = runCatching {
                universalExtractor.videosFromUrl(referer, src)
            }.getOrDefault(emptyList())

            extracted.forEach { v ->
                videos.add(Video(v.url, "$serverName - ${v.quality}", v.url, v.headers))
            }
        }

        return videos
    }

    // ================= HELPERS =================

    private fun org.jsoup.nodes.Document.hasNextPage(): Boolean {
        val pag = selectFirst("div#paginacao div.paginacao") ?: return false
        return pag.select("a[href]").any { a ->
            !a.hasClass("atual") && a.attr("href").let { it.isNotBlank() && it != "#" }
        }
    }

    private fun Element.toAnime(): SAnime? {
        val isEpisodeCard = hasClass("se") && hasClass("episodes")
        val rawLink = if (isEpisodeCard) {
            selectFirst("div.season_m a[href]")?.attr("href")
        } else {
            selectFirst("div.data h3 a[href]")?.attr("href")
                ?: selectFirst("div.season_m a[href]")?.attr("href")
        }?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val isInternalRelative = rawLink.startsWith("/") &&
            !rawLink.startsWith("//") &&
            (rawLink.contains("/episodios/") || rawLink.contains("/tvshows/"))
        val isInternalAbsolute = rawLink.startsWith(baseUrl) &&
            (rawLink.contains("/episodios/") || rawLink.contains("/tvshows/"))
        if (!isInternalRelative && !isInternalAbsolute) return null

        val internalLink = if (rawLink.startsWith(baseUrl)) rawLink.removePrefix(baseUrl) else rawLink

        val animeTitle = if (isEpisodeCard) {
            val series = selectFirst("div.season_m a span.b")?.text()?.trim().orEmpty()
            val ep = selectFirst("div.season_m a span.c")?.text()?.trim().orEmpty()
            listOf(series, ep).filter { it.isNotBlank() }.joinToString(" - ")
                .ifBlank { selectFirst("div.data h3")?.text()?.trim().orEmpty() }
        } else {
            selectFirst("div.data h3")?.text()?.trim().orEmpty()
        }
        if (animeTitle.isBlank()) return null

        return SAnime.create().apply {
            url = baseUrl + internalLink
            title = animeTitle
            thumbnail_url = selectFirst("div.poster img")?.attr("src")
        }
    }

    private fun String.toAbsolute(): String = if (startsWith("http")) this else baseUrl + this
}
