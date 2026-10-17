package eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors

import aniyomi.lib.bloggerextractor.BloggerExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient

class UniversalExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val bloggerExtractor = BloggerExtractor(client)
    private val voeExtractor = VoeExtractor(client, headers)

    fun videosFromUrl(url: String): List<Video> = when {
        url.contains("blogger.com") -> {
            runBlocking {
                bloggerExtractor.videosFromUrl(url, headers)
            }
        }
        url.contains("voe.sx") || url.contains("claireguessthousand.com") -> {
            runBlocking {
                voeExtractor.videosFromUrl(url)
            }
        }
        url.contains(".mp4") || url.contains(".m3u8") -> {
            listOf(Video(url, "Direct", url, headers = headers))
        }
        else -> emptyList()
    }
}
