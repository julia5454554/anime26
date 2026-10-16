package eu.kanade.tachiyomi.animeextension.pt.pornhub

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PornHub :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    companion object {
        private const val DEBUG_TAG = "PornHubExtDebug"
    }

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "pt"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt
            .get<Application>()
            .getSharedPreferences(
                "source_$id",
                0x0000,
            )
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36",
        )
        .add(
            "Cookie",
            "accessAgeDisclaimerPH=1; " +
                "hasVisited=1; " +
                "age_verified=1",
        )
        .add(
            "Referer",
            "$baseUrl/",
        )

    override val client: okhttp3.OkHttpClient
        get() {
            return super.client
                .newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request()

                    Log.d(
                        DEBUG_TAG,
                        "-> Requisição: ${request.url}",
                    )

                    try {
                        val response = chain.proceed(request)

                        Log.d(
                            DEBUG_TAG,
                            "<- Resposta: ${response.code} " +
                                "para ${request.url}",
                        )

                        response
                    } catch (exception: Exception) {
                        Log.e(
                            DEBUG_TAG,
                            "!! Falha de rede em ${request.url}: " +
                                "${exception.javaClass.simpleName} - " +
                                exception.message,
                            exception,
                        )

                        throw exception
                    }
                }
                .build()
        }

    // -------------------------------------------------------------------------
    // POPULAR
    // -------------------------------------------------------------------------

    override fun popularAnimeRequest(page: Int): Request = GET(
        "$baseUrl/video?page=$page",
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animeList = document
            .select(
                "li[data-video-id], " +
                    "li.pcVideoListItem, " +
                    "div.ph-thumbnail-component",
            )
            .mapNotNull { element ->
                val linkElement = element
                    .selectFirst("a[href*=/view_video.php]")
                    ?: return@mapNotNull null

                val href = linkElement.attr("href")

                val videoUrl = if (href.startsWith("http")) {
                    href.substringAfter(baseUrl)
                } else {
                    href
                }

                val titleElement = element.selectFirst(
                    "a.thumbnailTitle, " +
                        "span.title, " +
                        "a[title]",
                )

                val title = titleElement
                    ?.text()
                    ?.ifEmpty {
                        titleElement.attr("title")
                    }
                    ?.ifEmpty {
                        "Vídeo"
                    }
                    ?: element
                        .selectFirst("img")
                        ?.attr("alt")
                        ?.ifEmpty {
                            "Vídeo"
                        }
                    ?: "Vídeo"

                val duration = element
                    .selectFirst(
                        "span.bgEffect.time, " +
                            "var.duration",
                    )
                    ?.text()
                    .orEmpty()

                val thumbnailUrl = element
                    .selectFirst("img")
                    ?.let { image ->
                        image.attr("abs:data-mediumthumb")
                            .ifEmpty {
                                image.attr("abs:data-src")
                            }
                            .ifEmpty {
                                image.attr("abs:data-thumb_url")
                            }
                            .ifEmpty {
                                image.attr("abs:data-poster")
                            }
                            .ifEmpty {
                                image.attr("abs:src")
                            }
                    }

                SAnime.create().apply {
                    url = videoUrl

                    this.title = if (duration.isNotEmpty()) {
                        "$title ($duration)"
                    } else {
                        title
                    }

                    thumbnail_url = thumbnailUrl
                }
            }

        val hasNextPage = document
            .select(
                "div.page_next a[href], " +
                    "li.page_next a[href]",
            )
            .isNotEmpty()

        return AnimesPage(
            animeList,
            hasNextPage,
        )
    }

    // -------------------------------------------------------------------------
    // RECENTES
    // -------------------------------------------------------------------------

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // -------------------------------------------------------------------------
    // PESQUISA
    // -------------------------------------------------------------------------

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val encodedQuery = query.replace(
            oldValue = " ",
            newValue = "+",
        )

        return GET(
            "$baseUrl/video/search?" +
                "search=$encodedQuery&page=$page",
            headers,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // -------------------------------------------------------------------------
    // DETALHES
    // -------------------------------------------------------------------------

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val title = document
            .selectFirst("h1.title span, h1.title, h1")
            ?.text()
            ?.ifEmpty {
                "Vídeo"
            }
            ?: "Vídeo"

        val uploader = document
            .selectFirst(
                "a.uploaderLink, " +
                    "div.userInfo a, " +
                    ".usernameWrap a",
            )
            ?.text()
            ?.ifEmpty {
                "Desconhecido"
            }
            ?: "Desconhecido"

        val views = document
            .selectFirst(
                "div.videoViews span.count, " +
                    "div.videoViews, " +
                    "span.views",
            )
            ?.text()
            .orEmpty()

        val thumbnailUrl = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.ifEmpty {
                null
            }
            ?: document
                .selectFirst("img.mainImage, #player img")
                ?.attr("abs:src")

        return SAnime.create().apply {
            this.title = title

            description = buildString {
                append("Uploader: ")
                append(uploader)

                if (views.isNotEmpty()) {
                    append("\nVisualizações: ")
                    append(views)
                }
            }

            thumbnail_url = thumbnailUrl
            status = SAnime.COMPLETED
        }
    }

    // -------------------------------------------------------------------------
    // EPISÓDIOS
    // -------------------------------------------------------------------------

    override fun episodeListParse(response: Response): List<SEpisode> {
        val requestUrl = response.request.url

        val relativeUrl = buildString {
            append(requestUrl.encodedPath)

            requestUrl.query?.let { query ->
                append("?")
                append(query)
            }
        }

        val episode = SEpisode.create().apply {
            url = relativeUrl
            name = "Assistir Vídeo"
            episode_number = 1f
            date_upload = System.currentTimeMillis()
        }

        return listOf(episode)
    }

    // -------------------------------------------------------------------------
    // TEMPORADAS E HOSTERS
    // -------------------------------------------------------------------------

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun hosterListParse(response: Response): List<Hoster> = listOf(
        Hoster(
            hosterName = "PornHub",
            hosterUrl = response.request.url.toString(),
            lazy = false,
        ),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        Log.d(
            DEBUG_TAG,
            "getVideoList(hoster) chamado.",
        )

        return try {
            Log.d(
                DEBUG_TAG,
                "URL do hoster: ${hoster.hosterUrl}",
            )

            val request = GET(
                hoster.hosterUrl,
                headers,
            )

            val response = client
                .newCall(request)
                .execute()

            videoListParse(
                response,
                hoster,
            )
        } catch (exception: Exception) {
            Log.e(
                DEBUG_TAG,
                "Erro em getVideoList: " +
                    "${exception.javaClass.simpleName} - " +
                    exception.message,
                exception,
            )

            throw exception
        }
    }

    // -------------------------------------------------------------------------
    // VÍDEOS
    // -------------------------------------------------------------------------

    override fun videoListParse(
        response: Response,
        hoster: Hoster,
    ): List<Video> {
        val document = response.asJsoup()

        Log.d(
            DEBUG_TAG,
            "URL da requisição: ${response.request.url}",
        )

        Log.d(
            DEBUG_TAG,
            "Status HTTP: ${response.code}",
        )

        val scriptData = document
            .select("script")
            .map { script ->
                script.data()
            }
            .firstOrNull { script ->
                script.contains("mediaDefinitions")
            }
            ?: throw Exception(
                "Nenhum script contendo mediaDefinitions foi encontrado.",
            )

        Log.d(
            DEBUG_TAG,
            "Script encontrado " +
                "(${scriptData.length} caracteres).",
        )

        val flashvarsRegex = Regex(
            pattern = """var\s+flashvars_\d+\s*=\s*(\{.*?\});""",
            options = setOf(
                RegexOption.DOT_MATCHES_ALL,
            ),
        )

        val match = flashvarsRegex.find(scriptData)
            ?: throw Exception(
                "Variável flashvars_* não encontrada.",
            )

        val jsonString = match
            .groupValues
            .getOrNull(1)
            ?.trim()
            ?: throw Exception(
                "JSON do flashvars não encontrado.",
            )

        if (!jsonString.startsWith("{")) {
            throw Exception(
                "JSON inválido extraído do script.",
            )
        }

        val json = Json {
            ignoreUnknownKeys = true
        }

        val flashvars = try {
            json
                .parseToJsonElement(jsonString)
                .jsonObject
        } catch (exception: Exception) {
            Log.e(
                DEBUG_TAG,
                "Falha ao interpretar JSON.",
                exception,
            )

            throw Exception(
                "Erro ao interpretar JSON: " +
                    exception.message,
                exception,
            )
        }

        val mediaDefinitions = flashvars["mediaDefinitions"]
            ?.jsonArray
            ?: throw Exception(
                "mediaDefinitions não encontrado no JSON.",
            )

        Log.d(
            DEBUG_TAG,
            "mediaDefinitions encontrados: " +
                mediaDefinitions.size,
        )

        val videos = mediaDefinitions
            .mapNotNull { mediaElement ->
                val mediaObject = mediaElement.jsonObject

                val videoUrl = runCatching {
                    mediaObject["videoUrl"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()

                val qualityText = runCatching {
                    mediaObject["quality"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()

                val format = runCatching {
                    mediaObject["format"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()

                Log.d(
                    DEBUG_TAG,
                    "format=$format " +
                        "quality=$qualityText " +
                        "url=${videoUrl?.take(120)}",
                )

                if (videoUrl.isNullOrBlank()) {
                    return@mapNotNull null
                }

                if (!videoUrl.startsWith("http")) {
                    return@mapNotNull null
                }

                val quality = qualityText
                    ?.toIntOrNull()
                    ?: 0

                val qualityLabel = if (quality > 0) {
                    "${quality}p"
                } else {
                    "Padrão"
                }

                ParsedVideo(
                    url = videoUrl,
                    quality = quality,
                    qualityLabel = qualityLabel,
                )
            }
            .distinctBy { video ->
                video.url
            }
            .sortedByDescending { video ->
                video.quality
            }

        Log.d(
            DEBUG_TAG,
            "Vídeos válidos encontrados: ${videos.size}",
        )

        if (videos.isEmpty()) {
            throw Exception(
                "Nenhum vídeo foi extraído de mediaDefinitions.",
            )
        }

        return videos.map { video ->
            Video(
                video.url,
                video.qualityLabel,
                video.url,
                headers,
            )
        }
    }

    // -------------------------------------------------------------------------
    // CONFIGURAÇÕES
    // -------------------------------------------------------------------------

    override fun setupPreferenceScreen(
        screen: PreferenceScreen,
    ) {
        // Nenhuma configuração adicional.
    }

    private data class ParsedVideo(
        val url: String,
        val quality: Int,
        val qualityLabel: String,
    )
}
