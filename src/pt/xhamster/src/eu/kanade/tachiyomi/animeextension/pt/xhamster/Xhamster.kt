package eu.kanade.tachiyomi.animeextension.pt.xhamster

import eu.kanade.tachiyomi.animeextension.pt.xhamster.extractors.XHPlayer
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Xhamster : AnimeHttpSource() {

    override val name = "xHamster"
    override val baseUrl = "https://xhamster.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val xhPlayer by lazy { XHPlayer(client) }

    // Headers idênticos aos usados no Cloudstream para evitar bloqueios
    override val client: OkHttpClient = network.client

    private val xhHeaders: Headers = Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        add("Referer", "$baseUrl/")
    }.build()

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/newest/$page?geo=us", xhHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("div.thumb-list div.thumb-list__item").mapNotNull { it.toSAnime() }
        return AnimesPage(items, items.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/newest/$page?geo=us", xhHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search/$q/?page=$page&x_platform_switch=desktop&geo=us", xhHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    private fun Element.toSAnime(): SAnime? {
        val titleElem = this.selectFirst("a.video-thumb-info__name") ?: return null
        val title = titleElem.text().trim()
        val href = titleElem.attr("href")
        val posterUrl = this.select("img.thumb-image-container__image").attr("src").takeIf { !it.isNullOrBlank() }

        if (href.isBlank()) return null

        return SAnime.create().apply {
            url = href
            this.title = title
            thumbnail_url = posterUrl
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url + "?geo=us", xhHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.url = response.request.url.toString().substringBefore("?geo=us")

        anime.title = document.selectFirst("div.with-player-container h1")?.text()?.trim().orEmpty()
        anime.thumbnail_url = document.selectFirst("div.xp-preload-image")?.attr("style")
            ?.substringAfter("https:")?.substringBefore("');")?.let { "https:$it" }
        anime.description = document.selectFirst("div.controls-info div.ab-info p")?.text()?.trim()
        anime.genre = document.select("div[data-role='video-tags-list'] a").joinToString(", ") { it.text().trim() }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url + "?geo=us", xhHeaders)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val title = document.selectFirst("div.with-player-container h1")?.text()?.trim().orEmpty()
        val pageUrl = response.request.url.toString().substringBefore("?geo=us")

        return listOf(
            SEpisode.create().apply {
                url = pageUrl
                name = title.ifBlank { "Vídeo Completo" }
                episode_number = 1f
            },
        )
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url + "?geo=us", xhHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        return xhPlayer.videosFromPage(referer, document)
    }
}
