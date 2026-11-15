package eu.kanade.tachiyomi.animeextension.pt.eporner

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.util.Locale

class Eporner :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Eporner"
    override val baseUrl = "https://pt.eporner.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/videos-populares/"
        } else {
            "$baseUrl/videos-populares/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/categoria/todos/"
        } else {
            "$baseUrl/categoria/todos/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return popularAnimeRequest(page)

        val searchUrl = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("q", cleanQuery)
            .build()
            .toString()

        if (page == 1) return GET(searchUrl, headers)

        // The official search redirects to the canonical tag/category URL. Resolve it first
        // so that pages after the first one use the same URL space as the website.
        val canonicalUrl = resolveSearchUrl(searchUrl)
            ?: return GET(fallbackSearchUrl(cleanQuery, page), headers)

        val resolved = canonicalUrl.toHttpUrl()
        val pagedUrl = resolved.newBuilder()
            .encodedPath("${resolved.encodedPath.trimEnd('/')}/$page/")
            .build()
            .toString()

        return GET(pagedUrl, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    private fun resolveSearchUrl(searchUrl: String): String? = runCatching {
        client.newCall(GET(searchUrl, headers)).execute().use { response ->
            if (response.isSuccessful) response.request.url.toString() else null
        }
    }.getOrNull()

    private fun fallbackSearchUrl(query: String, page: Int): String {
        val slug = query
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
        return if (page == 1) {
            "$baseUrl/tag/$slug/"
        } else {
            "$baseUrl/tag/$slug/$page/"
        }
    }

    private fun parseAnimeList(response: Response): AnimesPage {
        if (!response.isSuccessful) return AnimesPage(emptyList(), false)

        val document = response.asJsoup()
        val animeList = document.select("#vidresults .mb").mapNotNull { element ->
            val linkElement = element.selectFirst(".mbtit a[href]") ?: return@mapNotNull null
            val title = linkElement.text().trim()
            if (title.isEmpty()) return@mapNotNull null

            val imageElement = element.selectFirst("img")
            val thumbnail = imageElement?.attr("abs:data-src").orEmpty()
                .ifEmpty { imageElement?.attr("abs:src").orEmpty() }
            val duration = element.selectFirst(".mbtim")?.text()?.trim().orEmpty()
            val quality = element.selectFirst(".mvhdico span")?.text()?.trim().orEmpty()

            SAnime.create().apply {
                this.title = listOfNotNull(
                    title.takeIf { it.isNotEmpty() },
                    duration.takeIf { it.isNotEmpty() },
                    quality.takeIf { it.isNotEmpty() },
                ).joinToString(" - ")
                this.thumbnail_url = thumbnail
                setUrlWithoutDomain(linkElement.absUrl("href"))
            }
        }

        val hasNextPage = document.selectFirst("link[rel=next], a.nmnext[href]") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val jsonLd = document.selectFirst("script[type=application/ld+json]")
            ?.data()
            ?.let { parseJsonLd(it) }

        val title = document.selectFirst("#video-info h1")?.ownText()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.removeSuffix(" - EPORNER")
                ?.trim()
            ?: jsonLd?.string("name").orEmpty()

        val description = jsonLd?.string("description")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?.trim()
                .orEmpty()

        val views = (jsonLd?.get("interactionStatistic") as? JsonObject)
            ?.let { (it["userInteractionCount"] as? JsonPrimitive)?.content }

        val genres = document.select(".vit-category a, .vit-tag a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        val artists = document.select(".vit-pornstar a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        val thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotEmpty() }
            ?: jsonLd?.string("image").orEmpty()

        return SAnime.create().apply {
            this.title = title
            this.thumbnail_url = thumbnail
            this.author = document.selectFirst(".vit-uploader a")?.text()?.trim().orEmpty()
            this.artist = artists
            this.genre = genres
            this.description = listOfNotNull(
                description.takeIf { it.isNotEmpty() },
                views?.let { "Visualizações: $it" },
            ).joinToString("\n\n")
            this.status = SAnime.COMPLETED
            setUrlWithoutDomain(response.request.url.toString())
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        return listOf(
            SEpisode.create().apply {
                episode_number = 1f
                name = "Assistir vídeo"
                setUrlWithoutDomain(url)
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val playerScript = document.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("EP.video.player.hash") }
            ?: error("Não foi possível encontrar os dados do player do Eporner")

        val videoId = playerIdRegex.find(playerScript)?.groupValues?.get(1)
            ?: urlVideoIdRegex.find(response.request.url.toString())?.groupValues?.get(1)
            ?: error("Não foi possível encontrar o ID do vídeo do Eporner")
        val playerHash = playerHashRegex.find(playerScript)?.groupValues?.get(1)
            ?: error("Não foi possível encontrar o hash do vídeo do Eporner")

        val endpoint = "$baseUrl/xhr/video/$videoId".toHttpUrl().newBuilder()
            .addQueryParameter("hash", transformPlayerHash(playerHash))
            .addQueryParameter("domain", baseUrl.toHttpUrl().host)
            .addQueryParameter("pixelRatio", "1")
            .addQueryParameter("playerWidth", "1280")
            .addQueryParameter("playerHeight", "720")
            .addQueryParameter("fallback", "false")
            .addQueryParameter("embed", "false")
            .addQueryParameter("supportedFormats", "mp4")
            .addQueryParameter("_", System.currentTimeMillis().toString())
            .build()
            .toString()

        val playerJson = client.newCall(GET(endpoint, headers)).execute().use { playerResponse ->
            if (!playerResponse.isSuccessful) {
                error("O player do Eporner respondeu HTTP ${playerResponse.code}")
            }
            playerResponse.body?.string() ?: error("Resposta vazia do player do Eporner")
        }

        val player = playerJson.parseAs<PlayerResponse>(json)
        if (!player.available) return emptyList()

        return player.sources?.mp4.orEmpty().mapNotNull { (name, source) ->
            val videoUrl = source.src?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val quality = source.labelShort?.takeIf { it.isNotEmpty() } ?: name
            Video(
                url = videoUrl,
                quality = "$quality (MP4)",
                videoUrl = videoUrl,
                headers = headers,
            )
        }
            .distinctBy { it.videoUrl }
            .sortedByDescending { qualityValue(it.quality) }
    }

    private fun parseJsonLd(raw: String): JsonObject? = runCatching {
        json.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()

    private fun JsonObject.string(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return if (primitive is JsonNull) null else primitive.content
    }

    private fun transformPlayerHash(hash: String): String = hash
        .chunked(8)
        .joinToString("") { it.toLong(16).toString(36) }

    private fun qualityValue(quality: String): Int = Regex("\\d+")
        .findAll(quality)
        .mapNotNull { it.value.toIntOrNull() }
        .maxOrNull()
        ?: 0

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    @Serializable
    private data class PlayerResponse(
        val available: Boolean = false,
        val sources: PlayerSources? = null,
    )

    @Serializable
    private data class PlayerSources(
        val mp4: Map<String, PlayerSource>? = null,
    )

    @Serializable
    private data class PlayerSource(
        val labelShort: String? = null,
        val src: String? = null,
        val type: String? = null,
        @SerialName("default")
        val isDefault: Boolean = false,
    )

    companion object {
        private val playerIdRegex = Regex("""EP\.video\.player\.vid\s*=\s*['"]([^'"]+)['"]""")
        private val playerHashRegex = Regex("""EP\.video\.player\.hash\s*=\s*['"]([0-9a-fA-F]{32})['"]""")
        private val urlVideoIdRegex = Regex("""/video-([^/]+)/""")
    }
}
