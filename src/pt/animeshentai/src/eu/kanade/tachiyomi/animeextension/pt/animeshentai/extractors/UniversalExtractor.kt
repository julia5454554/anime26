package eu.kanade.tachiyomi.animeextension.pt.animeshentai.extractors

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class UniversalExtractor(private val client: OkHttpClient) {

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun videosFromUrl(url: String, headers: Headers, suffix: String = ""): List<Video> = when {
        isBlogger(url) -> bloggerExtractor.videosFromUrl(url, headers, suffix)
        isP2Player(url) -> extractP2Player(url, headers, suffix)
        isDirectVideo(url) -> extractDirectVideo(url, headers, suffix)
        else -> emptyList()
    }

    private fun isBlogger(url: String): Boolean = url.contains("blogger.com") || url.contains("blogspot.com")

    private fun isP2Player(url: String): Boolean = url.contains("players/p2") || url.contains("p2.php")

    private fun isDirectVideo(url: String): Boolean = url.contains(".mp4") || url.contains(".m3u8")

    private fun isAdUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("pre-roll") || u.contains("preroll") || u.contains("sacanas") ||
            u.contains("/ads/") || u.contains("/ad/") || u.contains("doubleclick") ||
            u.contains("/vast")
    }

    private fun inferQuality(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains("1080p") || u.contains("/1080/") -> "1080p"
            u.contains("720p") || u.contains("/720/") -> "720p"
            u.contains("480p") || u.contains("/480/") -> "480p"
            u.contains("_sd") || u.contains("/sd/") || u.contains("-sd") -> "SD"
            else -> "HD"
        }
    }

    private fun extractDirectVideo(url: String, headers: Headers, suffix: String): List<Video> {
        if (isAdUrl(url)) return emptyList()
        return listOf(Video(url, inferQuality(url) + suffix, url, headers))
    }

    private fun extractP2Player(url: String, headers: Headers, suffix: String): List<Video> {
        return try {
            val padrao = url.substringAfter("padrao=", "").substringBefore("&")
            if (padrao.isBlank()) return emptyList()

            val wrapperBase = url.substringBefore("?").trimEnd('/')
            val wrapperHost = url.substringAfter("//").substringBefore("/")
            val p2Url = "$wrapperBase/p2.php?id=$padrao"

            val request = Request.Builder()
                .url(p2Url)
                .headers(headers.newBuilder().set("Referer", "https://$wrapperHost/").build())
                .build()

            val response = noRedirectClient.newCall(request).execute()
            val location = response.header("Location")
            response.close()

            if (location.isNullOrBlank()) return emptyList()

            val finalUrl = if (location.startsWith("http")) {
                location
            } else {
                "$wrapperBase/${location.removePrefix("/")}"
            }

            if (isAdUrl(finalUrl)) return emptyList()

            val videoHeaders = headers.newBuilder()
                .set("Referer", "https://$wrapperHost/")
                .set("Accept-Encoding", "identity")
                .build()

            listOf(Video(finalUrl, inferQuality(finalUrl) + suffix, finalUrl, videoHeaders))
        } catch (e: Exception) {
            emptyList()
        }
    }
}
