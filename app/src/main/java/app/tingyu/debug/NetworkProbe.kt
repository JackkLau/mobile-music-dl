package app.tingyu.debug

import app.tingyu.data.bili.BiliException
import app.tingyu.data.bili.BilibiliRepository
import app.tingyu.data.bili.NetworkProfile
import app.tingyu.data.bili.NetworkTrace
import app.tingyu.data.bili.UserAgentProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The Android debug entry and the PC CLI execute this exact implementation. */
class NetworkProbe internal constructor(
    private val repositoryFactory: (NetworkProfile, (String) -> Unit) -> BilibiliRepository,
    private val comparisonFactory: (BilibiliRepository) -> UaComparison = ::UaComparison,
) {
    constructor(cookies: () -> String = { "" }) : this({ profile, sink ->
        BilibiliRepository(cookies, { profile }, sink)
    })

    /** Each parse UA gets one audio resolution, then two UA requests to that same signed URL. */
    suspend fun run(input: String, onLine: (String) -> Unit): Boolean {
        var failed = false
        for (parseUa in listOf(UserAgentProfile.DESKTOP, UserAgentProfile.MOBILE)) {
            currentCoroutineContext().ensureActive()
            val profile = NetworkProfile(parseUa = parseUa, loggingEnabled = true)
            val requestId = NetworkTrace.requestId()
            fun report(fields: Map<String, String>) = onLine(NetworkTrace.line(requestId,
                fields + mapOf("parse_ua" to parseUa.id)))
            report(mapOf("stage" to "probe", "event" to "parse_started"))
            val repository = repositoryFactory(profile, onLine)
            try {
                val parsed = repository.parse(input, profile, requestId)
                if (parsed.favoriteId != null) throw BiliException("诊断请使用单个视频链接")
                val track = parsed.tracks.firstOrNull() ?: throw BiliException("未找到可诊断的音轨")
                val result = comparisonFactory(repository).run(track, profile,
                    requestId = requestId, onLine = {})
                failed = failed || result.failed
                if (result.stop) {
                    report(mapOf("stage" to "probe", "event" to "stopped", "reason" to "authorization_or_limit"))
                    return false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                val detail = mutableMapOf("stage" to "probe", "event" to "failed",
                    "failure" to NetworkTrace.failureKind(error.cause ?: error))
                if (error is BiliException) {
                    error.stage?.let { detail["stage"] = it }
                    error.httpStatus?.let { detail["http"] = it.toString() }
                    error.code?.let { detail["code"] = it.toString() }
                }
                report(detail)
                if (mustStop(error)) {
                    report(mapOf("stage" to "probe", "event" to "stopped", "reason" to "authorization_or_limit"))
                    return false
                }
            }
        }
        return !failed
    }

    private fun mustStop(error: Exception): Boolean = error is BiliException &&
        (error.httpStatus in setOf(401, 403, 412, 429) ||
            error.code in setOf(-101, -403, 403, 10003, -352, -412, -401, 401, 412, 429))
}
