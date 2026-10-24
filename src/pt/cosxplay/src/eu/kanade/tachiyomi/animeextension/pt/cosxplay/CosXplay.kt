package eu.kanade.tachiyomi.animeextension.pt.cosxplay

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CosXplay : ParsedAnimeHttpSource() {

    override val name = "CosXplay"
    override val baseUrl = "https://cosxplay.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    // "identity" = servidor responde sem compressão. Assim nenhum app (Aniyomi/Anikku)
    // precisa descomprimir br/gzip, e o interceptor do Aniyomi não mexe no header.
    private val reqHeaders: Headers = headers.newBuilder()
        .set("Accept-Encoding", "identity")
        .build()

    // Timeouts para a lista nunca ficar carregando para sempre.
    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    // Um único servidor local compartilhado (antes cada instância da fonte criava um novo e nunca parava).
    private fun getProxy(): ProxyServer = synchronized(lock) {
        proxy?.takeIf { it.isAlive } ?: ProxyServer(client).also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            proxy = it
        }
    }

    // ==================== Populares ====================

    override fun popularAnimeSelector(): String = "div.video-block"

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, reqHeaders)
    }

    override fun popularAnimeFromElement(element: Element): SAnime = parseCard(element)

    // Diagnóstico: se a lista vier vazia, mostra no app o que o site realmente devolveu.
    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
        if (animes.isEmpty()) {
            throw Exception(
                "Sem itens. HTTP ${response.code}, enc=${response.header("Content-Encoding")}, " +
                    "titulo='${document.title()}', html=${document.html().take(200)}",
            )
        }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNext)
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item:last-child a.page-link"

    // ==================== Recentes ====================

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ==================== Busca ====================

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (page == 1) "$baseUrl/?s=$query" else "$baseUrl/page/$page/?s=$query"
        return GET(url, reqHeaders)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ==================== Detalhes ====================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        description = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
    }

    // ==================== Episódios ====================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val pageUrl = response.request.url.toString()
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(pageUrl.removePrefix(baseUrl))
                name = "Vídeo"
                episode_number = 1f
            },
        )
    }

    override fun episodeListSelector(): String = "article.post"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create()

    // ==================== Vídeos ====================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val port = getProxy().listeningPort
        return document.select("video source").mapNotNull { source ->
            val src = source.attr("src")
            if (src.isEmpty()) return@mapNotNull null
            val quality = source.attr("title").ifBlank { "Vídeo" }
            val encodedUrl = URLEncoder.encode(src, "UTF-8")
            val localUrl = "http://127.0.0.1:$port/proxy?url=$encodedUrl"
            Video(localUrl, quality, localUrl)
        }
    }

    override fun videoListSelector(): String = "video source"

    override fun videoFromElement(element: Element): Video = Video("", "", "")

    override fun videoUrlParse(document: Document): String = ""

    // ==================== Helpers ====================

    companion object {
        private val lock = Any()

        @Volatile
        private var proxy: ProxyServer? = null
    }

    private fun parseCard(element: Element): SAnime {
        val link = element.selectFirst("a.thumb")?.attr("href") ?: ""
        val img = element.selectFirst("img.video-img")
        val thumb = img?.attr("data-src") ?: img?.attr("src") ?: ""
        val slug = link.trimEnd('/').substringAfterLast('/')
        val title = slug.substringAfter('-', slug).replace("-", " ")
        return SAnime.create().apply {
            setUrlWithoutDomain(link)
            this.title = title
            thumbnail_url = thumb
        }
    }
}
