package app.tingyu.data.auth

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.webkit.CookieManager
import android.webkit.WebStorage
import app.tingyu.model.Account
import app.tingyu.model.SessionMode
import app.tingyu.model.SessionState
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encrypted, device-local session. The file is excluded from Android backups by its directory. */
class SessionStore(context: Context) {
    private val backing = synchronized(backings) {
        val app = context.applicationContext
        backings.getOrPut(app.noBackupFilesDir.absolutePath) { Backing(app) }
    }
    val state: StateFlow<SessionState> = backing.state.asStateFlow()

    fun cookieHeader(): String = synchronized(backing) {
        if (backing.state.value.isLoggedIn) backing.cookie else ""
    }

    fun setMode(mode: SessionMode) = synchronized(backing) {
        require(mode != SessionMode.ACCOUNT || backing.state.value.account != null) { "请先登录 B 站账号" }
        val next = backing.state.value.copy(mode = mode)
        backing.write(backing.cookie, next)
        backing.state.value = next
    }

    fun saveSession(cookie: String, account: Account) = synchronized(backing) {
        val normalized = CookiePolicy.normalize(cookie)
        require(CookiePolicy.hasSession(normalized) && account.mid > 0) { "登录信息无效，请重新登录" }
        val next = SessionState(SessionMode.ACCOUNT, account)
        // Publish only after durable encrypted storage succeeds.
        backing.write(normalized, next)
        backing.cookie = normalized
        backing.state.value = next
    }

    fun logout() {
        synchronized(backing) {
            backing.cookie = ""
            backing.state.value = SessionState()
            backing.file.delete()
        }
        clearWebSession()
    }

    private class Backing(context: Context) {
        val state = MutableStateFlow(SessionState())
        var cookie = ""
        val file = AtomicFile(File(context.noBackupFilesDir, "bilibili-session.enc"))
        private val alias = "${context.packageName}.bilibili.session.v1"
        private val json = Json { ignoreUnknownKeys = true }

        init {
            if (file.baseFile.exists()) {
                try {
                    require(file.baseFile.length() in 1L..131_072L)
                    val stored = file.readFully()
                    require(stored.size > 30 && stored[0].toInt() == 1 && stored[1].toInt() == 12)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(2, 14)))
                    val plain = cipher.doFinal(stored.copyOfRange(14, stored.size))
                    val saved = json.decodeFromString<SavedSession>(plain.toString(Charsets.UTF_8))
                    plain.fill(0)
                    cookie = CookiePolicy.normalize(saved.cookie)
                    require(CookiePolicy.hasSession(cookie) && saved.account.mid > 0)
                    state.value = SessionState(SessionMode.valueOf(saved.mode), saved.account)
                } catch (_: Exception) {
                    // A missing/invalidated key or corrupt file must fail closed, with no secret in a log.
                    file.delete()
                    cookie = ""
                    state.value = SessionState()
                }
            }
        }

        fun write(cookie: String, state: SessionState) {
            val account = state.account ?: run { file.delete(); return }
            val plain = json.encodeToString(SavedSession(cookie, account, state.mode.name)).toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val encrypted = try {
                cipher.init(Cipher.ENCRYPT_MODE, key())
                cipher.doFinal(plain)
            } finally { plain.fill(0) }
            val stream = file.startWrite()
            try {
                stream.write(byteArrayOf(1, cipher.iv.size.toByte()))
                stream.write(cipher.iv)
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
        }
    }

    @Serializable
    private data class SavedSession(val cookie: String, val account: Account, val mode: String)

    companion object {
        private val backings = mutableMapOf<String, Backing>()

        fun clearWebSession(onComplete: (() -> Unit)? = null) {
            val clear = {
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    onComplete?.invoke()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) clear() else Handler(Looper.getMainLooper()).post { clear() }
        }
    }
}
