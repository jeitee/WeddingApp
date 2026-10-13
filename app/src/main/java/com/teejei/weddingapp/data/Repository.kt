package com.teejei.weddingapp.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.teejei.weddingapp.model.Guest
import com.teejei.weddingapp.model.MemoryStats
import com.teejei.weddingapp.model.Seat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit

data class ImportSummary(val sheet: String, val imported: Int, val skippedNoToken: Int, val headers: List<String>)

fun Throwable.friendly(): String = when (this) {
    is UnknownHostException -> "No internet connection."
    is SocketTimeoutException -> "The request timed out. Please try again."
    is HttpException -> when (code()) {
        401, 403 -> "Access denied (HTTP ${code()}). For Supabase this usually means a missing read policy — see README."
        else -> "Server returned HTTP ${code()}."
    }
    is JsonSyntaxException -> "Unexpected response. Make sure the Apps Script is deployed as a web app for “Anyone” and the latest version."
    is IOException -> "Network error: ${message ?: "unknown"}"
    else -> message ?: "Something went wrong."
}

class Repository(private val ctx: Context) {
    private val db = Room.databaseBuilder(ctx, AppDb::class.java, "wedding.db")
        .fallbackToDestructiveMigration().build()
    private val dao = db.dao()
    val settings = SettingsStore(ctx)
    private val gson = Gson()
    private val textPlain = "text/plain".toMediaType()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://localhost/")
        .client(OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    private val script = retrofit.create(ScriptApi::class.java)
    private val supa = retrofit.create(SupabaseApi::class.java)

    private val seatListType = object : TypeToken<List<Seat>>() {}.type

    val guests: Flow<List<Guest>> = dao.guests().map { l -> l.map { it.toGuest() } }
    val media: Flow<List<MediaRow>> = dao.mediaRows()
    val memoryStats: Flow<MemoryStats> =
        combine(dao.countMedia("photo"), dao.countMedia("video"), dao.countUploaders()) { p, v, u -> MemoryStats(p, v, u) }

    private fun GuestEntity.toGuest() = Guest(
        token = token, groupName = groupName, maxPax = maxPax, contact = contact,
        url = tinyUrl.ifBlank { url }.ifBlank { excelUrl },
        seats = runCatching { gson.fromJson<List<Seat>>(seatsJson, seatListType) }.getOrNull().orEmpty(),
        invitationSent = invitationSent, sentAt = sentAt, sentNotes = sentNotes,
        firstOpenedAt = firstOpenedAt, lastOpenedAt = lastOpenedAt, visitCount = visitCount, pendingSync = pendingSync
    )

    /* ================= Guests (Apps Script) ================= */

    private fun body(map: Map<String, Any?>) = gson.toJson(map).toRequestBody(textPlain)

    private fun requireScript(s: AppSettings) {
        require(s.scriptUrl.isNotBlank()) { "Apps Script URL is not set. Open Settings." }
        require(s.adminKey.isNotBlank()) { "Admin key is not set. Open Settings and enter the ADMIN_KEY from your Apps Script." }
    }

    private suspend fun push(s: AppSettings, g: GuestEntity): String? {
        val r = script.setSent(
            s.scriptUrl,
            body(mapOf("action" to "admin_set_sent", "key" to s.adminKey, "token" to g.token,
                "sent" to g.invitationSent, "notes" to g.sentNotes))
        )
        if (r.status != "success") error(r.message ?: "Server rejected the update.")
        return r.sent_at
    }

    suspend fun refreshGuests(): Result<Unit> = runCatching {
        val s = settings.current()
        requireScript(s)
        // 1) push offline changes first so they are not overwritten
        dao.pending().forEach { g ->
            runCatching { push(s, g) }.onSuccess { at ->
                dao.upsertGuest(g.copy(pendingSync = false, sentAt = if (g.invitationSent) (at ?: g.sentAt) else null))
            }
        }
        // 2) pull
        val resp = script.list(s.scriptUrl, body(mapOf("action" to "admin_list", "key" to s.adminKey)))
        if (resp.status != "success") error(resp.message ?: "Server returned an error.")
        val local = dao.allGuests().associateBy { it.token }
        val list = resp.guests.orEmpty().filter { !it.token.isNullOrBlank() }.map { d ->
            val t = d.token!!.trim()
            val keep = local[t]?.takeIf { it.pendingSync }
            val seats = d.seats.orEmpty().map { Seat(it.seat_no ?: 0, it.name.orEmpty(), (it.attending ?: "pending").lowercase()) }
                .sortedBy { it.seatNo }
            GuestEntity(
                token = t, groupName = d.group_name.orEmpty().trim(), maxPax = d.max_pax ?: 0,
                contact = d.contact.orEmpty().trim(), url = d.url.orEmpty().trim(),
                tinyUrl = d.tiny_url.orEmpty().trim(),
                excelUrl = local[t]?.excelUrl.orEmpty(), seatsJson = gson.toJson(seats),
                invitationSent = keep?.invitationSent ?: (d.invitation_sent == true),
                sentAt = keep?.sentAt ?: d.sent_at?.takeIf { it.isNotBlank() },
                sentNotes = keep?.sentNotes ?: d.sent_notes.orEmpty(),
                firstOpenedAt = d.first_opened_at?.takeIf { it.isNotBlank() },
                lastOpenedAt = d.last_opened_at?.takeIf { it.isNotBlank() },
                visitCount = d.visit_count ?: 0, pendingSync = keep != null
            )
        }
        dao.replaceGuests(list)
        settings.setGuestSync(System.currentTimeMillis())
    }

    /** Optimistic local update, then push to the backend. Stays pending if the push fails. */
    suspend fun setSent(token: String, sent: Boolean, notes: String): Result<Boolean> = runCatching {
        val g = dao.guest(token) ?: error("Guest not found.")
        val updated = g.copy(
            invitationSent = sent, sentAt = if (sent) Instant.now().toString() else null,
            sentNotes = if (sent) notes.trim() else "", pendingSync = true
        )
        dao.upsertGuest(updated)
        val s = settings.current()
        runCatching { requireScript(s); push(s, updated) }
            .onSuccess { at -> dao.upsertGuest(updated.copy(pendingSync = false, sentAt = if (sent) (at ?: updated.sentAt) else null)) }
            .map { true }
            .getOrElse { e -> throw IllegalStateException("Saved on this device only: ${e.friendly()} It will sync on the next refresh.") }
    }

    /* ================= Excel import ================= */

    private fun Map<String, String>.pick(vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> this[k]?.takeIf { it.isNotBlank() } }.orEmpty()

    suspend fun importExcel(uri: Uri): Result<ImportSummary> = runCatching {
        val data = ctx.contentResolver.openInputStream(uri)?.use { ExcelReader.read(it) }
            ?: error("Could not open the selected file.")
        require("token" in data.headers) {
            "No “token” column found. Columns in “${data.sheetName}”: ${data.headers.joinToString(", ")}"
        }
        var n = 0; var skipped = 0
        for (r in data.rows) {
            val token = r.pick("token")
            if (token.isBlank()) { skipped++; continue }
            val name = r.pick("group_name", "group", "guest_name", "name")
            val pax = r.pick("max_pax", "pax", "seats", "no_of_pax").toDoubleOrNull()?.toInt()
            val contact = r.pick("contact", "contact_number", "phone", "mobile")
            val url = r.pick("tiny_url", "tinyurl", "url", "invitation_url", "invitation_link", "link")
            val ex = dao.guest(token)
            val e = ex?.copy(
                groupName = name.ifBlank { ex.groupName }, maxPax = pax ?: ex.maxPax,
                contact = contact.ifBlank { ex.contact }, excelUrl = url.ifBlank { ex.excelUrl }
            ) ?: GuestEntity(
                token = token, groupName = name, maxPax = pax ?: 0, contact = contact, url = "", tinyUrl = "", excelUrl = url,
                seatsJson = "[]", invitationSent = false, sentAt = null, sentNotes = "",
                firstOpenedAt = null, lastOpenedAt = null, visitCount = 0, pendingSync = false
            )
            dao.upsertGuest(e); n++
        }
        ImportSummary(data.sheetName, n, skipped, data.headers)
    }

    /* ================= Memories (Supabase) ================= */

    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }

    private suspend fun fetchAll(s: AppSettings, table: String): List<JsonObject> {
        val base = s.supabaseUrl.trimEnd('/') + "/rest/v1/$table"
        val headers = mapOf("apikey" to s.supabaseKey, "Accept" to "application/json")
        val out = ArrayList<JsonObject>()
        var offset = 0
        var ordered = true
        while (true) {
            val q = linkedMapOf("select" to "*", "event_id" to "eq.${s.eventId}", "limit" to "1000", "offset" to "$offset")
            if (ordered) q["order"] = "id.desc"
            val page = try {
                supa.rows(base, headers, q)
            } catch (e: HttpException) {
                if (ordered && e.code() == 400) { ordered = false; continue } else throw e
            }
            out += page
            if (page.size < 1000) break
            offset += 1000
        }
        return out
    }

    suspend fun refreshMemories(): Result<Unit> = runCatching {
        val s = settings.current()
        require(s.supabaseUrl.isNotBlank() && s.supabaseKey.isNotBlank()) { "Supabase URL/key not set. Open Settings." }
        val uploads = fetchAll(s, "uploads").mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            UploadEntity(id, o.str("uploader").orEmpty().trim(), o.str("message").orEmpty(),
                o.str("upload_type").orEmpty(), o.str("file_count")?.toIntOrNull() ?: 0, o.str("created_at").orEmpty())
        }
        val files = fetchAll(s, "files").mapNotNull { o ->
            val url = o.str("file_url") ?: return@mapNotNull null
            val status = o.str("upload_status")
            if (status != null && status != "completed") return@mapNotNull null
            val mime = o.str("mime_type").orEmpty()
            val type = o.str("file_type") ?: if (mime.startsWith("video")) "video" else "photo"
            MediaEntity(
                id = o.str("id") ?: o.str("file_id") ?: url, uploadId = o.str("upload_id").orEmpty(),
                type = type, url = url, name = o.str("original_filename").orEmpty(),
                sizeMb = o.str("file_size_mb")?.toDoubleOrNull() ?: 0.0, mime = mime, createdAt = o.str("created_at").orEmpty()
            )
        }
        dao.replaceMemories(uploads, files)
        settings.setMemorySync(System.currentTimeMillis())
    }
}
