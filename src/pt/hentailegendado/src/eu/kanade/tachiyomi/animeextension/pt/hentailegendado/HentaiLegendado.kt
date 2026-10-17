package eu.kanade.tachiyomi.animeextension.pt.hentailegendado

import eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors.UniversalExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HentaiLegendado : AnimeHttpSource() {

    override val name = "Hentai Legendado"
    override val baseUrl = "https://hentailegendado.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val universalExtractor by lazy { UniversalExtractor(client, headers) }
    private val epNumberRegex = Regex("""Epis[oó]dio\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    private fun String.toAbsoluteUrl(): String = if (this.startsWith("/")) "$baseUrl$this" else this

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-hentai/page/$page/")
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val items = doc.select("#archive-content article.item, .items article.item").mapNotNull { it.toSAnimeStandard() }
        return AnimesPage(items, hasNextPage(doc, response.currentPage()))
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/assistir/page/$page/")
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val items = doc.select("#archive-content article.item, .items article.item").mapNotNull { it.toSAnimeStandard() }
        return AnimesPage(items, hasNextPage(doc, response.currentPage()))
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/?s=$q")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val items = doc.select(".result-item article").mapNotNull { it.toSAnimeSearch() }
        return AnimesPage(items, false)
    }

    private fun Response.currentPage(): Int {
        val segs = request.url.pathSegments
        val idx = segs.indexOf("page")
        return if (idx >= 0 && idx + 1 < segs.size) segs[idx + 1].toIntOrNull() ?: 1 else 1
    }

    private fun hasNextPage(doc: org.jsoup.nodes.Document, page: Int): Boolean = doc.select("div.pagination a[href*='/page/']").any { a ->
        val num = a.attr("href").substringAfterLast("/page/").substringBefore("/").toIntOrNull()
        num != null && num > page
    }

    private fun Element.toSAnimeStandard(): SAnime? {
        val link = selectFirst("div.data h3 a") ?: selectFirst("div.episodiotitle a") ?: return null
        val rawHref = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = rawHref.toAbsoluteUrl()

        if (!href.contains("/anime-hentai/") && !href.contains("/hentai/") && !href.contains("/assistir/")) return null

        val title = link.text().trim().ifBlank { selectFirst("div.poster img")?.attr("alt").orEmpty() }
        val img = selectFirst("div.poster img") ?: selectFirst("div.image img")
        val rawThumb = img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src").orEmpty()

        return SAnime.create().apply {
            url = href
            this.title = title
            thumbnail_url = rawThumb.toAbsoluteUrl()
        }
    }

    private fun Element.toSAnimeSearch(): SAnime? {
        val link = selectFirst(".details .title a") ?: return null
        val rawHref = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = rawHref.toAbsoluteUrl()

        val img = selectFirst(".image img")
        val rawThumb = img?.attr("src")?.takeIf { it.isNotBlank() } ?: img?.attr("data-lazy-src").orEmpty()

        return SAnime.create().apply {
            url = href
            title = link.text().trim()
            thumbnail_url = rawThumb.toAbsoluteUrl()
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val isEpisodePage = response.request.url.toString().contains("/assistir/")

        return SAnime.create().apply {
            title = doc.selectFirst("h1:not(.text)")?.text()?.trim().orEmpty()

            val img = doc.selectFirst("img[itemprop=image]")
            val rawThumb = img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src").orEmpty()
            thumbnail_url = rawThumb.toAbsoluteUrl()

            genre = if (!isEpisodePage) {
                doc.select("a[rel=tag]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            } else {
                ""
            }

            val paragraphs = doc.select("div.wp-content p").map { it.text().trim() }
                .filter { it.isNotBlank() && it != "&nbsp;" && it.length > 15 }
            description = paragraphs.firstOrNull() ?: ""

            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val urlStr = response.request.url.toString()
        val doc = Jsoup.parse(response.body?.string().orEmpty(), urlStr)

        if (urlStr.contains("/assistir/")) {
            return listOf(
                SEpisode.create().apply {
                    url = urlStr
                    name = "Assistir Online"
                    episode_number = 1f
                },
            )
        }

        val eps = doc.select("ul.episodios li").mapNotNull { li ->
            val a = li.selectFirst("div.episodiotitle a") ?: return@mapNotNull null
            val rawPageUrl = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pageUrl = rawPageUrl.toAbsoluteUrl()

            val epTitle = a.text().trim()
            val epNum = epNumberRegex.find(epTitle)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            SEpisode.create().apply {
                url = pageUrl
                name = epTitle
                episode_number = epNum
            }
        }

        if (eps.isNotEmpty()) return eps.reversed()

        val btn = doc.selectFirst("a.su-button[href*=/assistir/]")
        if (btn != null) {
            val href = btn.attr("href").toAbsoluteUrl()
            if (href.isNotBlank()) {
                return listOf(
                    SEpisode.create().apply {
                        url = href
                        name = "Filme Completo"
                        episode_number = 1f
                    },
                )
            }
        }
        return emptyList()
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url)

    override fun videoListParse(response: Response): List<Video> = runBlocking {
        val bodyString = response.body?.string().orEmpty()
        val doc = Jsoup.parse(bodyString, response.request.url.toString())

        val options = doc.select("li.dooplay_player_option")
        if (options.isEmpty()) return@runBlocking emptyList()

        val postId = options.firstOrNull()?.attr("data-post").orEmpty()
        if (postId.isBlank()) return@runBlocking emptyList()

        val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
        val numes = options.map { it.attr("data-nume").ifBlank { "1" } }.distinct()

        for (nume in numes) {
            val formBody = FormBody.Builder()
                .add("action", "doo_player_ajax")
                .add("post", postId)
                .add("nume", nume)
                .add("type", "tv")
                .build()

            val ajaxRequest = Request.Builder()
                .url(ajaxUrl)
                .post(formBody)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", response.request.url.toString())
                .build()

            val ajaxResponse = client.newCall(ajaxRequest).execute()
            val ajaxString = ajaxResponse.body?.string() ?: ""

            val regex = Regex("\"embed_url\"\\s*:\\s*\"([^\"]+)\"")
            val embedUrl = regex.find(ajaxString)?.groupValues?.get(1)?.replace("\\/", "/")

            if (!embedUrl.isNullOrBlank()) {
                val videos = try {
                    universalExtractor.videosFromUrl(embedUrl)
                } catch (e: Exception) {
                    emptyList()
                }

                if (videos.isNotEmpty()) {
                    return@runBlocking videos
                }
            }
        }

        emptyList()
    }
}
