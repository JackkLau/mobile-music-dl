package app.tingyu.probe

import app.tingyu.debug.NetworkProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.exitProcess

/** PC diagnostics are anonymous; the phone entry uses its existing selected account mode. */
fun main(args: Array<String>) {
    if (args.size != 2 || !Regex("[a-f0-9]{32}").matches(args[0]) || args[1].length > 8192) {
        System.err.println("Usage: probe <32-character run id> <Bilibili video link or share text>")
        exitProcess(2)
    }
    val runId = args[0]
    val timeoutSeconds = System.getenv("TINGYU_PROBE_TIMEOUT_SECONDS")?.toLongOrNull() ?: 240L
    if (timeoutSeconds !in 1L..1800L) exitProcess(2)
    fun output(line: String) = println("run=$runId $line")
    output("stage=session event=started mode=anonymous")
    val status = try {
        if (runBlocking { withTimeout(timeoutSeconds * 1000) { NetworkProbe().run(args[1], ::output) } }) 0 else 1
    } catch (_: TimeoutCancellationException) { 124 }
    catch (_: CancellationException) { 130 }
    catch (_: Exception) { 1 } // Exception messages can contain signed URLs or response data.
    output("stage=session event=finished exit=$status")
    exitProcess(status)
}
