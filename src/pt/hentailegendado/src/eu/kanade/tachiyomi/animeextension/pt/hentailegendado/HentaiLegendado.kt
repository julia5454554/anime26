// language: Kotlin, file: HentaiLegendado.kt, target: Android / Tachiyomi anime extension (legacy API)
package eu.kanade.tachiyomi.animeextension.pt.hentailegendado

import android.util.Log
import eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HentaiLegendado : AnimeHttpSource() {

    override val name = "Hentai Legendado"
    override val baseUrl = "https://hentailegendado.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", userAgent)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")

    private val universalExtractor by lazy { UniversalExtractor(client, headers) }

    private fun log(m: String) = Log.d("HentaiLegendado", m)
    private fun String.toAbs(): String = if (startsWith("/")) "$baseUrl$this" else this

    // ==================== Popular — /hentai/ + /anime-hentai/ ====================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/hentai/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val page = currentPage(response)

        val fromHentai = extractCards(doc, listOf("/hentai/", "/anime-hentai/"))

        // segunda fonte — /anime-hentai/ (catálogo separado do tema)
        val fromAnimeHentai = try {
            val req = GET("$baseUrl/anime-hentai/page/$page/", headers)
            client.newCall(req).execute().use { r ->
                val d = Jsoup.parse(r.body?.string().orEmpty(), r.request.url.toString())
                val items = extractCards(d, listOf("/anime-hentai/", "/hentai/"))
                log("popular/anime-hentai page=$page items=${items.size} hasNext=${hasNext(d, page)}")
                items to hasNext(d, page)
            }
        } catch (e: Exception) {
            log("popular/anime-hentai fail: ${e.message}")
            emptyList<SAnime>() to false
        }

        val combined = (fromHentai + fromAnimeHentai.first).distinctBy { it.url }
        log("popular page=$page hentai=${fromHentai.size} anime-hentai=${fromAnimeHentai.first.size} total=${combined.size}")

        return AnimesPage(combined, hasNext(doc, page) || fromAnimeHentai.second)
    }

    // ==================== Recentes — /assistir/ ====================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/assistir/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val items = extractCards(doc, listOf("/assistir/"))
        log("latest page=${currentPage(response)} items=${items.size}")
        return AnimesPage(items, hasNext(doc, currentPage(response)))
    }

    // ==================== Busca ====================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) "$baseUrl/?s=$q" else "$baseUrl/page/$page/?s=$q"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, response.request.url.toString())
        val items = extractCards(doc, listOf("/assistir/", "/hentai/", "/anime-hentai/"))
        log("search page=${currentPage(response)} items=${items.size}")
        return AnimesPage(items, false)
    }

    // ==================== Card parser ====================

    private fun extractCards(doc: Document, allowedPrefixes: List<String>): List<SAnime> {
        val candidates = mutableListOf<Element>()
        candidates += doc.select("a.ce")
        candidates += doc.select(".gpost a[href]")
        candidates += doc.select("article a[href], .result-item a[href]")

        return candidates
            .filter { a ->
                val h = a.attr("href")
                h.isNotBlank() && allowedPrefixes.any { p -> h.startsWith("$baseUrl$p") }
            }
            .mapNotNull { it.toSAnime(allowedPrefixes) }
            .distinctBy { it.url }
    }

    private fun Element.toSAnime(allowedPrefixes: List<String>): SAnime? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        if (allowedPrefixes.none { href.startsWith("$baseUrl$it") }) return null

        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: attr("title").takeIf { it.isNotBlank() }
            ?: text().trim().takeIf { it.isNotBlank() && it.length < 200 }
            ?: return null

        val thumb = extractThumb(this) ?: extractThumb(closest(".gpost") ?: this)

        return SAnime.create().apply {
            url = href
            this.title = cleanTitle(title)
            thumbnail_url = thumb ?: ""
        }
    }

    private fun extractThumb(el: Element): String? {
        el.selectFirst("img")?.let { img ->
            val c = img.attr("data-lazy-src").ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("src") }
            if (c.isNotBlank()) return c.toAbs()
        }
        val style = el.attr("style") + " " + el.attr("data-bg")
        Regex("""url\(['"]?([^)'"]+)['"]?\)""").find(style)?.groupValues?.get(1)?.let { return it.toAbs() }
        return null
    }

    private val titleNoise = Regex(
        """\s*\((Sem Censura|Com Censura)\)|\s*(Dublado|Legendado)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private fun cleanTitle(t: String): String = t.replace(titleNoise, "").trim()

    // ==================== Details ====================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val thumb = extractThumb(doc.body()) ?: ""
        val title = doc.selectFirst("h1")?.text()?.trim()?.let(::cleanTitle)
            ?: response.request.url.pathSegments.lastOrNull().orEmpty()

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = thumb
            genre = doc.select("a[rel=tag], a[href*=/genero/]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && it.length < 40 && !it.startsWith("A-Z", true) }
                .distinct()
                .joinToString(", ")
            description = doc.select("div.wp-content p, .desc p, article p")
                .map { it.text().trim() }
                .firstOrNull { it.length > 30 }
                ?: ""
            status = SAnime.UNKNOWN
        }
    }

    // ==================== Episódios ====================
    // /assistir/{slug}/ = 1 ep isolado
    // /hentai/ ou /anime-hentai/{slug}/ = série; raspa a[href*=/assistir/]

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val doc = Jsoup.parse(response.body?.string().orEmpty(), url)

        if (url.contains("/assistir/")) {
            val num = extractEpisodeNumber(url) ?: 1f
            return listOf(
                SEpisode.create().apply {
                    this.url = url
                    name = if (num > 0) "Episódio ${num.toInt()}" else "Assistir Online"
                    episode_number = num
                },
            )
        }

        val eps = doc.select("a[href*=/assistir/]")
            .mapNotNull { a ->
                val href = a.attr("href").takeIf { it.startsWith("$baseUrl/assistir/") }
                    ?: return@mapNotNull null
                val num = extractEpisodeNumber(href)
                val name = if (num != null && num > 0) {
                    "Episódio ${num.toInt()}"
                } else {
                    cleanTitle(a.text().trim().ifBlank { "Episódio" })
                }
                SEpisode.create().apply {
                    this.url = href
                    this.name = name
                    episode_number = num ?: -1f
                }
            }
            .distinctBy { it.url }

        log("series eps=${eps.size} url=$url")
        if (eps.isNotEmpty()) return eps.reversed()

        val num = extractEpisodeNumber(url) ?: 1f
        return listOf(
            SEpisode.create().apply {
                this.url = url
                name = if (num > 0) "Episódio ${num.toInt()}" else "Assistir Online"
                episode_number = num
            },
        )
    }

    private val epNumRegex = Regex("""-episodio-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private fun extractEpisodeNumber(url: String): Float? = epNumRegex.find(url)?.groupValues?.get(1)?.toFloatOrNull()

    // ==================== Videos ====================
    // UniversalExtractor antigo resolve. "Direct"/"Mega" filtrado quando há alternativa.

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, response.request.url.toString())

        val iframeSrcs = doc.select("iframe[src]").mapNotNull {
            it.attr("src").takeIf { s -> s.startsWith("http") }
        }
        val rawMp4 = Regex("""https?://[^\s"'<>]+\.(mp4|m3u8)[^\s"'<>]*""")
            .findAll(body).map { it.value }.toList()

        val sources = (iframeSrcs + rawMp4).distinct()
        log("videoList: iframes=${iframeSrcs.size} rawMp4=${rawMp4.size}")

        val all = mutableListOf<Video>()
        for (src in sources) {
            try {
                all += universalExtractor.videosFromUrl(src)
            } catch (e: Exception) {
                log("extract fail $src: ${e.message}")
            }
        }

        val unique = all.distinctBy { it.url }
        val semDirect = unique.filterNot { isBrokenDirect(it) }
        val result = semDirect.ifEmpty { unique }
        log("videoList: total=${unique.size} after=${result.size} qualities=${result.map { it.quality }}")
        return result
    }

    private fun isBrokenDirect(v: Video): Boolean {
        val q = v.quality.lowercase()
        return q == "direct" || q == "mega"
    }

    // ==================== Pagination ====================

    private fun currentPage(response: Response): Int {
        val segs = response.request.url.pathSegments
        val idx = segs.indexOf("page")
        return if (idx >= 0 && idx + 1 < segs.size) segs[idx + 1].toIntOrNull() ?: 1 else 1
    }

    private fun hasNext(doc: Document, page: Int): Boolean = doc.select("nav.pag a[href*=/page/]").any { a ->
        val n = a.attr("href").substringAfterLast("/page/").substringBefore("/").toIntOrNull()
        n != null && n > page
    }
}
