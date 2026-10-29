package app.tingyu.data.local

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.tingyu.MusicApp
import app.tingyu.model.LocalTrack
import app.tingyu.model.Track
import app.tingyu.playback.TrackDataSource
import app.tingyu.playback.TrackMedia
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room and MediaStore; every written/deleted row and database belongs to this fixture. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LocalMusicIntegrationTest {
    private lateinit var context: Context
    private lateinit var library: LibraryStore
    private lateinit var databaseName: String
    private val created = mutableListOf<Uri>()
    private val token = UUID.randomUUID().toString()

    @Before fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "local-library-fixture-$token.db"
        library = LibraryStore(context, databaseName)
    }

    @After fun cleanup() {
        created.forEach { context.contentResolver.delete(it, null, null) }
        library.close()
        if (context.getDatabasePath(databaseName).exists()) {
            assertTrue("Only this test's isolated database is removed", context.deleteDatabase(databaseName))
        }
    }

    @Test fun completedMediaScanIsRepeatableAndPreservesExistingMetadata() = runBlocking {
        val restored = audio("restore")
        val knownUri = audio("known")
        val pending = audio("pending", pending = true)
        val empty = audio("empty", bytes = byteArrayOf())
        val outside = audio("outside", directory = "Music/")
        val known = LocalTrack(Track("local_$token", 0, "Preserved title", "Preserved artist", localOnly = true), knownUri.toString(), 1234)
        library.saveLocal(known)
        val manager = LocalMusicManager(context, library)
        manager.scan()
        val first = library.readLocal()
        assertEquals(known, library.saved(known.track.id))
        val recovered = first.single { it.uri == restored.toString() }
        assertTrue(recovered.track.localOnly)
        assertTrue(recovered.track.durationMs > 0)
        assertEquals("", recovered.track.sourceUrl)
        assertFalse(first.any { it.uri in listOf(pending, empty, outside).map(Uri::toString) })
        manager.scan()
        assertEquals(first.filter { it.uri in created.map(Uri::toString) }, library.readLocal().filter { it.uri in created.map(Uri::toString) })
    }

    @Test fun importDeduplicatesUriWithRealMediaAndRoomAndRequiresReadGrant() = runBlocking {
        val uri = audio("import", directory = "Music/")
        val receivedFlags = mutableListOf<Int>()
        // Only the OS grant boundary is substituted. File reads, format inspection,
        // MediaStore metadata and Room writes run against the actual Android APIs.
        val manager = LocalMusicManager(context, library, PersistedAudioGrant { selected, flags ->
            assertEquals(uri, selected)
            receivedFlags += flags
            true
        })
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(ImportResult(1, 0, 0), manager.importUris(listOf(uri, uri), flags))
        val original = library.readLocal().single()
        assertEquals(ImportResult(0, 1, 0), manager.importUris(listOf(uri), flags))
        assertEquals(original, library.readLocal().single())
        assertEquals(listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_READ_URI_PERMISSION), receivedFlags)
        assertEquals(ImportResult(0, 0, 1), manager.importUris(listOf(uri), Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertEquals(original, library.readLocal().single())
    }

    @Test fun realImporterRejectsAnUnpersistableGrantWithoutChangingRecords() = runBlocking {
        val uri = audio("ungranted", directory = "Music/")
        val original = LocalTrack(LocalMusicRules.track("fixture.wav", uri.toString()), uri.toString())
        library.saveLocal(original)
        val result = LocalMusicManager(context, library).importUris(listOf(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(ImportResult(0, 0, 1), result)
        assertEquals(original, library.saved(original.track.id))
    }

    @Test fun scannedAudioImportedThroughExternalMediaAliasStaysOneRecord() = runBlocking {
        val uri = audio("alias")
        val manager = LocalMusicManager(context, library, PersistedAudioGrant { _, _ -> true })
        manager.scan()
        val original = library.readLocal().single { it.uri == uri.toString() }
        val alias = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), ContentUris.parseId(uri))
        assertEquals(ImportResult(0, 1, 0), manager.importUris(listOf(alias), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertEquals(original, library.saved(original.track.id))
        assertEquals(1, library.readLocal().count { it.identityUri == uri.toString() || it.uri == uri.toString() })
    }

    @Test fun suppliedDocumentIdentityDeduplicatesInRoomWithoutReplacingScannedMetadata() = runBlocking {
        val uri = audio("document-alias")
        val manager = LocalMusicManager(context, library)
        manager.scan()
        val original = library.readLocal().single { it.uri == uri.toString() }
        // This is a Room identity fixture, not evidence of a real SAF grant or of
        // Android's document mapping. The actual picker path is tested separately.
        val document = DocumentsContract.buildDocumentUri("com.android.providers.media.documents", "audio:${ContentUris.parseId(uri)}")
        val duplicate = LocalTrack(LocalMusicRules.track("selected.wav", document.toString()), document.toString(), identityUri = uri.toString())
        assertFalse(library.addDiscovered(duplicate))
        assertEquals(original, library.saved(original.track.id))
    }

    @Test fun sameVideoIdentityWithDifferentFilesDoesNotReplaceTheFirstFile() = runBlocking {
        val firstUri = audio("first")
        val secondUri = audio("second")
        val first = LocalTrack(LocalMusicRules.track("Old-BV1sVtw6JEeq-P2.m4a", firstUri.toString()), firstUri.toString())
        val second = LocalTrack(LocalMusicRules.track("New-BV1sVtw6JEeq-P2.m4a", secondUri.toString()), secondUri.toString())
        assertTrue(library.addDiscovered(first))
        assertTrue(library.addDiscovered(second))
        assertFalse(library.addDiscovered(second))
        assertEquals(first, library.saved(first.track.id))
        val imported = library.readLocal().single { it.uri == secondUri.toString() }
        assertTrue(imported.track.localOnly)
        assertNotEquals(first.track.id, imported.track.id)
    }

    @Test fun oldJsonAndUriSurviveRoomCloseAndReopen() = runBlocking {
        val uri = audio("upgrade")
        library.close()
        val database = Room.databaseBuilder(context, MusicDatabase::class.java, databaseName).build()
        try {
            // Older serialization sometimes omitted a time-dependent savedAt default.
            val payload = """{"track":{"bvid":"BV1sVtw6JEeq","cid":42,"title":"Before upgrade","artist":"Original artist","page":2},"uri":"$uri"}"""
            database.musicDao().put(LocalRecord("BV1sVtw6JEeq:p2", payload, 1234))
        } finally { database.close() }
        library = LibraryStore(context, databaseName)
        val prior = library.saved("BV1sVtw6JEeq:p2")!!
        assertFalse(prior.track.localOnly)
        assertEquals(1234L, prior.savedAt)
        assertEquals(uri.toString(), prior.uri)
        LocalMusicManager(context, library).scan()
        library.close()
        library = LibraryStore(context, databaseName)
        assertEquals(prior, library.saved(prior.track.id))
        assertEquals(1, library.readLocal().count { it.uri == uri.toString() })
    }

    @Test fun deletionRemovesTheRealFileBeforeRemovingItsRecord() = runBlocking {
        val uri = audio("delete")
        val local = LocalTrack(LocalMusicRules.track("fixture.wav", uri.toString()), uri.toString())
        library.saveLocal(local)
        assertTrue(LocalMusicManager(context, library).delete(local) is DeleteResult.Deleted)
        assertNull(library.saved(local.track.id))
        assertTrue(context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { !it.moveToFirst() } == true)
        created.remove(uri) // Absence was verified above; never delete the same fixture twice.
        Unit
    }

    @Test fun failedDeleteAndReplacedUriKeepTheirRecords() = runBlocking {
        // A MediaStore collection is deliberately not a deletable single file.
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).toString()
        val local = LocalTrack(LocalMusicRules.track("fixture.wav", collection), collection)
        library.saveLocal(local)
        assertTrue(LocalMusicManager(context, library).delete(local) is DeleteResult.Failed)
        assertEquals(local, library.saved(local.track.id))
        val replacement = local.copy(uri = audio("replacement").toString())
        library.saveLocal(replacement)
        assertTrue(LocalMusicManager(context, library).delete(local) is DeleteResult.Failed)
        assertFalse(library.removeLocalIfUri(local.track.id, local.uri))
        assertEquals(replacement, library.saved(local.track.id))
    }

    @Test @SdkSuppress(minSdkVersion = 30) fun approvedSystemDeleteClearsOnlyTheConfirmedMissingFile() = runBlocking {
        val uri = audio("system-delete")
        val local = LocalTrack(LocalMusicRules.track("fixture.wav", uri.toString()), uri.toString())
        library.saveLocal(local)
        assertEquals(1, context.contentResolver.delete(uri, null, null))
        created.remove(uri) // The platform has confirmed this fixture's actual deletion.
        val result = LocalMusicManager(context, library).delete(local, afterUserApproval = true)
        assertEquals(DeleteResult.Deleted(alreadyMissing = true), result)
        assertNull(library.saved(local.track.id))
    }

    @Test fun missingLocalOnlyPlaybackNeverCallsRemoteResolver() {
        val app = context.applicationContext as MusicApp
        val track = Track("local_missing_$token", 0, "Missing local file", localOnly = true)
        var calls = 0
        val source = TrackDataSource(context, app.container, ConcurrentHashMap<String, Track>().apply { put(track.id, track) }, OkHttpClient()) {
            calls++
            throw AssertionError("No remote fallback is permitted for local-only audio")
        }
        try {
            source.open(DataSpec(TrackMedia.item(track).localConfiguration!!.uri))
            fail("Missing local audio should fail")
        } catch (expected: IOException) { assertTrue(expected.message!!.contains("本地音频")) }
        finally { source.close() }
        assertEquals(0, calls)
    }

    private fun audio(label: String, pending: Boolean = false, bytes: ByteArray = wav(), directory: String = LocalMusicManager.MUSIC_DIRECTORY): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "tingyu-fixture-$token-$label.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, directory)
            put(MediaStore.Audio.Media.TITLE, "Fixture $label")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }) ?: error("Could not create fixture")
        created += uri
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        if (!pending) check(resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1)
        return uri
    }

    private fun wav(): ByteArray {
        val samples = 8_000
        val dataSize = samples * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(dataSize)
            repeat(samples) { putShort(0) }
        }.array()
    }
}
