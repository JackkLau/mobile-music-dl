package app.tingyu.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.tingyu.data.auth.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Debug-only shell entry. No file output, account changes, background service, or media saving. */
class ProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var runId: String? = null
    private lateinit var status: TextView
    private lateinit var cancelButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset * 2, inset, inset)
            fitsSystemWindows = true
        }
        status = TextView(this).apply {
            text = "正在进行网络诊断…每种 UA 最多读取 1 KB，不保存音频。"
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        cancelButton = Button(this).apply {
            text = "取消对照"
            setOnClickListener { job?.cancel(); finish() }
        }
        root.addView(status)
        root.addView(cancelButton)
        setContentView(root)
        if (savedInstanceState != null) {
            // Never reissue requests automatically after process/configuration recreation.
            finish()
        } else handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val token = intent.getStringExtra("run").orEmpty()
        if (!Regex("[a-f0-9]{32}").matches(token)) { finish(); return }
        if (intent.getBooleanExtra("cancel", false)) {
            if (token == runId || runId == null) { job?.cancel(); finish() }
            return
        }
        if (job?.isActive == true) {
            Log.i(TAG, "run=$token stage=session event=finished exit=1 reason=busy")
            return
        }
        val encoded = intent.getStringExtra("input").orEmpty()
        val input = try {
            require(encoded.length in 1..32768)
            Base64.decode(encoded, Base64.NO_WRAP).toString(Charsets.UTF_8).also { require(it.length <= 8192) }
        } catch (_: Exception) {
            Log.i(TAG, "run=$token stage=session event=finished exit=2 reason=input")
            finish()
            return
        }
        runId = token
        fun output(line: String) { Log.i(TAG, "run=$token $line") }
        val sessions = SessionStore(applicationContext)
        output("stage=session event=started mode=${if (sessions.state.value.isLoggedIn) "account" else "anonymous"}")
        job = scope.launch {
            val code = try {
                if (NetworkProbe(sessions::cookieHeader).run(input, ::output)) 0 else 1
            } catch (_: CancellationException) { 130 }
            catch (_: Exception) { 1 }
            output("stage=session event=finished exit=$code")
            status.text = when (code) {
                0 -> "样本诊断完成。请查看终端日志；此结果不代表完整下载或保存成功。"
                130 -> "已取消对照。"
                else -> "诊断未全部通过，请查看终端中的状态码和失败阶段。"
            }
            cancelButton.text = "返回"
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { private const val TAG = "TingYuProbe" }
}
