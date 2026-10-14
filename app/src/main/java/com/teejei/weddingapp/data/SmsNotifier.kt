package com.teejei.weddingapp.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.teejei.weddingapp.model.Guest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Context.smsDs by preferencesDataStore("sms_state")

/** A SIM card the app can send from. subId -1 = the phone's default SMS SIM. */
data class SimOption(val subId: Int, val label: String)

/** Editable in Settings; stored on the phone, so changing it never needs a rebuild. */
data class MessageConfig(
    val weddingDate: LocalDate = LocalDate.of(2026, 12, 20),
    val couple: String = "Teejei & Judith Marie",
    val followUpDate: LocalDate = LocalDate.of(2026, 12, 1),   // when you plan to send the follow-up
    val finalizeBy: LocalDate? = null                           // deadline mentioned in the follow-up text (optional)
)

fun formatLongDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH))

/**
 * Sends SMS from this phone's SIM:
 *  1. AUTOMATIC: a confirmation text when seats of a guest group newly confirm attendance.
 *  2. MANUAL: the December 1 "please finalize your guest list" follow-up, which is only sent when you pick
 *     groups in the follow-up list and tap Send (see [sendFollowUp]).
 *
 * "Already texted" is recorded in the Google Sheet (sms_sent_seats / finalize_sms_sent_at). Before sending, the app
 * claims the seat or group on the server and texts only what it actually claimed, so two phones can never text the
 * same thing and reinstalling the app can't cause re-sends. If the sheet can't be reached, nothing is sent.
 */
class SmsNotifier(private val ctx: Context) {
    private val kEnabled = booleanPreferencesKey("enabled")
    private val kSubId = intPreferencesKey("sim_sub_id")
    private val mutex = Mutex()
    private val gson = Gson()
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    private val kWedding = stringPreferencesKey("wedding_date")
    private val kCouple = stringPreferencesKey("couple_names")
    private val kFollowUp = stringPreferencesKey("followup_date")
    private val kFinalizeBy = stringPreferencesKey("finalize_by")

    private fun Preferences.toConfig(): MessageConfig {
        val d = MessageConfig()
        fun date(k: Preferences.Key<String>): LocalDate? = this[k]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return MessageConfig(
            weddingDate = date(kWedding) ?: d.weddingDate,
            couple = this[kCouple]?.takeIf { it.isNotBlank() } ?: d.couple,
            followUpDate = date(kFollowUp) ?: d.followUpDate,
            finalizeBy = date(kFinalizeBy)
        )
    }

    val enabled: Flow<Boolean> = ctx.smsDs.data.map { it[kEnabled] ?: false }
    val simId: Flow<Int> = ctx.smsDs.data.map { it[kSubId] ?: -1 }
    suspend fun setSimId(id: Int) { ctx.smsDs.edit { it[kSubId] = id } }

    /** Active SIM cards. Empty until the phone-state permission is granted. */
    @Suppress("MissingPermission")
    fun availableSims(): List<SimOption> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            sm.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
                .map { SimOption(it.subscriptionId, "SIM ${it.simSlotIndex + 1} - ${it.carrierName}") }
        }.getOrDefault(emptyList())
    }
    val config: Flow<MessageConfig> = ctx.smsDs.data.map { it.toConfig() }
    suspend fun currentConfig(): MessageConfig = config.first()

    suspend fun saveConfig(c: MessageConfig) {
        ctx.smsDs.edit { p ->
            p[kWedding] = c.weddingDate.toString()
            p[kCouple] = c.couple.trim().take(40)
            p[kFollowUp] = c.followUpDate.toString()
            val by = c.finalizeBy
            if (by != null) p[kFinalizeBy] = by.toString() else p.remove(kFinalizeBy)
        }
    }

    companion object {
        // Android warns about / blocks apps that send more than ~30 SMS parts in 30 minutes. The app checks every
        // 5 minutes, so automatic confirmation texts use at most 5 parts per check to stay safely under that limit.
        private const val PART_BUDGET = 5
    }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun confirmed(g: Guest) = g.seats.filter { it.attending == "attending" }.sortedBy { it.seatNo }

    /* ---------- Apps Script calls (blocking; always run on Dispatchers.IO) ---------- */

    private fun call(s: AppSettings, payload: Map<String, Any?>): JsonObject {
        require(s.scriptUrl.isNotBlank() && s.adminKey.isNotBlank()) { "Apps Script URL / admin key not set. Open Settings." }
        val body = gson.toJson(payload + mapOf("key" to s.adminKey)).toRequestBody("text/plain".toMediaType())
        http.newCall(Request.Builder().url(s.scriptUrl).post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server returned HTTP ${r.code}")
            val o = gson.fromJson(r.body?.string().orEmpty(), JsonObject::class.java) ?: error("Empty response from the server.")
            val status = o.get("status")?.takeIf { !it.isJsonNull }?.asString
            if (status != "success") error(o.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Server returned an error.")
            return o
        }
    }

    private fun parseSeats(s: String?): Set<Int> =
        s.orEmpty().split(",").mapNotNull { it.trim().removePrefix("s").removePrefix("S").toIntOrNull() }.toSet()

    private class State(val tags: Map<String, Set<Int>>, val finalized: Set<String>)

    private fun fetchState(s: AppSettings): State {
        val o = call(s, mapOf("action" to "admin_sms_list"))
        val tags = o.getAsJsonObject("tags")?.entrySet()?.associate { (k, v) -> k to parseSeats(v.asString) } ?: emptyMap()
        val finalized = o.getAsJsonObject("finalized")?.keySet()?.toSet() ?: emptySet()
        return State(tags, finalized)
    }

    /** Tags the seats that are not tagged yet and returns only those. */
    private fun claim(s: AppSettings, token: String, seats: List<Int>): List<Int> {
        val o = call(s, mapOf("action" to "admin_sms_seats", "mode" to "claim", "token" to token, "seats" to seats))
        return o.getAsJsonArray("seats")?.map { it.asInt }.orEmpty()
    }

    private fun release(s: AppSettings, token: String, seats: List<Int>) {
        runCatching { call(s, mapOf("action" to "admin_sms_seats", "mode" to "release", "token" to token, "seats" to seats)) }
    }

    private fun claimFinalize(s: AppSettings, token: String): Boolean {
        val o = call(s, mapOf("action" to "admin_finalize_sms", "mode" to "claim", "token" to token))
        return o.get("claimed")?.takeIf { !it.isJsonNull }?.asBoolean == true
    }

    private fun releaseFinalize(s: AppSettings, token: String) {
        runCatching { call(s, mapOf("action" to "admin_finalize_sms", "mode" to "release", "token" to token)) }
    }

    /**
     * Turning on tags everyone who already confirmed (without texting them), so no backlog is sent.
     * If the sheet can't be reached, the switch is NOT turned on.
     */
    suspend fun setEnabled(on: Boolean, guests: List<Guest>): Result<Unit> = mutex.withLock {
        runCatching {
            if (on) withContext(Dispatchers.IO) {
                val s = SettingsStore(ctx).current()
                val state = fetchState(s)
                for (g in guests) {
                    val untagged = confirmed(g).map { it.seatNo }.filter { it !in (state.tags[g.token] ?: emptySet()) }
                    if (untagged.isNotEmpty()) claim(s, g.token, untagged)
                }
            }
            ctx.smsDs.edit { it[kEnabled] = on }
            Unit
        }
    }

    /** AUTOMATIC confirmation texts. Returns how many SMS messages were sent. */
    suspend fun process(guests: List<Guest>): Result<Int> = mutex.withLock {
        runCatching {
            if (ctx.smsDs.data.first()[kEnabled] != true || !hasPermission()) return@runCatching 0
            withContext(Dispatchers.IO) {
                val s = SettingsStore(ctx).current()
                val state = fetchState(s)   // if this fails nothing is sent
                val cfg = currentConfig()
                var sent = 0
                var parts = 0
                seatLoop@ for (g in guests) {
                    val fresh = confirmed(g).filter { it.seatNo !in (state.tags[g.token] ?: emptySet()) }
                    if (fresh.isEmpty()) continue
                    val number = normalizePh(g.contact) ?: continue
                    val need = partsOf(compose(g, fresh.map { displayName(it.name) }, cfg))
                    if (parts > 0 && parts + need > PART_BUDGET) break@seatLoop
                    val claimed = try { claim(s, g.token, fresh.map { it.seatNo }) } catch (e: Exception) { break@seatLoop }
                    if (claimed.isEmpty()) continue            // another phone already texted these
                    val toSend = fresh.filter { it.seatNo in claimed }
                    try {
                        parts += send(number, compose(g, toSend.map { displayName(it.name) }, cfg))
                        sent++
                    } catch (e: Exception) {
                        release(s, g.token, claimed)            // un-tag so it is retried on the next check
                    }
                }
                sent
            }
        }
    }

    /* ---------- MANUAL December 1 follow-up ---------- */

    fun finalizeMessage(g: Guest, cfg: MessageConfig): String = composeFinalize(g, cfg)

    fun hasValidNumber(g: Guest): Boolean = normalizePh(g.contact) != null

    /** Tokens of the guest groups that were already sent the follow-up (read from the sheet). */
    suspend fun followUpSentTokens(): Result<Set<String>> = runCatching {
        withContext(Dispatchers.IO) { fetchState(SettingsStore(ctx).current()).finalized }
    }

    /** Texts one group. Returns true if sent, false if it was already sent (for example from another phone). */
    suspend fun sendFollowUp(g: Guest): Result<Boolean> = mutex.withLock {
        runCatching {
            check(hasPermission()) { "SMS permission is not granted." }
            val number = normalizePh(g.contact) ?: error("No valid contact number.")
            withContext(Dispatchers.IO) {
                val s = SettingsStore(ctx).current()
                val cfg = currentConfig()
                if (!claimFinalize(s, g.token)) return@withContext false
                try {
                    send(number, composeFinalize(g, cfg))
                    true
                } catch (e: Exception) {
                    releaseFinalize(s, g.token)
                    throw e
                }
            }
        }
    }

    /* ---------- MANUAL confirmation texts (when the automatic switch is off) ---------- */

    /** Confirmed seats per group token that were not texted yet (read from the sheet). */
    suspend fun unsentConfirmations(guests: List<Guest>): Result<Map<String, List<Int>>> = runCatching {
        withContext(Dispatchers.IO) {
            val state = fetchState(SettingsStore(ctx).current())
            guests.mapNotNull { g ->
                val fresh = confirmed(g).map { it.seatNo }.filter { it !in (state.tags[g.token] ?: emptySet()) }
                if (fresh.isEmpty()) null else g.token to fresh
            }.toMap()
        }
    }

    fun confirmationMessage(g: Guest, seatNos: List<Int>, cfg: MessageConfig): String {
        val names = confirmed(g).filter { it.seatNo in seatNos }.map { displayName(it.name) }
        return compose(g, names.ifEmpty { listOf("a guest") }, cfg)
    }

    /** Texts one group's untexted confirmed seats. Returns how many seats were texted (0 = already texted). */
    suspend fun sendConfirmation(g: Guest): Result<Int> = mutex.withLock {
        runCatching {
            check(hasPermission()) { "SMS permission is not granted." }
            val number = normalizePh(g.contact) ?: error("No valid contact number.")
            withContext(Dispatchers.IO) {
                val s = SettingsStore(ctx).current()
                val cfg = currentConfig()
                val all = confirmed(g)
                if (all.isEmpty()) return@withContext 0
                val claimed = claim(s, g.token, all.map { it.seatNo })   // server returns only seats not tagged yet
                if (claimed.isEmpty()) return@withContext 0
                val toSend = all.filter { it.seatNo in claimed }
                try {
                    send(number, compose(g, toSend.map { displayName(it.name) }, cfg))
                    toSend.size
                } catch (e: Exception) {
                    release(s, g.token, claimed)
                    throw e
                }
            }
        }
    }

    private fun displayName(n: String) = n.trim().ifBlank { "a guest" }

    private fun seatsText(n: Int) = if (n == 1) "1 seat" else "$n seats"

    // ASCII only, so a message stays in the cheaper 160-character SMS segments.
    private fun compose(g: Guest, names: List<String>, cfg: MessageConfig): String {
        val who = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        val total = g.maxPax.toInt()
        val confirmed = g.confirmedSeats.toInt()
        val open = (total - confirmed - g.declinedSeats.toInt()).coerceAtLeast(0)
        return buildString {
            append("Hi ${g.groupName.trim().ifBlank { "there" }}! Thank you for confirming $who for our wedding on ${formatLongDate(cfg.weddingDate)}. ")
            if (open > 0) append("You still have ${seatsText(open)} open - you can add more names on your invitation link. ")
            else if (confirmed >= total && total > 0) append("All ${seatsText(total)} are now confirmed. ")
            append("With love, ${cfg.couple}")
        }
    }

    private fun composeFinalize(g: Guest, cfg: MessageConfig): String {
        val total = g.maxPax.toInt()
        val confirmed = g.confirmedSeats.toInt()
        val open = (total - confirmed - g.declinedSeats.toInt()).coerceAtLeast(0)
        return buildString {
            append("Hi ${g.groupName.trim().ifBlank { "there" }}! Our wedding is on ${formatLongDate(cfg.weddingDate)}, and we are now finalizing the guest list. ")
            append("Please open your invitation link and confirm or decline each seat reserved for you")
            cfg.finalizeBy?.let { append(" by ${formatLongDate(it)}") }
            append(". ")
            append("So far, $confirmed of ${seatsText(total)} confirmed")
            if (open > 0) append(", $open still waiting for your reply")
            append(". Thank you! With love, ${cfg.couple}")
        }
    }

    @Suppress("DEPRECATION")
    private fun defaultSmsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /** SmsManager bound to the SIM chosen in Settings (or the phone's default when none is chosen). Called on Dispatchers.IO. */
    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager {
        val id = runBlocking { ctx.smsDs.data.first()[kSubId] ?: -1 }
        if (id < 0) return defaultSmsManager()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(id)
            else SmsManager.getSmsManagerForSubscriptionId(id)
        }.getOrElse { defaultSmsManager() }
    }

    private fun partsOf(body: String): Int = smsManager().divideMessage(body).size

    /** Sends the text and returns how many SMS parts it used. */
    private fun send(number: String, body: String): Int {
        val sm = smsManager()
        val parts = sm.divideMessage(body)
        if (parts.size > 1) sm.sendMultipartTextMessage(number, null, parts, null, null)
        else sm.sendTextMessage(number, null, body, null, null)
        return parts.size
    }

    /** Philippine-friendly: 09171234567, 9171234567, 639171234567 and +639171234567 all become +639171234567. */
    private fun normalizePh(raw: String): String? {
        val m = Regex("""\+?\d[\d\s\-().]{6,}""").find(raw) ?: return null
        val plus = m.value.startsWith("+")
        val d = m.value.filter { it.isDigit() }
        return when {
            plus && d.length in 8..15 -> "+$d"
            d.startsWith("63") && d.length == 12 -> "+$d"
            d.startsWith("09") && d.length == 11 -> "+63" + d.drop(1)
            d.startsWith("9") && d.length == 10 -> "+63$d"
            else -> null
        }
    }
}