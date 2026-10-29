package app.tingyu.debug

import app.tingyu.data.bili.BilibiliRepository
import app.tingyu.data.bili.BiliException
import app.tingyu.data.bili.ConnectionMetadata
import app.tingyu.data.bili.MediaRequestPolicy
import app.tingyu.data.bili.NetworkConnectionListener
import app.tingyu.data.bili.NetworkProfile
import app.tingyu.data.bili.NetworkTrace
import app.tingyu.data.bili.UserAgentProfile
import app.tingyu.model.Track
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response

data class UaComparisonResult(val failed: Boolean, val stop: Boolean)

/** One resolution, one fixed signed URL, two bounded requests; no files or background retries. */
class UaComparison internal constructor(private val bilibili: BilibiliRepository, private val client: OkHttpClient) {
    constructor(bilibili: BilibiliRepository) : this(bilibili, OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).eventListenerFactory { NetworkConnectionListener() }
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build())

    suspend fun run(track: Track, profile: NetworkProfile, onLine: (String) -> Unit): UaComparisonResult =
        run(track, profile, NetworkTrace.requestId(), onLine)

    suspend fun run(track: Track, profile: NetworkProfile, requestId: String, onLine: (String) -> Unit): UaComparisonResult = withContext(Dispatchers.IO) {
        var failed = false
        var stop = false
        fun report(fields: Map<String, String>, url: String? = null) {
            onLine(NetworkTrace.line(requestId, fields, url))
            bilibili.log(profile, requestId, fields, url)
        }
        report(mapOf("stage" to "probe", "event" to "resolving", "parse_ua" to profile.parseUa.id))
        val stream = try {
            bilibili.resolveAudio(track, profile, requestId)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            report(mapOf("stage" to "probe", "event" to "resolve_failed", "failure" to failureKind(error)) +
                ((error as? BiliException)?.code?.let { mapOf("code" to it.toString()) } ?: emptyMap()))
            throw error
        }
        val url = stream.url
        // Both requests use exactly this URL. No refresh, fallback host, or automatic redirects.
        for (ua in listOf(UserAgentProfile.DESKTOP, UserAgentProfile.MOBILE)) {
            currentCoroutineContext().ensureActive()
            val request = MediaRequestPolicy.request(url, stream, ua, sampleBytes = SAMPLE_BYTES)
            try {
                val sample = client.newCall(request).awaitProbe(SAMPLE_BYTES)
                currentCoroutineContext().ensureActive()
                val connection = request.tag(ConnectionMetadata::class.java)
                report(mapOf("stage" to "probe", "event" to "sample_result", "http" to sample.status.toString(),
                    "parse_ua" to profile.parseUa.id, "ua" to ua.id, "bytes" to sample.bytes.toString(),
                    "sample" to if (sample.valid) "valid_mp4_header" else "not_confirmed",
                    "range" to "sample", "profile" to "web_media", "offset" to "0", "redirects" to "0", "attempt" to "0",
                    "family" to (connection?.family ?: "unknown"),
                    "proxy" to (connection?.proxy ?: "unknown")), url)
                failed = failed || !sample.valid
                // Explicit throttling or missing authorization terminates diagnostics.
                if (sample.status in listOf(401, 412, 429)) { stop = true; break }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                failed = true
                val connection = request.tag(ConnectionMetadata::class.java)
                report(mapOf("stage" to "probe", "event" to "request_failed", "ua" to ua.id,
                    "parse_ua" to profile.parseUa.id, "profile" to "web_media", "range" to "sample", "attempt" to "0",
                    "family" to (connection?.family ?: "unknown"), "proxy" to (connection?.proxy ?: "unknown"),
                    "network_stage" to (connection?.stage ?: "unknown"), "failure" to failureKind(error)), url)
            }
        }
        report(mapOf("stage" to "probe", "event" to if (stop) "stopped" else "completed",
            "result" to if (failed) "failed" else "success"))
        UaComparisonResult(failed, stop)
    }

    private fun failureKind(error: Exception): String = NetworkTrace.failureKind(error.cause ?: error)

    companion object { private const val SAMPLE_BYTES = 1024 }
}

private data class ProbeSample(val status: Int, val bytes: Int, val valid: Boolean)

/** Cancellation remains attached to the call until the bounded body read has finished. */
private suspend fun Call.awaitProbe(limit: Int): ProbeSample = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!continuation.isActive) return
                    val bytes = if (response.code in listOf(200, 206)) {
                        val input = response.body?.byteStream() ?: throw IOException("音频样本为空")
                        val buffer = ByteArray(limit)
                        var total = 0
                        while (total < buffer.size && continuation.isActive) {
                            val count = input.read(buffer, total, buffer.size - total)
                            if (count < 0) break
                            total += count
                        }
                        buffer.copyOf(total)
                    } else byteArrayOf()
                    val result = ProbeSample(response.code, bytes.size,
                        MediaRequestPolicy.validSample(response.code, response.header("Content-Type"), response.header("Content-Range"), bytes))
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
