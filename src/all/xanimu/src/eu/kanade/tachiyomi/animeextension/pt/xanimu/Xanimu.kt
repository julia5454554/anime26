// language: Kotlin, file: src/pt/xanimu/src/eu/kanade/tachiyomi/animeextension/pt/xanimu/Xanimu.kt
// *listagem/detalhe via OkHttp normal; player via ProxyServer local com Cookie/Referer*

package eu.kanade.tachiyomi.animeextension.pt.xanimu

import android.util.Log
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
import fi.iki.elonen.NanoHTTPD
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "Xanimu"

class Xanimu :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Xanimu"
    override val baseUrl = "https://xanimu.com"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val catalogBase = "$baseUrl/pt"
    private val cookieJar = XanimuCookieJar()

    override val client: OkHttpClient = network.client.newBuilder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getProxy(): ProxyServer = synchronized(PROXY_LOCK) {
        proxy?.takeIf { it.isAlive } ?: ProxyServer(client).also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            proxy = it
        }
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", cookieJar.userAgent)
        .add("Referer", "$catalogBase/")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

    // ---------- listagens ----------

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$catalogBase/" else "$catalogBase/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response) = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response) = parseListing(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$catalogBase/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("paged", page.toString())
            .build()
            .toString()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response) = parseListing(response)

    private fun parseListing(response: Response): AnimesPage {
        if (!response.isSuccessful) return AnimesPage(emptyList(), false)
        val doc = response.asJsoup()
        val list = doc.select(".video-block[data-post-id]").mapNotNull(::parseCard)
        Log.d(TAG, "parseListing: ${list.size} cards em ${response.request.url}")
        val hasNext = doc.selectFirst("a.next.page-numbers, link[rel=next]") != null ||
            doc.selectFirst(".pagination a:containsOwn(Próximo)") != null ||
            list.size >= 40
        return AnimesPage(list, hasNext)
    }

    private fun parseCard(el: Element): SAnime? {
        val link = el.selectFirst("a.infos[href]") ?: el.selectFirst("a.thumb[href]") ?: return null
        val href = link.absUrl("href").ifEmpty { link.attr("href") }
        if (!href.contains("/pt/")) return null

        val titleEl = el.selectFirst("span.title")
        val altText = el.selectFirst("img.video-img")?.attr("alt")
        val title: String = titleEl?.text()?.trim()
            ?.ifEmpty { null }
            ?: altText?.trim()?.ifEmpty { null }
            ?: return null

        val thumb: String = el.selectFirst("img.video-img")?.let {
            it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }
        }.orEmpty()

        val duration: String = el.selectFirst("span.duration")?.text()?.trim().orEmpty()
        val views: String = el.selectFirst("span.views-number")?.text()?.trim().orEmpty()

        return SAnime.create().apply {
            this.title = listOfNotNull(title, duration.ifEmpty { null }).joinToString(" - ")
            thumbnail_url = thumb
            description = views.takeIf { it.isNotEmpty() }?.let { "Views: $it" }.orEmpty()
            setUrlWithoutDomain(href)
        }
    }

    // ---------- detalhes ----------

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val videoObject = extractVideoObject(doc)

        val title: String = videoObject?.name?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: return SAnime.create()

        val description: String = videoObject?.description?.trim().orEmpty()
        val thumb: String = videoObject?.thumbnailUrl?.firstOrNull()
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()

        val tags = buildList {
            videoObject?.about?.mapNotNull { it.name }?.let { addAll(it) }
            if (isEmpty()) {
                videoObject?.keywords?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.let { addAll(it) }
            }
        }.distinct()

        val durationText = videoObject?.duration?.let(::isoDurationToReadable).orEmpty()
        val uploadDate = videoObject?.uploadDate?.substringBefore("T").orEmpty()
        val actorIds = videoObject?.actor?.mapNotNull { it.id }.orEmpty()

        return SAnime.create().apply {
            this.title = title
            this.description = buildString {
                if (description.isNotEmpty()) appendLine(description)
                if (durationText.isNotEmpty()) appendLine("\nDuração: $durationText")
                if (uploadDate.isNotEmpty()) appendLine("Publicado: $uploadDate")
                if (tags.isNotEmpty()) appendLine("Tags: ${tags.joinToString(", ")}")
            }.trim()
            thumbnail_url = thumb
            genre = tags.joinToString(", ")
            artist = actorIds.mapNotNull { id ->
                Regex("""/actor/\d+-([^/]+)/""").find(id)?.groupValues?.get(1)
            }.joinToString(", ")
            status = SAnime.COMPLETED
            setUrlWithoutDomain(response.request.url.toString())
        }
    }

    @Serializable
    private data class LdGraph(val graph: List<LdNode> = emptyList())

    @Serializable
    private data class LdNode(
        @SerialName("@type") val type: String? = null,
        val name: String? = null,
        val description: String? = null,
        val thumbnailUrl: List<String>? = null,
        val contentUrl: String? = null,
        val embedUrl: String? = null,
        val duration: String? = null,
        val uploadDate: String? = null,
        val keywords: String? = null,
        val about: List<LdTerm>? = null,
        val actor: List<LdActor>? = null,
    )

    @Serializable private data class LdTerm(val name: String? = null)

    @Serializable private data class LdActor(@SerialName("@id") val id: String? = null)

    private fun extractVideoObject(doc: Document): LdNode? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = script.data().ifEmpty { script.html() }
            if (raw.isBlank()) continue
            val graph = runCatching { raw.parseAs<LdGraph>() }.getOrNull() ?: continue
            graph.graph.firstOrNull { it.type == "VideoObject" }?.let { return it }
        }
        return null
    }

    private fun isoDurationToReadable(iso: String): String {
        val m = Regex("""P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(iso) ?: return iso
        val d = m.groupValues[1].toIntOrNull() ?: 0
        val h = m.groupValues[2].toIntOrNull() ?: 0
        val mi = m.groupValues[3].toIntOrNull() ?: 0
        return buildList {
            if (d > 0) add("${d}d")
            if (h > 0) add("${h}h")
            if (mi > 0) add("${mi}min")
        }.joinToString(" ").ifEmpty { iso }
    }

    // ---------- episódio único ----------

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = response.request.url.toString()
            name = "Assistir vídeo"
            episode_number = 1f
        },
    )

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    // ---------- player ----------

    private val videoUrlRegex = Regex(
        """https://xcdn1\.nosofiles\.com/[a-z0-9/]+/output_[a-f0-9-]+_(high|low)\.mp4\?verify=[^"'\s]+""",
        RegexOption.IGNORE_CASE,
    )

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()
        val html = response.body.string()
        if (html.isBlank()) {
            Log.w(TAG, "videoListParse: HTML vazio em $pageUrl")
            return emptyList()
        }

        val matches = videoUrlRegex.findAll(html).toList()
        Log.d(TAG, "videoListParse: ${matches.size} match(es) em $pageUrl")
        if (matches.isEmpty()) return emptyList()

        // *prepara o proxy com o cookie e o Referer que o CDN do Xanimu espera*
        val proxy = getProxy().apply {
            cookieHeader = cookieJar.loadForRequest("https://xcdn1.nosofiles.com/".toHttpUrl())
                .joinToString("; ") { "${it.name}=${it.value}" }
            referer = pageUrl
            userAgent = cookieJar.userAgent
        }
        val port = proxy.listeningPort

        return matches
            .map { m ->
                val upstreamUrl = m.value
                    .replace("&amp;", "&")
                    .replace("&#038;", "&")
                val quality = when (m.groupValues[1].lowercase()) {
                    "high" -> "HD (MP4)"
                    "low" -> "SD (MP4)"
                    else -> "MP4"
                }
                val encoded = URLEncoder.encode(upstreamUrl, "UTF-8")
                val localUrl = "http://127.0.0.1:$port/proxy?url=$encoded"
                Log.d(TAG, "video: $quality -> $localUrl (upstream $upstreamUrl)")
                Video(url = localUrl, quality = quality, videoUrl = localUrl)
            }
            .distinctBy { it.videoUrl }
            .sortedByDescending { if (it.quality.contains("HD")) 1 else 0 }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    companion object {
        private val PROXY_LOCK = Any()

        @Volatile
        private var proxy: ProxyServer? = null
    }
}
