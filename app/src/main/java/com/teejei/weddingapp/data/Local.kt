package com.teejei.weddingapp.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/* ---------- Defaults copied from the existing website source ---------- */
object Defaults {
    // script.js (active API_URL)
    const val SCRIPT_URL =
        "https://script.google.com/macros/s/AKfycbzJf_kvU44TRztjZaYixK4EIX8wJq8Ate8SIC0NL7WGSA0mx0N1wQQ_9cptDoeL_vB-QA/exec"

    // qr-script.js
    const val SUPABASE_URL = "https://jwixdwokeguliuwbwbqs.supabase.co"
    const val ADMIN_KEY  = "iFoundJudithMariePerezLomibaoDeCelis022323"
    const val SUPABASE_KEY = "sb_publishable_LN5IZzt80pI20i8W4kT-4g_BkEEKlss"
    const val EVENT_ID = "teejei-judith"
}

data class AppSettings(
    val scriptUrl: String = Defaults.SCRIPT_URL,
    val adminKey: String = Defaults.ADMIN_KEY,
    val supabaseUrl: String = Defaults.SUPABASE_URL,
    val supabaseKey: String = Defaults.SUPABASE_KEY,
    val eventId: String = Defaults.EVENT_ID,
    val lastGuestSync: Long = 0,
    val lastMemorySync: Long = 0
)

private val Context.ds by preferencesDataStore("wedding_settings")

class SettingsStore(private val ctx: Context) {
    private val kScript = stringPreferencesKey("script_url")
    private val kAdmin = stringPreferencesKey("admin_key")
    private val kSbUrl = stringPreferencesKey("sb_url")
    private val kSbKey = stringPreferencesKey("sb_key")
    private val kEvent = stringPreferencesKey("event_id")
    private val kGSync = longPreferencesKey("guest_sync")
    private val kMSync = longPreferencesKey("memory_sync")

    private fun Preferences.toSettings() = AppSettings(
        scriptUrl = this[kScript] ?: Defaults.SCRIPT_URL,
        adminKey = this[kAdmin] ?: Defaults.ADMIN_KEY,
        supabaseUrl = this[kSbUrl] ?: Defaults.SUPABASE_URL,
        supabaseKey = this[kSbKey] ?: Defaults.SUPABASE_KEY,
        eventId = this[kEvent] ?: Defaults.EVENT_ID,
        lastGuestSync = this[kGSync] ?: 0,
        lastMemorySync = this[kMSync] ?: 0
    )

    val flow: Flow<AppSettings> = ctx.ds.data.map { it.toSettings() }
    suspend fun current(): AppSettings = flow.first()

    suspend fun save(s: AppSettings) {
        ctx.ds.edit {
            it[kScript] = s.scriptUrl.trim(); it[kAdmin] = s.adminKey.trim()
            it[kSbUrl] = s.supabaseUrl.trim(); it[kSbKey] = s.supabaseKey.trim()
            it[kEvent] = s.eventId.trim()
        }
    }
    suspend fun setGuestSync(ms: Long) { ctx.ds.edit { it[kGSync] = ms } }
    suspend fun setMemorySync(ms: Long) { ctx.ds.edit { it[kMSync] = ms } }
}

/* ---------- Room ---------- */
@Entity(tableName = "guests")
data class GuestEntity(
    @PrimaryKey val token: String,
    val groupName: String,
    val maxPax: Int,
    val contact: String,
    val url: String,
    val tinyUrl: String,
    val excelUrl: String,
    val seatsJson: String,
    val invitationSent: Boolean,
    val sentAt: String?,
    val sentNotes: String,
    val firstOpenedAt: String?,
    val lastOpenedAt: String?,
    val visitCount: Int,
    val pendingSync: Boolean
)

@Entity(tableName = "uploads")
data class UploadEntity(
    @PrimaryKey val id: String, val uploader: String, val message: String,
    val type: String, val fileCount: Int, val createdAt: String
)

@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey val id: String, val uploadId: String, val type: String, val url: String,
    val name: String, val sizeMb: Double, val mime: String, val createdAt: String
)

data class MediaRow(
    val id: String, val uploadId: String, val type: String, val url: String, val name: String, val sizeMb: Double,
    val createdAt: String, val uploader: String?, val message: String?
)

@Dao
abstract class WeddingDao {
    @Query("SELECT * FROM guests") abstract fun guests(): Flow<List<GuestEntity>>
    @Query("SELECT * FROM guests") abstract suspend fun allGuests(): List<GuestEntity>
    @Query("SELECT * FROM guests WHERE pendingSync = 1") abstract suspend fun pending(): List<GuestEntity>
    @Query("SELECT * FROM guests WHERE token = :t") abstract suspend fun guest(t: String): GuestEntity?
    @Upsert abstract suspend fun upsertGuests(list: List<GuestEntity>)
    @Upsert abstract suspend fun upsertGuest(g: GuestEntity)
    @Query("DELETE FROM guests") abstract suspend fun clearGuests()

    @Transaction
    open suspend fun replaceGuests(list: List<GuestEntity>) { clearGuests(); upsertGuests(list) }

    @Query("DELETE FROM uploads") abstract suspend fun clearUploads()
    @Query("DELETE FROM media") abstract suspend fun clearMedia()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertUploads(l: List<UploadEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertMedia(l: List<MediaEntity>)

    @Transaction
    open suspend fun replaceMemories(u: List<UploadEntity>, m: List<MediaEntity>) {
        clearUploads(); clearMedia(); insertUploads(u); insertMedia(m)
    }

    @Query("SELECT COUNT(*) FROM media WHERE type = :t") abstract fun countMedia(t: String): Flow<Int>
    @Query("SELECT COUNT(DISTINCT LOWER(TRIM(uploader))) FROM uploads WHERE TRIM(uploader) <> ''")
    abstract fun countUploaders(): Flow<Int>

    @Query(
        "SELECT m.id AS id, m.uploadId AS uploadId, m.type AS type, m.url AS url, m.name AS name, m.sizeMb AS sizeMb, " +
                "m.createdAt AS createdAt, u.uploader AS uploader, u.message AS message " +
                "FROM media m LEFT JOIN uploads u ON u.id = m.uploadId ORDER BY m.createdAt DESC"
    )
    abstract fun mediaRows(): Flow<List<MediaRow>>
}

@Database(entities = [GuestEntity::class, UploadEntity::class, MediaEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun dao(): WeddingDao }