package eu.kanade.tachiyomi.animeextension.pt.pornocarioca

import eu.kanade.tachiyomi.animeextension.pt.pornocarioca.extractors.PornoCariocaExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PornoCarioca : AnimeHttpSource() {

    override val name = "PornoCarioca"
    override val baseUrl = "https://www.pornocarioca.com"
    override val lang = "pt"
    override val supportsLatest = true

    // Cookies de verificação de idade (nomes comuns; troque pelo real quando descobrir)
    private val ageCookies = listOf(
        "age_verified=1",
        "age_gate=1",
        "over18=1",
        "is_adult=1",
        "adult=1",
        "ageVerified=true",
    ).joinToString("; ")

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                if (req.url.host.endsWith("pornocarioca.com")) {
                    val existing = req.header("Cookie").orEmpty()
                    val merged = if (existing.isBlank()) ageCookies else "$existing; $ageCookies"
                    chain.proceed(req.newBuilder().header("Cookie", merged).build())
                } else {
                    chain.proceed(req)
                }
            }
            .build()
    }

    private val extractor by lazy { PornoCariocaExtractor(client, headers) }

    private val extraHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .build()
    }

    // ============================== HELPERS ==============================

    private fun Response.safeBody(): String = this.body?.string().orEmpty()

    private fun Response.asJsoupSafe(): Document = Jsoup.parse(safeBody(), this.request.url.toString())

    private fun toRelative(href: String): String? = when {
        href.startsWith(baseUrl) -> href.removePrefix(baseUrl).ifBlank { "/" }
        href.startsWith("/") -> href
        else -> null
    }

    // ============================== REQUESTS ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/videos-mais-visitados/"
        } else {
            "$baseUrl/videos-mais-visitados/page/$page/"
        }
        return GET(url, extraHeaders)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, extraHeaders)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$baseUrl/?s=$q" else "$baseUrl/page/$page/?s=$q"
        return GET(url, extraHeaders)
    }

    // ============================== PARSERS ==============================

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = response.asJsoupSafe().toAnimesPage()
        if (page.animes.isNotEmpty()) return page

        // Fallback: se a página de mais visitados vier vazia, usa a home
        return try {
            client.newCall(GET(baseUrl, extraHeaders)).execute().use {
                it.asJsoupSafe().toAnimesPage()
            }
        } catch (e: Exception) {
            page
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = response.asJsoupSafe().toAnimesPage()

    override fun searchAnimeParse(response: Response): AnimesPage = response.asJsoupSafe().toAnimesPage()

    private fun Document.toAnimesPage(): AnimesPage {
        val cards = select("div.video[itemscope][itemtype*=CreativeWork]")
            .ifEmpty { select("div.video") }
            .ifEmpty { select("article:has(h2.video-titulo), div:has(> a h2.video-titulo)") }

        val items = cards.mapNotNull { it.toSAnime() }.distinctBy { it.url }
        val hasNext = selectFirst("li.pagination-next a[href], a.next, a[rel=next]") != null
        return AnimesPage(items, hasNext)
    }

    private fun Element.toSAnime(): SAnime? {
        val link = this.selectFirst("a[itemprop=URL]") ?: this.selectFirst("a[href]") ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val relativeUrl = toRelative(href) ?: return null

        val title = this.selectFirst("h2.video-titulo[itemprop=headline]")?.text()?.trim()
            ?: this.selectFirst("h2.video-titulo")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        if (title.isBlank()) return null

        val img = this.selectFirst("img[itemprop=image]") ?: this.selectFirst(".thumb img")
        val poster = img?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("//")) "https:$it" else it }

        return SAnime.create().apply {
            url = relativeUrl
            this.title = title
            thumbnail_url = poster
        }
    }

    // ============================== DETAILS ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoupSafe()
        return SAnime.create().apply {
            url = response.request.url.encodedPath
            title = doc.selectFirst("h1.post-titulo")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
                ?: ""

            description = doc.selectFirst("div.pc-comments-tabs__panel[data-panel=\"description\"]")
                ?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")

            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            genre = doc.select("ul.post-categories a, div.post-tags-v2 a")
                .joinToString(", ") { it.text().trim() }
                .takeIf { it.isNotBlank() }
            status = SAnime.UNKNOWN
        }
    }

    // ============================== EPISODES ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoupSafe()
        val title = doc.selectFirst("h1.post-titulo")?.text()?.trim().orEmpty()
        val duration = doc.selectFirst("meta[itemprop=duration]")?.attr("content")

        return listOf(
            SEpisode.create().apply {
                url = response.request.url.encodedPath
                name = buildString {
                    if (title.isNotBlank()) append(title)
                    if (!duration.isNullOrBlank()) append(" [$duration]")
                }.ifBlank { "Vídeo Completo" }
                episode_number = 1f
            },
        )
    }

    // ============================== VIDEOS ==============================

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, extraHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoupSafe()
        val referer = response.request.url.toString()
        return extractor.videosFromPage(referer, doc)
    }
}
