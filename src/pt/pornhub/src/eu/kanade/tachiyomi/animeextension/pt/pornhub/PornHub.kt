package eu.kanade.tachiyomi.animeextension.pt.pornhub

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
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PornHub :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    companion object {
        private const val IMAGE_BASE_URL = "https://ei.phncdn.com"
        private const val IMAGE_HOST = "ei.phncdn.com"

        // Keep parsing self-contained instead of relying on the globally injected Json instance.
        private val json = Json { ignoreUnknownKeys = true }

        private val flashvarsRegex = Regex(
            """var\s+flashvars_\d+\s*=\s*(\{.*?\});""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val categoriesRegex = Regex(
            """['"]categories_in_video['"]\s*:\s*['"]([^'"]+)['"]""",
        )
        private val publishedDateRegex = Regex(
            """['"]video_date_published['"]\s*:\s*['"](\d{8})['"]""",
        )
        private val jsonLdAuthorRegex = Regex(
            """"author"\s*:\s*"([^"]+)"""",
        )
        private val stableImagePathRegex = Regex(
            """.*\.(?:jpe?g|png|webp)$""",
            RegexOption.IGNORE_CASE,
        )
        private val streamResolutionRegex = Regex(
            """/(\d{3,4})P[_\d]""",
            RegexOption.IGNORE_CASE,
        )
        private val publishedDateFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularAnimeRequest(page: Int): Request = GET(
        "$baseUrl/video?page=$page",
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animeList = document
            .select("li.pcVideoListItem[data-video-vkey], li[data-video-id][data-video-vkey]")
            .mapNotNull { element ->
                val linkElement = element
                    .selectFirst("a[href*=/view_video.php]")
                    ?: return@mapNotNull null

                val imageElement = element.selectFirst("img")
                val title = element
                    .selectFirst("span.title a[href], a.thumbnailTitle[href], a[href][title]")
                    ?.text()
                    ?.ifEmpty {
                        element.selectFirst("a[href][title]")?.attr("title").orEmpty()
                    }
                    ?.ifEmpty {
                        imageElement?.attr("alt").orEmpty()
                    }
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val duration = element
                    .selectFirst("var.duration, span.bgEffect.time")
                    ?.text()
                    .orEmpty()

                SAnime.create().apply {
                    setUrlWithoutDomain(linkElement.absUrl("href"))
                    this.title = if (duration.isNotEmpty()) {
                        "$title ($duration)"
                    } else {
                        title
                    }
                    thumbnail_url = imageElement?.let(::extractListingThumbnail)
                }
            }

        val hasNextPage = document.selectFirst("div.page_next a[href], li.page_next a[href]") != null

        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val url = "$baseUrl/video/search"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("search", query.trim())
            .addQueryParameter("page", page.toString())
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val flashvars = parseFlashvars(document)
        val title = extractTitle(document)
        val uploader = extractUploader(document)
        val views = document
            .selectFirst("div.ratingInfo div.views span.count")
            ?.text()
            .orEmpty()

        return SAnime.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            this.title = title
            author = uploader
            artist = uploader
            thumbnail_url = extractDetailThumbnail(document, flashvars)
            genre = extractCategories(document)?.joinToString()
            description = buildString {
                append("Uploader: ")
                append(uploader ?: "Desconhecido")

                if (views.isNotEmpty()) {
                    append("\nVisualizações: ")
                    append(views)
                }
            }
            status = SAnime.COMPLETED
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        val episode = SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Assistir Vídeo"
            episode_number = 1f
            date_upload = extractPublishedDate(document)
        }

        return listOf(episode)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val flashvars = parseFlashvars(document)

        val remoteVideos = extractRemoteMediaVideos(flashvars, response)
        val directVideos = extractDirectVideos(document)
        val hlsVideos = extractHlsVideos(flashvars)

        return (remoteVideos + directVideos + hlsVideos)
            .distinctBy { video ->
                videoResolution(video)?.let { "quality:$it" } ?: video.videoUrl
            }
            .sortedByDescending { videoResolution(it) ?: 0 }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    private fun extractListingThumbnail(image: Element): String? {
        stableThumbnailUrl(image.attr("data-path"))?.let { return it }

        return image.attr("abs:data-image")
            .ifEmpty { image.attr("abs:data-mediumthumb") }
            .ifEmpty { image.attr("abs:data-thumb_url") }
            .ifEmpty { image.attr("abs:data-src") }
            .ifEmpty { image.attr("abs:src") }
            .takeIf { it.isNotEmpty() }
    }

    private fun stableThumbnailUrl(dataPath: String?): String? {
        if (dataPath.isNullOrBlank()) return null

        val normalized = dataPath.replace("{index}", "0")
        val absoluteUrl = when {
            normalized.startsWith("https://") -> normalized
            normalized.startsWith("//") -> "https:$normalized"
            normalized.startsWith("/") -> "$IMAGE_BASE_URL$normalized"
            else -> return null
        }

        val parsedUrl = absoluteUrl.toHttpUrlOrNull() ?: return null
        if (parsedUrl.host != IMAGE_HOST) return null

        val sourcePath = parsedUrl.encodedPath.substringBefore("/plain/")
        if (!stableImagePathRegex.matches(sourcePath)) return null

        return parsedUrl
            .newBuilder()
            .encodedPath(sourcePath)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun extractDetailThumbnail(document: Document, flashvars: Flashvars?): String? = document.selectFirst("meta[name=twitter:image]")?.attr("content")?.takeIf { it.isNotEmpty() }
        ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotEmpty() }
        ?: flashvars?.imageUrl

    private fun extractRemoteMediaVideos(flashvars: Flashvars?, pageResponse: Response): List<Video> {
        val mediaEndpoint = flashvars
            ?.mediaDefinitions
            .orEmpty()
            .firstOrNull { definition ->
                definition.format.equals("mp4", ignoreCase = true) &&
                    definition.videoUrl?.contains("/video/get_media") == true
            }
            ?.videoUrl
            ?: return emptyList()

        return runCatching {
            val endpointUrl = mediaEndpoint.toHttpUrl()
            val requestHeaders = headers.newBuilder()
                .apply {
                    val sessionCookies = buildList {
                        addAll(runCatching { Cookie.parseAll(endpointUrl, pageResponse.headers) }.getOrDefault(emptyList()))
                        addAll(client.cookieJar.loadForRequest(endpointUrl))
                    }
                        .filter { it.value.isNotEmpty() }
                        .distinctBy { it.name }

                    if (sessionCookies.isNotEmpty()) {
                        set("Cookie", sessionCookies.joinToString("; ") { "${it.name}=${it.value}" })
                    }
                }
                .build()

            client.newCall(GET(mediaEndpoint, requestHeaders)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()

                response
                    .parseAs<List<MediaDefinition>>(json)
                    .mapNotNull { definition ->
                        val url = definition.videoUrl?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                        if (!url.substringBefore('?').endsWith(".mp4", ignoreCase = true)) return@mapNotNull null

                        val resolution = definition.resolution
                            ?: streamResolutionRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: return@mapNotNull null

                        Video(
                            url = url,
                            quality = "${resolution}p (MP4)",
                            videoUrl = url,
                            headers = headers,
                        )
                    }
            }
        }.getOrDefault(emptyList())
    }

    private fun extractDirectVideos(document: Document): List<Video> {
        val urls = document
            .select("meta[property=og:video], meta[name=twitter:player:stream]")
            .map { it.attr("content").trim() }
            .filter { url ->
                url.isNotEmpty() && url.substringBefore('?').endsWith(".mp4", ignoreCase = true)
            }
            .distinct()

        return urls.map { url ->
            val resolution = streamResolutionRegex
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            Video(
                url = url,
                quality = if (resolution != null) "${resolution}p (MP4)" else "MP4 (direto)",
                videoUrl = url,
                headers = headers,
            )
        }
    }

    private fun videoResolution(video: Video): Int? = video.quality
        .filter { it.isDigit() }
        .toIntOrNull()

    private val MediaDefinition.resolution: Int?
        get() = (quality as? JsonPrimitive)?.content?.toIntOrNull() ?: height

    private fun extractHlsVideos(flashvars: Flashvars?): List<Video> {
        return flashvars
            ?.mediaDefinitions
            .orEmpty()
            .mapNotNull { definition ->
                val url = definition.videoUrl?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val isHls = definition.format.equals("hls", ignoreCase = true) ||
                    url.contains(".m3u8", ignoreCase = true)
                if (!isHls) return@mapNotNull null

                val resolution = definition.resolution
                    ?: streamResolutionRegex.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

                Video(
                    url = url,
                    quality = if (resolution != null) "${resolution}p (HLS)" else "HLS",
                    videoUrl = url,
                    headers = headers,
                )
            }
            .distinctBy { it.videoUrl }
            .sortedByDescending { video ->
                video.quality.filter { it.isDigit() }.toIntOrNull() ?: 0
            }
    }

    private fun parseFlashvars(document: Document): Flashvars? = document
        .select("script")
        .asSequence()
        .mapNotNull { script ->
            flashvarsRegex.find(script.data())?.groupValues?.getOrNull(1)
        }
        .mapNotNull { jsonString ->
            runCatching { jsonString.parseAs<Flashvars>(json) }.getOrNull()
        }
        .firstOrNull()

    private fun extractTitle(document: Document): String = document.selectFirst("h1.title span.inlineFree")?.text()
        ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ?: error("Título do vídeo não encontrado")

    private fun extractUploader(document: Document): String? {
        val jsonLd = document.selectFirst("script[type=application/ld+json]")?.data().orEmpty()
        return jsonLdAuthorRegex.find(jsonLd)?.groupValues?.getOrNull(1)
    }

    private fun extractCategories(document: Document): List<String>? {
        val script = document
            .select("script")
            .asSequence()
            .map { it.data() }
            .firstOrNull { it.contains("categories_in_video") }
            ?: return null

        val categories = categoriesRegex
            .find(script)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        return categories
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun extractPublishedDate(document: Document): Long {
        val script = document
            .select("script")
            .asSequence()
            .map { it.data() }
            .firstOrNull { it.contains("video_date_published") }
            ?: return 0L

        val date = publishedDateRegex.find(script)?.groupValues?.getOrNull(1)
        return publishedDateFormat.tryParse(date)
    }

    @Serializable
    private class Flashvars(
        val mediaDefinitions: List<MediaDefinition> = emptyList(),
        @SerialName("image_url")
        val imageUrl: String? = null,
    )

    @Serializable
    private class MediaDefinition(
        val format: String? = null,
        val quality: JsonElement? = null,
        val videoUrl: String? = null,
        val height: Int? = null,
    )
}
