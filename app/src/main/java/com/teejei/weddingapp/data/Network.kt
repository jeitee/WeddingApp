package com.teejei.weddingapp.data

import com.google.gson.JsonObject
import okhttp3.RequestBody
import retrofit2.http.*

/* ---------- Google Apps Script (admin extension, see apps-script/Admin.gs) ---------- */
data class SeatDto(val seat_no: Int?, val name: String?, val attending: String?)

data class GuestDto(
    val token: String?, val group_name: String?, val max_pax: Int?, val rsvp_status: String?,
    val contact: String?, val url: String?, val tiny_url: String?, val updated_at: String?,
    val invitation_sent: Boolean?, val sent_at: String?, val sent_notes: String?,
    val first_opened_at: String?, val last_opened_at: String?, val visit_count: Int?,
    val seats: List<SeatDto>?
)

data class AdminListResponse(val status: String?, val message: String?, val guests: List<GuestDto>?)
data class SimpleResponse(val status: String?, val message: String?, val sent_at: String?)

interface ScriptApi {
    @POST suspend fun list(@Url url: String, @Body body: RequestBody): AdminListResponse
    @POST suspend fun setSent(@Url url: String, @Body body: RequestBody): SimpleResponse
}

/* ---------- Supabase PostgREST ---------- */
interface SupabaseApi {
    @GET suspend fun rows(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @QueryMap query: Map<String, String>
    ): List<JsonObject>
}
