package app.tingyu.data.local

import app.tingyu.model.LocalTrack
import app.tingyu.model.Track
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocalMusicRulesTest {
    @Test fun previousTrackAndLocalRecordJsonRemainReadable() {
        val old = """{"track":{"bvid":"BV1xx411c7mD","cid":42,"title":"Old title","page":2},"uri":"content://media/external_primary/audio/media/10","savedAt":1000}"""
        val restored = Json.decodeFromString<LocalTrack>(old)
        assertFalse(restored.track.localOnly)
        assertNull(restored.identityUri)
        assertEquals("BV1xx411c7mD:p2", restored.track.id)
        assertEquals(1000L, restored.savedAt)
    }

    @Test fun localOnlySurvivesPersistenceWithoutCreatingAWebSource() {
        val original = LocalMusicRules.track("local song.wav", "content://fixture/music/10")
        val restored = Json.decodeFromString<Track>(Json.encodeToString(original))
        assertTrue(restored.localOnly)
        assertEquals("", restored.sourceUrl)
        assertEquals(original.id, restored.id)
    }

    @Test fun legacyExportNameRecoversVideoAndPartWithoutAllowingNetworkFallback() {
        val track = LocalMusicRules.track("歌名-现场-BV1sVtw6JEeq-P12.m4a", "content://fixture/music/10")
        assertEquals("BV1sVtw6JEeq:p12", track.id)
        assertEquals("歌名-现场", track.title)
        assertTrue(track.localOnly)
        assertEquals("", track.sourceUrl)
    }

    @Test fun duplicateExportSuffixAndProviderMetadataArePreserved() {
        val track = LocalMusicRules.track("old-BV1sVtw6JEeq-P2 (1).m4a", "content://fixture/music/10", "Original title", "Artist", 10_000)
        assertEquals("BV1sVtw6JEeq:p2", track.id)
        assertEquals("Original title", track.title)
        assertEquals("Artist", track.artist)
        assertEquals(10_000L, track.durationMs)
    }

    @Test fun unknownNamesHaveStableUriIdentityIndependentOfDisplayName() {
        val original = LocalMusicRules.track("song.wav", "content://fixture/music/10")
        assertEquals(original.id, LocalMusicRules.track("renamed.wav", "content://fixture/music/10").id)
        assertNotEquals(original.id, LocalMusicRules.track("song.wav", "content://fixture/music/11").id)
        assertTrue(original.bvid.startsWith("local_"))
    }

    @Test fun malformedPartFallsBackToLocalIdentity() {
        val original = LocalMusicRules.track("song-BV1sVtw6JEeq-P999999999999999999999999.m4a", "content://fixture/music/10")
        assertTrue(original.bvid.startsWith("local_"))
        assertEquals(1, original.page)
        assertTrue(original.localOnly)
    }
}
