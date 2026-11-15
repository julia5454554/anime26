// language: Kotlin, file: src/pt/xanimu/src/eu/kanade/tachiyomi/animeextension/pt/xanimu/ProxyServer.kt
// *NanoHTTPD local injeta cf_clearance + Referer no request upstream; ExoPlayer fala com 127.0.0.1*

package eu.kanade.tachiyomi.animeextension.pt.xanimu

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.util.concurrent.TimeUnit

class ProxyServer(private val baseClient: OkHttpClient) : NanoHTTPD("127.0.0.1", 0) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .connectionPool(ConnectionPool(3, 30, TimeUnit.SECONDS))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Cookie do cf_clearance — o Xanimu exige em xanimu.com, o CDN xcdn1.nosofiles.com
     * pode ou não exigir; mandar sempre é mais seguro.
     */
    @Volatile var cookieHeader: String = ""

    /**
     * Referer padrão — o CDN valida o `verify` contra o Referer da página de detalhe.
     * O `videoListParse` atualiza essa property antes de servir os Video.
     */
    @Volatile var referer: String = "https://xanimu.com/pt/"

    @Volatile var userAgent: String = DEFAULT_UA

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/proxy") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
        val targetUrl = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing url")

        val reqHeaders = Headers.Builder()
            .set("User-Agent", userAgent)
            .set("Referer", referer)
            .set("Origin", "https://xanimu.com")
            .set("Accept", "*/*")
            .apply { if (cookieHeader.isNotEmpty()) set("Cookie", cookieHeader) }
            .build()

        val reqBuilder = Request.Builder().url(targetUrl).headers(reqHeaders).get()
        session.headers["range"]?.let { reqBuilder.header("Range", it) }

        Log.d(TAG, "→ range=${session.headers["range"]} url=$targetUrl")

        val upstream = try {
            client.newCall(reqBuilder.build()).execute()
        } catch (e: Exception) {
            Log.e(TAG, "upstream exception", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "err")
        }

        Log.d(
            TAG,
            "← ${upstream.code} len=${upstream.header("Content-Length")} " +
                "range=${upstream.header("Content-Range")} ct=${upstream.header("Content-Type")}",
        )

        if (upstream.code >= 400) {
            val code = upstream.code
            upstream.close()
            val status = Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR
            return newFixedLengthResponse(status, "text/plain", "upstream $code")
        }

        val body = upstream.body
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "no body")
        val mime = upstream.header("Content-Type") ?: "video/mp4"
        val status = if (upstream.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val length = body.contentLength()

        val stream = object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    upstream.close()
                }
            }
        }
        val nano = if (length >= 0) {
            newFixedLengthResponse(status, mime, stream, length)
        } else {
            newChunkedResponse(status, mime, stream)
        }
        upstream.header("Content-Range")?.let { nano.addHeader("Content-Range", it) }
        upstream.header("Accept-Ranges")?.let { nano.addHeader("Accept-Ranges", it) }
        return nano
    }

    companion object {
        private const val TAG = "XanimuProxy"
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
