package eu.kanade.tachiyomi.animeextension.pt.xhamster.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.jsoup.nodes.Document

class XHPlayer(private val client: OkHttpClient) {

    fun videosFromPage(referer: String, document: Document): List<Video> {
        val videos = mutableListOf<Video>()
        val html = document.toString()

        val playerHeaders = Headers.Builder().apply {
            add("Referer", referer)
            add("Origin", "https://xhamster.com")
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Accept", "video/*,*/*")
        }.build()

        try {
            var hlsEncontrado = false

            // 1. ESTRATÉGIA CLOUDSTREAM (Mais Segura): Buscar o .m3u8 no <link rel="preload">
            val preloadLinks = document.select("link[rel=preload][as=fetch]")
            for (link in preloadLinks) {
                val href = link.attr("href")
                if (href.isNotEmpty() && href.contains(".m3u8")) {
                    videos.add(
                        Video(
                            url = href,
                            quality = "HLS (Automático)",
                            videoUrl = href,
                            headers = playerHeaders,
                        ),
                    )
                    hlsEncontrado = true
                    break // Como já achou o HLS, sai do loop
                }
            }

            // 2. FALLBACK 1: Extrair Resoluções MP4 ou HLS via JSON (Código antigo melhorado)
            val regex = Regex("""window\.initials\s*=\s*(\{.+?\});?\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html)

            if (match != null) {
                val jsonStr = match.groupValues[1]
                val json = JSONObject(jsonStr)
                val xplayer = json.optJSONObject("xplayerSettings")
                val sources = xplayer?.optJSONObject("sources")

                // Se o Preload falhou, tenta pegar o HLS aqui pelo JSON
                if (!hlsEncontrado) {
                    val hls = sources?.optJSONObject("hls")
                    val h264HlsUrl = hls?.optJSONObject("h264")?.optString("url") ?: hls?.optJSONObject("av1")?.optString("url")

                    if (!h264HlsUrl.isNullOrBlank()) {
                        videos.add(
                            Video(
                                url = h264HlsUrl,
                                quality = "HLS (JSON)",
                                videoUrl = h264HlsUrl,
                                headers = playerHeaders,
                            ),
                        )
                    }
                }

                // Extrair as resoluções fixas em MP4 (Standard)
                val standard = sources?.optJSONObject("standard")
                val h264List = standard?.optJSONArray("h264")
                if (h264List != null) {
                    for (i in 0 until h264List.length()) {
                        val item = h264List.getJSONObject(i)
                        val quality = item.optString("quality", "HD")
                        val videoUrl = item.optString("url")

                        if (videoUrl.isNotBlank()) {
                            videos.add(
                                Video(
                                    url = videoUrl,
                                    quality = "$quality p",
                                    videoUrl = videoUrl,
                                    headers = playerHeaders,
                                ),
                            )
                        }
                    }
                }
            }

            // 3. FALLBACK FINAL: Varrer o código inteiro procurando qualquer link .m3u8
            // Só executa se absolutamente nenhum vídeo tiver sido adicionado até aqui
            if (videos.isEmpty()) {
                val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                val m3u8Match = m3u8Regex.find(html)
                if (m3u8Match != null) {
                    videos.add(
                        Video(
                            url = m3u8Match.value,
                            quality = "HLS (Fallback)",
                            videoUrl = m3u8Match.value,
                            headers = playerHeaders,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // =========================================================================
        // FILTRO DE SEGURANÇA (Resolve o erro "File name too long" no mpv)
        // Garante que o aplicativo nunca envie lixo HTML para o player.
        // Apenas URLs que começam com "http" passam daqui.
        // =========================================================================
        return videos.filter { it.url.startsWith("http") }
    }
}
