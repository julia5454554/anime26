package eu.kanade.tachiyomi.animeextension.pt.superhentais

import eu.kanade.tachiyomi.animeextension.pt.superhentais.extractors.UniversalExtractor
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

class SuperHentais : AnimeHttpSource() {

    override val name = "SuperHentais"
    override val baseUrl = "https://superhentais.com.br"
    override val lang = "pt"
    override val supportsLatest = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val extractor by lazy { UniversalExtractor(client) }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", userAgent)
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    // ==================== LISTAGEM ====================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/lista-de-hentais"
        } else {
            "$baseUrl/lista-de-hentais/page/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("article.box_view.list").mapNotNull { element ->
            animeFromElement(element)
        }
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    private fun animeFromElement(element: Element): SAnime? {
        val linkElement = element.selectFirst("a[itemprop=url], h2.grid_title a, h2 a") ?: return null
        val href = linkElement.attr("abs:href").ifBlank { linkElement.attr("href") }
        if (href.isBlank()) return null

        // ⚠️ FILTRO: aceitar apenas links de ANIME, ignorando mangás e outros
        if (!href.contains("/anime-hentai/")) return null

        val title = element.selectFirst("h2.grid_title a, h2 a")?.text()?.trim()
            ?: linkElement.attr("title").trim()
            ?: element.selectFirst("h2, h3")?.text()?.trim()
            ?: ""

        if (title.isBlank()) return null

        val imageUrl = element.selectFirst("img")?.let { img ->
            img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
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

        anime.title = document.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?.removeSuffix(" Hentai Online")
            ?.removeSuffix(" Online")
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: ""

        anime.thumbnail_url = document.selectFirst("img[itemprop=image]")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""

        val sinopse = document.selectFirst(".sinop .box_content p:not(:empty)")?.text()?.trim()
        anime.description = sinopse
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: ""

        anime.genre = document.select("span[itemprop=genre] a.genero_btn")
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
        val episodes = mutableListOf<SEpisode>()

        document.select("div.epsBox.epcontent").forEachIndexed { index, element ->
            val linkElement = element.selectFirst("a[itemprop=url]") ?: return@forEachIndexed
            val href = linkElement.attr("href")
            if (href.isBlank()) return@forEachIndexed

            val epName = element.selectFirst("span[itemprop=name]")?.text()?.trim()
                ?: linkElement.attr("title").trim()
                ?: "Episódio ${index + 1}"

            val epNumber = element.attr("data-cap").toFloatOrNull()
                ?: element.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toFloatOrNull()
                ?: (index + 1).toFloat()

            val episode = SEpisode.create().apply {
                name = epName
                setUrlWithoutDomain(href)
                episode_number = epNumber
                date_upload = 0L
            }
            episodes.add(episode)
        }

        if (episodes.isEmpty()) {
            val episode = SEpisode.create().apply {
                name = document.selectFirst("h1")?.text()?.trim() ?: "Assistir"
                setUrlWithoutDomain(response.request.url.toString())
                episode_number = 1f
            }
            episodes.add(episode)
        }

        return episodes
    }

    // ==================== VÍDEO ====================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val videoList = mutableListOf<Video>()

        // -------- Estratégia principal: iframe t_param.php --------
        val iframe = document.selectFirst(
            "iframe[src*=t_param.php], iframe[src*=video-play.mp4], #playVideo iframe",
        )
        if (iframe != null) {
            val iframeUrl = iframe.attr("abs:src")
            if (iframeUrl.isNotBlank()) {
                videoList.addAll(extractor.videosFromUrl(pageUrl, iframeUrl))
            }
        }

        // -------- Fallback: tags <video>/<source> soltas --------
        document.select("video source, video[src]").forEach { element ->
            val url = element.attr("abs:src").ifBlank { element.attr("abs:data-src") }
            if (url.isNotBlank() && (url.contains(".mp4") || url.contains(".m3u8"))) {
                if (videoList.none { it.url == url }) {
                    videoList.add(createDirectVideo(url, "Vídeo"))
                }
            }
        }

        // -------- Fallback: regex em scripts --------
        val scriptContent = document.select("script").html()
        val urlRegex = """(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""".toRegex()
        urlRegex.findAll(scriptContent).forEach { match ->
            val url = match.value
            if (videoList.none { it.url == url }) {
                videoList.add(createDirectVideo(url, "Vídeo"))
            }
        }

        return videoList
    }

    private fun createDirectVideo(url: String, quality: String): Video {
        val vHeaders = headersBuilder()
            .add("Accept", "*/*")
            .build()
        return Video(url, quality, url, vHeaders)
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

        // 1) Tenta template da listagem
        var animeList = document.select("article.box_view.list").mapNotNull { animeFromElement(it) }

        // 2) Tenta article genérico
        if (animeList.isEmpty()) {
            animeList = document.select("article").mapNotNull { animeFromElement(it) }
        }

        // 3) Tenta div.grid_box
        if (animeList.isEmpty()) {
            animeList = document.select("div.grid_box").mapNotNull { element ->
                animeFromElement(element.parent() ?: element)
            }
        }

        // 4) FALLBACK NUCLEAR: qualquer <a href*='/anime-hentai/'>
        if (animeList.isEmpty()) {
            val seen = mutableSetOf<String>()
            animeList = document.select("a[href*='/anime-hentai/']")
                .mapNotNull { link ->
                    val href = link.attr("abs:href")
                    if (href.isBlank() ||
                        !href.endsWith("/") ||
                        href.contains("/category/") ||
                        href.contains("/tag/") ||
                        href.contains("/lista-de-hentais/")
                    ) {
                        return@mapNotNull null
                    }

                    if (!seen.add(href)) return@mapNotNull null

                    val title = link.text().trim()
                        .ifBlank { link.attr("title").trim() }
                        .ifBlank { return@mapNotNull null }

                    val imageUrl = link.selectFirst("img")?.let { img ->
                        img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
                    } ?: ""

                    SAnime.create().apply {
                        setUrlWithoutDomain(href)
                        this.title = title
                        this.thumbnail_url = imageUrl
                    }
                }
        }

        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        return AnimesPage(animeList, hasNextPage)
    }
}
