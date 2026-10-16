package eu.kanade.tachiyomi.animeextension.pt.javrider

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class JavRider : AnimeHttpSource() {

    override val name = "JavRider"
    override val baseUrl = "https://javrider.com"
    override val lang = "pt"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/wp-json/wp/v2"
    private val embedParam = "wp:featuredmedia"

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .add("Referer", "$baseUrl/pt/")
            .build()
    }

    // ==================== HELPERS ====================

    private fun safeBody(response: Response): String = try {
        response.body?.string().orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun safeDocument(response: Response): Document = Jsoup.parse(safeBody(response), response.request.url.toString())

    private fun extractSlug(url: String): String {
        val cleaned = url.trim().trimEnd('/').substringBefore("?").substringBefore("#")
        return cleaned.substringAfterLast("/")
    }

    private fun buildUrl(slug: String): String = "$baseUrl/pt/$slug/"

    private fun resolveUrl(rawUrl: String): String {
        val slug = extractSlug(rawUrl)
        return if (slug.isNotEmpty() && !slug.contains(":")) buildUrl(slug) else rawUrl
    }

    private fun responseUrlOrDefault(response: Response): String {
        val fromResponse = response.request.url.toString()
        return if (fromResponse.startsWith("http")) fromResponse else baseUrl
    }

    // ==================== LISTAGEM ====================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/posts?per_page=24&page=$page&_embed=$embedParam&lang=pt"
        return GET(url, apiHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parsePosts(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/posts?per_page=24&page=$page&orderby=date&order=desc" +
            "&_embed=$embedParam&lang=pt"
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parsePosts(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/posts?search=$q&per_page=24&page=$page&_embed=$embedParam&lang=pt"
        return GET(url, apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parsePosts(response)

    private fun parsePosts(response: Response): AnimesPage {
        val body = safeBody(response)
        if (body.isBlank()) return AnimesPage(emptyList(), false)
        return try {
            val json = JSONArray(body)
            val list = ArrayList<SAnime>(json.length())
            for (i in 0 until json.length()) {
                val post = json.getJSONObject(i)
                val link = post.optString("link", "")

                // Filtro extremo: Se a url não for da versão em português, descartar
                if (!link.contains("/pt/")) continue

                val slug = post.optString("slug", "")
                val realUrl = if (slug.isNotEmpty()) buildUrl(slug) else link
                val thumb = post.optJSONObject("_embedded")
                    ?.optJSONArray("wp:featuredmedia")
                    ?.optJSONObject(0)
                    ?.optString("source_url")
                    ?.takeIf { it.startsWith("http") }
                val anime = SAnime.create().apply {
                    title = post.getJSONObject("title").getString("rendered")
                        .replace(Regex("<[^>]+>"), "").trim()
                    url = realUrl
                    thumbnail_url = thumb
                }
                if (realUrl.isNotEmpty()) list.add(anime)
            }
            AnimesPage(list, json.length() == 24)
        } catch (_: Exception) {
            AnimesPage(emptyList(), false)
        }
    }

    // ==================== DETALHES ====================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(resolveUrl(anime.url), apiHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        try {
            val document = safeDocument(response)
            anime.title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            anime.thumbnail_url = document.selectFirst("meta[property=og:image]")
                ?.attr("content")?.takeIf { it.startsWith("http") }
            anime.genre = document.select("a.category-item").joinToString(", ") { it.text() }
                .takeIf { it.isNotEmpty() }
            val content = document.selectFirst("div.entry-content")
            if (content != null) {
                content.select("div.code-block, script, style").remove()
                anime.description = content.text().trim().take(1500).takeIf { it.isNotEmpty() }
            }
            anime.author = document.selectFirst("li:contains(Estúdio) a")?.text()
            anime.status = SAnime.COMPLETED
        } catch (_: Exception) {
            // retorna anime vazio mas válido
        }
        if (anime.title.isNullOrBlank()) anime.title = "JavRider"
        return anime
    }

    // ==================== EPISÓDIOS ====================

    override fun episodeListRequest(anime: SAnime): Request = GET(resolveUrl(anime.url), apiHeaders)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = responseUrlOrDefault(response)
        val ep = SEpisode.create().apply {
            name = "Vídeo Completo"
            this.url = url
            episode_number = 1f
        }
        return listOf(ep)
    }

    // ==================== VÍDEO ====================

    override fun videoListRequest(episode: SEpisode): Request = GET(resolveUrl(episode.url), apiHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val videos = mutableListOf<Video>()
        val referer = responseUrlOrDefault(response)
        try {
            val document = safeDocument(response)

            // Tracks extras direto da pagina html (se existirem)
            val pageTracks = mutableListOf<Track>()
            document.select("video track, track").forEach { el ->
                // Adicionada limpeza preventiva para evitar links concatenados vindos do HTML
                val src = el.attr("src").ifEmpty { el.attr("data-src") }
                    .substringBefore(",")
                    .substringBefore("[")
                if (src.startsWith("http") && pageTracks.none { it.url == src }) {
                    pageTracks.add(Track(src, el.attr("label").ifEmpty { "Legenda PT" }))
                }
            }

            val iframe = document.selectFirst("div.player-3rdparty iframe, iframe[src*=javplayers]")
                ?: document.selectFirst("iframe[src]")
            val iframeSrc = iframe?.let {
                it.attr("src").ifEmpty { it.attr("data-lazy-src") }
            }.orEmpty()

            if (iframeSrc.startsWith("http")) {
                videos.addAll(extractFromPlayer(iframeSrc, referer, pageTracks))
            }

            document.select("video, video source").forEach { el ->
                val src = el.attr("src").ifEmpty { el.attr("data-src") }
                if (src.startsWith("http") && videos.none { it.url == src }) {
                    videos.add(Video(src, "Direto", src, videoHeadersFor(referer), subtitleTracks = pageTracks))
                }
            }
        } catch (_: Exception) {
            // Log removido para a versão final de release
        }
        return videos
    }

    private fun videoHeadersFor(referer: String): Headers = Headers.Builder()
        .add("User-Agent", desktopUa)
        .add("Referer", referer)
        .add("Origin", "https://javplayers.com")
        .add("Accept", "*/*")
        .add("Accept-Encoding", "identity")
        .build()

    private fun extractFromPlayer(iframeUrl: String, referer: String, extraTracks: List<Track> = emptyList()): List<Video> {
        val videos = mutableListOf<Video>()
        val hash = Regex("""/video/([a-f0-9]{16,})""")
            .find(iframeUrl)?.groupValues?.get(1)
            ?: return videos

        var iframeHtml = ""
        // Passo 1: visitar a página do iframe (pega cookies + HTML para buscar tags de legenda ocultas)
        try {
            val pageReq = Request.Builder()
                .url(iframeUrl)
                .headers(playerPageHeaders(referer))
                .build()
            iframeHtml = client.newCall(pageReq).execute().use { it.body?.string().orEmpty() }
        } catch (_: Exception) {
            // Log removido para a versão final de release
        }

        val apiUrl = "https://javplayers.com/player/index.php?data=$hash&do=getVideo"

        // Passo 2: GET JSON
        var body = tryApiGet(apiUrl, iframeUrl)

        // Passo 3: se veio HTML curto/vazio, tenta POST JSON
        if (body.length < 500 || !body.contains("securedLink", true)) {
            val postBody = "data=$hash&do=getVideo"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val postResp = tryApiPost(apiUrl, iframeUrl, postBody)
            if (postResp.contains("securedLink", true) || postResp.length > body.length) {
                body = postResp
            }
        }

        val normalized = body.replace("\\/", "/").replace("\\u002F", "/")
        var secured: String? = null
        var source: String? = null
        val tracks = mutableListOf<Track>()
        tracks.addAll(extraTracks)

        val textToSearch = iframeHtml + "\n" + normalized

        // =========================================================================
        // EXTRAÇÃO DE LEGENDAS - CAMADA 1: Foco exclusivo na legenda PT (Correção mpv)
        // =========================================================================

        // Esta Regex procura a tag PT e garante que a extração para imediatamente
        // caso encontre vírgulas ou novos colchetes (ex: ,[EN]), resolvendo o bug do mpv.
        val regexPt = Regex("""\[(?:PT|PT-BR|pt|pt-br|Português)\](https?://[^,\[\]"'\s<>]+?\.(?:srt|vtt))""", RegexOption.IGNORE_CASE)
        var ptEncontrada = false

        regexPt.findAll(textToSearch).forEach { match ->
            val subUrl = match.groupValues[1].trim()
            if (tracks.none { it.url == subUrl }) {
                tracks.add(Track(subUrl, "Português (PT)"))
                ptEncontrada = true
            }
        }

        // Caso a legenda não tenha a marcação [PT] na frente, faz a busca genérica,
        // mas AGORA com a barreira [^,\[\]] para nunca juntar ficheiros diferentes.
        if (!ptEncontrada) {
            val srtRegex = Regex("""(https?://[^,\[\]"'\s<>]+?\.(?:srt|vtt))""")
            srtRegex.findAll(textToSearch).forEach { match ->
                val subUrl = match.groupValues[1].trim()
                if (tracks.none { it.url == subUrl }) {
                    tracks.add(Track(subUrl, "Legenda PT"))
                }
            }
        }

        // =========================================================================
        // Extração de Legendas - Camada 2: Tags HTML <track> nativas ocultas
        // =========================================================================
        val trackTagRegex = Regex("""<track[^>]+src=["'](https?://[^"']+)["'][^>]*>""")
        trackTagRegex.findAll(iframeHtml).forEach { match ->
            // Limpeza extra para evitar sujidade no HTML
            val file = match.groupValues[1].replace("&amp;", "&").substringBefore(",").substringBefore("[")
            if (tracks.none { it.url == file }) {
                tracks.add(Track(file, "Legenda PT (HTML)"))
            }
        }

        // =========================================================================
        // Extração de Legendas - Camada 3: Parse do JSON puro
        // =========================================================================
        try {
            val json = JSONObject(normalized)
            secured = json.optString("securedLink", "").takeIf { it.startsWith("http") }
            source = json.optString("videoSource", "").takeIf { it.startsWith("http") }

            val subtitles = json.optJSONArray("subtitles") ?: json.optJSONArray("captions")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val subObj = subtitles.getJSONObject(i)
                    // Limpeza preventiva extraindo até à primeira vírgula (se houver lixo)
                    val file = subObj.optString("file").replace("\\/", "/").substringBefore(",").substringBefore("[")
                    val label = subObj.optString("label", "Português (PT)")

                    if (file.startsWith("http") && tracks.none { it.url == file }) {
                        // Como pediu apenas PT, filtramos pela label ou adicionamos se for a única opção
                        if (label.contains("pt", ignoreCase = true) || label.contains("português", ignoreCase = true) || tracks.isEmpty()) {
                            tracks.add(Track(file, "Português (PT)"))
                        }
                    }
                }
            } else {
                val subStr = json.optString("subtitle", "").ifEmpty { json.optString("subtitles", "") }
                val cleanSubStr = subStr.replace("\\/", "/").substringBefore(",").substringBefore("[")
                if (cleanSubStr.startsWith("http") && tracks.none { it.url == cleanSubStr }) {
                    tracks.add(Track(cleanSubStr, "Português (PT)"))
                }
            }
        } catch (_: Exception) {
            // Não falha caso não seja JSON
        }

        // =========================================================================
        // Recuperar as streams de Vídeo
        // =========================================================================
        if (secured == null) {
            secured = Regex(""""securedLink"\s*:\s*"([^"]+)"""")
                .find(normalized)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }
        }
        if (source == null) {
            source = Regex(""""videoSource"\s*:\s*"([^"]+)"""")
                .find(normalized)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }
        }
        if (secured == null) {
            secured = Regex("""(https?://javplayers\.com/m3/[A-Za-z0-9+/=%]+)""")
                .find(normalized)?.groupValues?.get(1)
        }
        if (secured == null) {
            secured = Regex("""["']([^"']*master\.m3u8[^"']*)["']""")
                .find(normalized)?.groupValues?.get(1)
                ?.let { if (it.startsWith("http")) it else "https://javplayers.com$it" }
        }

        // Montagem do vídeo anexando a variável tracks populada e limpa
        if (secured != null) {
            videos.add(Video(secured, "Principal", secured, videoHeadersFor(iframeUrl), subtitleTracks = tracks))
        }
        if (source != null && source != secured) {
            videos.add(Video(source, "Alternativo", source, videoHeadersFor(iframeUrl), subtitleTracks = tracks))
        }

        return videos
    }

    private fun tryApiGet(url: String, referer: String): String = try {
        val req = Request.Builder().url(url).headers(apiHeadersFor(referer)).build()
        client.newCall(req).execute().use { it.body?.string().orEmpty() }
    } catch (e: Exception) {
        ""
    }

    private fun tryApiPost(url: String, referer: String, body: RequestBody): String = try {
        val req = Request.Builder()
            .url(url)
            .headers(apiHeadersFor(referer))
            .post(body)
            .build()
        client.newCall(req).execute().use { it.body?.string().orEmpty() }
    } catch (e: Exception) {
        ""
    }

    private fun playerPageHeaders(referer: String): Headers = Headers.Builder()
        .add("User-Agent", desktopUa)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Referer", referer)
        .build()

    private fun apiHeadersFor(referer: String): Headers = Headers.Builder()
        .add("User-Agent", desktopUa)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        .add("Referer", referer)
        .add("Origin", "https://javplayers.com")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()
}
