package app.tingyu.update

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File

/** Shares one private update file through a temporary read grant. */
class UpdateFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Update files are read-only")
        return super.openFile(uri, mode)
    }
}

@Suppress("DEPRECATION")
internal fun systemInstaller(context: Context, file: File, name: String): Intent {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file, name)
    val pm = context.packageManager
    for (action in listOf(Intent.ACTION_INSTALL_PACKAGE, Intent.ACTION_VIEW)) {
        val intent = Intent(action).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY
        val target = pm.queryIntentActivities(intent, flags).map { it.activityInfo }.firstOrNull {
            it.exported && (action == Intent.ACTION_INSTALL_PACKAGE ||
                pm.checkPermission(Manifest.permission.INSTALL_PACKAGES, it.packageName) == PackageManager.PERMISSION_GRANTED)
        }
        if (target != null) return intent.setComponent(ComponentName(target.packageName, target.name))
    }
    throw UpdateError("没有可用的系统安装程序，可将安装包保存到文件")
}
