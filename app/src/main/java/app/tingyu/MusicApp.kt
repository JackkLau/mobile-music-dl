package app.tingyu

import android.app.Application
import android.content.Context
import app.tingyu.data.auth.SessionStore
import app.tingyu.data.bili.BilibiliRepository
import app.tingyu.data.local.LibraryStore
import app.tingyu.download.DownloadCoordinator
import app.tingyu.debug.DebugSettingsStore

class MusicApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    val sessions = SessionStore(context)
    val debugSettings = DebugSettingsStore(context, available = BuildConfig.DEBUG)
    val bilibili = BilibiliRepository(sessions::cookieHeader, debugSettings::snapshot) {
        android.util.Log.i("TingYuDownload", it)
    }
    val library = LibraryStore(context)
    val downloads = DownloadCoordinator(context, bilibili, library)
}
