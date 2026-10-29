package app.tingyu.data.bili

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class BiliResponse(val status: Int, val text: String, val location: String? = null, val family: String = "unknown", val proxy: String = "unknown")

internal fun interface BiliTransport {
    suspend fun get(url: HttpUrl, headers: Map<String, String>): BiliResponse
}

internal class OkHttpBiliTransport : BiliTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .eventListenerFactory { NetworkConnectionListener() }
        .build()

    override suspend fun get(url: HttpUrl, headers: Map<String, String>): BiliResponse = suspendCancellableCoroutine { continuation ->
        val metadata = ConnectionMetadata()
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }
            .tag(ConnectionMetadata::class.java, metadata).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    BiliException("无法连接 B 站，请检查网络后重试", connection = metadata).apply { initCause(e) },
                )
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        // API responses are bounded. No untrusted server can exhaust the app heap.
                        val body = if (response.isRedirect) "" else {
                            val source = response.body?.source()
                            if (source != null && source.request(MAX_RESPONSE_BYTES + 1)) {
                                throw BiliException("返回内容过大，请使用具体视频或收藏夹链接")
                            }
                            source?.readUtf8().orEmpty()
                        }
                        val result = BiliResponse(response.code, body, response.header("Location"), metadata.family, metadata.proxy)
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        BiliException(
                            if (e is BiliException) e.message ?: "读取 B 站响应失败，请重试" else "读取 B 站响应失败，请重试",
                            code = (e as? BiliException)?.code,
                            httpStatus = response.code,
                            connection = metadata,
                        ).apply { initCause(e) },
                    )
                }
            }
        })
    }

    companion object { private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024 }
}
