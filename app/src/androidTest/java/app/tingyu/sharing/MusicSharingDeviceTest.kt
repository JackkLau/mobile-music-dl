package app.tingyu.sharing

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tingyu.model.LocalTrack
import app.tingyu.model.Track
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class MusicSharingDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val track = Track("BV1sVtw6JEeq", 349, "示例歌曲", artist = "示例歌手", page = 2,
        sourceUrl = "https://cdn.invalid/track?token=private")

    @Test fun remoteMusicOpensAChooserWithOnlyThePublicPageAndSongName() = runBlocking {
        val chooser = musicShareIntent(app, track)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNotNull(chooser.resolveActivity(app.packageManager))
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertNull(send.component)
        assertNull(send.`package`)
        assertEquals("text/plain", send.type)
        assertEquals("示例歌曲 · 示例歌手\nhttps://www.bilibili.com/video/BV1sVtw6JEeq?p=2", send.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(0, send.flags)
    }

    @Test fun localAudioGivesTheChooserReadAccessToOnlyTheSelectedFile(): Unit = runBlocking {
        val resolver = app.contentResolver
        val media = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(media, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "share-${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/WeileTest/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        try {
            resolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0)) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1)
            val local = LocalTrack(track, uri.toString())
            val chooser = musicShareIntent(app, track, local)
            val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals("audio/mpeg", send.type)
            assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
            assertEquals(1, send.clipData!!.itemCount)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, chooser.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            assertThrows(IOException::class.java) {
                runBlocking { musicShareIntent(app, track.copy(page = 3), local) }
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test fun invalidLocalSourcesOfferNoAttachment() {
        val local = track.copy(localOnly = true)
        listOf("file:///data/user/0/app.tingyu/private", "https://cdn.invalid/song.mp3",
            "content://com.android.externalstorage.documents/tree/primary%3AMusic").forEach { uri ->
            assertThrows(IOException::class.java) { runBlocking { musicShareIntent(app, local, LocalTrack(local, uri)) } }
        }
        assertThrows(IOException::class.java) { runBlocking { musicShareIntent(app, local) } }
    }
}
