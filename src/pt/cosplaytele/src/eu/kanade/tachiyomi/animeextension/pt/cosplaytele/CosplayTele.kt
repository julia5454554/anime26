package eu.kanade.tachiyomi.animeextension.pt.cosplaytele

import eu.kanade.tachiyomi.animeextension.pt.cosplaytele.extractors.CossoraExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

class CosplayTele : AnimeHttpSource() {

    override val name = "CosplayTele"
    override val baseUrl = "https://cosplaytele.com"
    override val lang = "pt"
    override val supportsLatest = true

    private val cossoraExtractor by lazy { CossoraExtractor(client) }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // Aumentei o per_page para 30 para compensar os posts que serão filtrados
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/wp-json/wp/v2/posts?page=$page&per_page=30&_embed", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val json = JSONArray(response.body!!.string())
        val animes = mutableListOf<SAnime>()

        for (i in 0 until json.length()) {
            val post = json.getJSONObject(i)
            val content = post.getJSONObject("content").getString("rendered")

            // FILTRO: Só adiciona se o post tiver um vídeo
            if (content.contains("cossora.stream") || content.contains("<iframe")) {
                animes.add(post.toSAnime())
            }
        }

        // A paginação olha para o JSON bruto. Se a API retornou 30, tem próxima página.
        val hasNextPage = json.length() == 30
        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/wp-json/wp/v2/posts?search=$query&page=$page&per_page=30&_embed", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string())
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.entry-title")?.text() ?: "Sem título"
        anime.thumbnail_url = document.selectFirst("div.entry-content img")?.attr("abs:src")
        anime.description = document.selectFirst("div.entry-content p")?.text()
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create()
        episode.name = "Assistir"
        episode.url = response.request.url.toString().substringAfter(baseUrl)
        episode.episode_number = 1f
        return listOf(episode)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string(), baseUrl)
        val videos = mutableListOf<Video>()

        var iframe = document.selectFirst("iframe[src*=cossora]")
        if (iframe == null) iframe = document.selectFirst("iframe[data-src*=cossora]")
        if (iframe == null) iframe = document.selectFirst("iframe[data-lazy-src*=cossora]")

        if (iframe != null) {
            var iframeUrl = iframe.attr("src")
            if (iframeUrl.isBlank()) iframeUrl = iframe.attr("data-src")
            if (iframeUrl.isBlank()) iframeUrl = iframe.attr("data-lazy-src")

            if (iframeUrl.startsWith("//")) {
                iframeUrl = "https:$iframeUrl"
            } else if (iframeUrl.startsWith("/")) {
                iframeUrl = baseUrl + iframeUrl
            }

            videos.addAll(cossoraExtractor.videosFromUrl(iframeUrl))
        }
        return videos
    }

    private fun JSONObject.toSAnime(): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(getString("link"))

        val rawTitle = getJSONObject("title").getString("rendered")
        anime.title = Jsoup.parse(rawTitle).text()

        try {
            val featuredMedia = getJSONObject("_embedded").getJSONArray("wp:featuredmedia")
            if (featuredMedia.length() > 0) {
                anime.thumbnail_url = featuredMedia.getJSONObject(0).getString("source_url")
            }
        } catch (e: Exception) {}

        return anime
    }
}
