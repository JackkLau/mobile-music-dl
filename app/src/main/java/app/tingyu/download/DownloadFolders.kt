package app.tingyu.download

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.tingyu.data.local.LocalMusicManager
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DownloadFolder(val tree: String? = null, val label: String = LocalMusicManager.MUSIC_DIRECTORY)

class DownloadFolders(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("download_folder", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val selected = MutableStateFlow(DownloadFolder(prefs.getString("tree", null),
        prefs.getString("label", null) ?: LocalMusicManager.MUSIC_DIRECTORY))
    val current = selected.asStateFlow()

    suspend fun select(tree: Uri?, flags: Int = 0) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = if (tree == null) DownloadFolder() else {
                if (!DocumentsContract.isTreeUri(tree) || tree.scheme != ContentResolver.SCHEME_CONTENT ||
                    flags and READ_WRITE != READ_WRITE) throw IOException("请选择允许读取和写入的文件夹")
                val directory = root(tree)
                val name = resolver.query(directory, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_FLAGS), null, null, null)?.use {
                    if (it.moveToFirst() && it.getString(1) == Document.MIME_TYPE_DIR &&
                        it.getInt(2) and Document.FLAG_DIR_SUPPORTS_CREATE != 0) it.getString(0) else null
                } ?: throw IOException("所选文件夹不允许创建音乐文件")
                val label = if (tree.authority == "com.android.externalstorage.documents")
                    DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { name } else name
                resolver.takePersistableUriPermission(tree, READ_WRITE)
                if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission })
                    throw IOException("无法保留文件夹授权，请重新选择")
                DownloadFolder(tree.toString(), label)
            }
            if (!prefs.edit().putString("tree", next.tree).putString("label", next.label).commit())
                throw IOException("无法保存下载位置，请重试")
            // Previously granted trees can still contain tracks in the library.
            selected.value = next
        }
    }

    companion object {
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

        /** All exported tracks are direct children. A missing volume or incomplete listing is inconclusive. */
        internal fun confirmsFileRemoved(resolver: ContentResolver, file: Uri): Boolean {
            return try {
                if (resolver.getType(root(file)) != Document.MIME_TYPE_DIR) return false
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(file, DocumentsContract.getTreeDocumentId(file))
                val fileId = DocumentsContract.getDocumentId(file)
                resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                    if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                        cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)) return false
                    while (cursor.moveToNext()) if (cursor.getString(0) == fileId) return false
                    true
                } == true
            } catch (_: Exception) { false }
        }
    }
}
