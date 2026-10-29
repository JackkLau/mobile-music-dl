package app.tingyu.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import app.tingyu.model.DownloadTask
import app.tingyu.model.LocalTrack
import app.tingyu.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "local_tracks")
data class LocalRecord(@PrimaryKey val id: String, val payload: String, val savedAt: Long)
@Entity(tableName = "downloads")
data class DownloadRecord(@PrimaryKey val id: String, val payload: String, val createdAt: Long)
@Entity(tableName = "player_snapshot")
data class PlayerRecord(@PrimaryKey val id: Int = 1, val payload: String, val index: Int, val position: Long, val mode: String, val sessionKey: String)

@Dao
interface MusicDao {
    @Query("SELECT * FROM local_tracks ORDER BY savedAt DESC") fun observeLocal(): Flow<List<LocalRecord>>
    @Query("SELECT * FROM local_tracks WHERE id = :id") suspend fun local(id: String): LocalRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: LocalRecord)
    @Query("DELETE FROM local_tracks WHERE id = :id") suspend fun deleteLocal(id: String)
    @Query("SELECT * FROM downloads ORDER BY createdAt ASC") suspend fun downloads(): List<DownloadRecord>
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun download(id: String): DownloadRecord?
    @Query("DELETE FROM downloads WHERE id = :id") suspend fun deleteDownload(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDownload(record: DownloadRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPlayer(record: PlayerRecord)
    @Query("SELECT * FROM player_snapshot WHERE id = 1") suspend fun player(): PlayerRecord?
    @Query("DELETE FROM player_snapshot") suspend fun clearPlayer()
}

@Database(entities = [LocalRecord::class, DownloadRecord::class, PlayerRecord::class], version = 1, exportSchema = false)
abstract class MusicDatabase : RoomDatabase() { abstract fun musicDao(): MusicDao }

class LibraryStore(context: Context) {
    private val db = Room.databaseBuilder(context.applicationContext, MusicDatabase::class.java, "tingyu.db").build()
    private val dao = db.musicDao()
    private val json = Json { ignoreUnknownKeys = true }
    val localTracks: Flow<List<LocalTrack>> = dao.observeLocal().map { rows -> rows.map { json.decodeFromString<LocalTrack>(it.payload) } }
    suspend fun saved(id: String): LocalTrack? = dao.local(id)?.let { json.decodeFromString(it.payload) }
    suspend fun saveLocal(track: LocalTrack) = dao.put(LocalRecord(track.track.id, json.encodeToString(track), track.savedAt))
    suspend fun removeLocal(id: String) = dao.deleteLocal(id)
    suspend fun readDownloads(): List<DownloadTask> = dao.downloads().map { json.decodeFromString(it.payload) }
    suspend fun removeDownload(id: String) = dao.deleteDownload(id)
    suspend fun saveDownload(task: DownloadTask) = dao.putDownload(DownloadRecord(task.track.id, json.encodeToString(task), dao.download(task.track.id)?.createdAt ?: System.currentTimeMillis()))
    suspend fun commitDownload(local: LocalTrack, task: DownloadTask) = db.withTransaction {
        dao.put(LocalRecord(local.track.id, json.encodeToString(local), local.savedAt))
        dao.putDownload(DownloadRecord(task.track.id, json.encodeToString(task), dao.download(task.track.id)?.createdAt ?: System.currentTimeMillis()))
    }
    suspend fun savePlayer(tracks: List<Track>, index: Int, position: Long, mode: String, sessionKey: String) = dao.putPlayer(PlayerRecord(payload = json.encodeToString(tracks), index = index, position = position, mode = mode, sessionKey = sessionKey))
    suspend fun readPlayer(): PlayerRecord? = dao.player()
    fun decodeQueue(record: PlayerRecord): List<Track> = json.decodeFromString(record.payload)
    suspend fun clearPlayer() = dao.clearPlayer()
}
