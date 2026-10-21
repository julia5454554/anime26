package eu.kanade.tachiyomi.animeextension.pt.cosplaytele.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CossoraExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String): List<Video> {
        val headers = Headers.Builder()
            .add("Referer", "https://cosplaytele.com/")
            .add("Origin", "https://cossora.stream")
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()

        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: return emptyList()

        val document = Jsoup.parse(html)
        val script = document.select("script").html()

        val videoUrlB64 = Regex("""const\s+videoURL\s*=\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.get(1) ?: return emptyList()
        val key = Regex("""decryptLink\(videoURL\s*,\s*['"]([^'"]+)['"]\)""").find(script)?.groupValues?.get(1) ?: return emptyList()

        val m3u8Url = decryptAES(videoUrlB64, key) ?: return emptyList()

        val videoHeaders = Headers.Builder()
            .add("Referer", "https://cossora.stream/")
            .add("Origin", "https://cossora.stream")
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()

        return playlistUtils.extractFromHls(
            playlistUrl = m3u8Url,
            masterHeaders = videoHeaders,
            videoHeaders = videoHeaders,
        )
    }

    private fun decryptAES(encryptedB64: String, keyStr: String): String? {
        return try {
            val decodedBytes = encryptedB64.decodeBase64()?.toByteArray() ?: return null
            if (decodedBytes.size < 16) return null

            val iv = IvParameterSpec(decodedBytes.copyOfRange(0, 16))
            val cipherText = decodedBytes.copyOfRange(16, decodedBytes.size)

            val keySpec = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)

            val decryptedBytes = cipher.doFinal(cipherText)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
