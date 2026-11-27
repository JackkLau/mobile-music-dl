package app.tingyu.update

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, VERIFYING, SAVING }

internal data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: GitHubRelease? = null,
    val bytes: Long = 0,
    val canInstall: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

internal class AppUpdateViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = AppUpdateRepository(app)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null

    init {
        val version = saved.get<String>("version")
        val size = saved.get<Long>("size")
        val sha256 = saved.get<String>("sha256")
        if (version != null && size != null && sha256 != null) {
            val release = GitHubRelease(version, size, sha256, legacy = saved.get<Boolean>("legacyAsset") ?: true)
            mutable.value = UpdateState(UpdatePhase.AVAILABLE, release)
            // A system file picker may outlive this process.
            if (repo.file(release).isFile) {
                mutable.value = UpdateState(UpdatePhase.CHECKING, release)
                operation = viewModelScope.launch {
                    val compatible = withContext(Dispatchers.IO) { repo.verifiedCache(release) }
                    mutable.value = if (compatible == null) UpdateState(UpdatePhase.AVAILABLE, release)
                    else UpdateState(UpdatePhase.READY, release, canInstall = compatible)
                }
            }
        }
    }

    fun check() {
        if (operation?.isActive == true) return
        val previous = mutable.value
        mutable.value = previous.copy(phase = UpdatePhase.CHECKING, error = null, message = null)
        operation = viewModelScope.launch {
            try {
                val release = repo.latest()
                saved["version"] = release.version
                saved["size"] = release.size
                saved["sha256"] = release.sha256
                saved["legacyAsset"] = release.legacy
                if (previous.phase == UpdatePhase.READY && release == previous.release && repo.file(release).isFile) {
                    mutable.value = previous.copy(message = null, error = null)
                } else {
                    mutable.value = UpdateState(UpdatePhase.AVAILABLE, release)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = previous.copy(error = errorText(e), message = null)
            }
        }
    }

    fun download() {
        val release = mutable.value.release ?: return
        if (operation?.isActive == true || mutable.value.phase != UpdatePhase.AVAILABLE) return
        mutable.value = UpdateState(UpdatePhase.DOWNLOADING, release)
        operation = viewModelScope.launch {
            try {
                val owner = currentCoroutineContext()
                val compatible = withContext(Dispatchers.IO) {
                    repo.download(release) { count ->
                        mutable.update { current ->
                            if (owner.isActive && current.phase == UpdatePhase.DOWNLOADING) current.copy(bytes = count)
                            else current
                        }
                    }
                }
                mutable.value = UpdateState(UpdatePhase.READY, release, canInstall = compatible)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = UpdateState(UpdatePhase.AVAILABLE, release, error = errorText(e))
            }
        }
    }

    fun cancelDownload() {
        val value = mutable.value
        if (value.phase != UpdatePhase.DOWNLOADING) return
        operation?.cancel()
        mutable.value = UpdateState(UpdatePhase.AVAILABLE, value.release)
    }

    suspend fun installIntent(expectedDigest: String): android.content.Intent? {
        val ready = mutable.value
        val release = ready.release ?: return null
        if (ready.phase != UpdatePhase.READY || !ready.canInstall || release.sha256 != expectedDigest) return null
        mutable.value = ready.copy(phase = UpdatePhase.VERIFYING, error = null, message = null)
        try {
            val intent = withContext(Dispatchers.IO) { repo.verifiedInstallIntent(release) }
            mutable.value = ready.copy(error = null, message = null)
            return intent
        } catch (e: CancellationException) {
            mutable.value = ready
            throw e
        } catch (e: Exception) {
            mutable.value = if (repo.file(release).isFile) ready.copy(error = errorText(e), message = null)
                else UpdateState(UpdatePhase.AVAILABLE, release, error = errorText(e))
            return null
        }
    }

    fun saveCopy(uri: Uri) {
        val ready = mutable.value
        val release = ready.release ?: return
        if (ready.phase != UpdatePhase.READY || operation?.isActive == true) return
        if (!repo.file(release).isFile) {
            mutable.value = UpdateState(UpdatePhase.AVAILABLE, release, error = "安装包缓存已清理，请重新下载")
            return
        }
        val app = getApplication<Application>()
        if (uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.authority == app.packageName + ".updates") {
            feedback("请选择有效的保存位置")
            return
        }
        mutable.value = ready.copy(phase = UpdatePhase.SAVING, message = null, error = null)
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.exportVerified(release) {
                        app.contentResolver.openOutputStream(uri, "wt") ?: throw UpdateError("无法写入所选位置")
                    }
                }
                mutable.value = ready.copy(message = "安装包已保存。如需更新，请在系统文件管理器中打开。", error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateError) {
                mutable.value = if (repo.file(release).isFile) ready.copy(error = e.message, message = null)
                    else UpdateState(UpdatePhase.AVAILABLE, release, error = e.message)
            } catch (_: Exception) {
                mutable.value = ready.copy(error = "未能完整保存，请检查可用空间并重试", message = null)
            }
        }
    }

    fun feedback(text: String) {
        mutable.update { it.copy(message = null, error = text) }
    }

    private fun errorText(e: Exception) = (e as? UpdateError)?.message ?: "无法完成更新操作，请检查网络和可用空间后重试"
}
