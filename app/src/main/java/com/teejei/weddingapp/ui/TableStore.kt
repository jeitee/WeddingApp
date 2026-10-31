package com.teejei.weddingapp.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Connection to the separate seating Apps Script (Settings > Connections). Saved on the phone. */
data class SeatingConfig(val url: String = "", val key: String = "")

/** One row of the RSVP_Seats sheet. [row] is the sheet row number. */
data class SeatEntry(val row: Int, val name: String, val group: String, val attending: String, val table: String)

data class AssignResult(val updated: Set<Int>, val skipped: Int)

private val Context.seatingDs by preferencesDataStore("seating_connection")

object TableStore {
    private val kUrl = stringPreferencesKey("url")
    private val kKey = stringPreferencesKey("key")

    fun flow(ctx: Context): Flow<SeatingConfig> = ctx.seatingDs.data.map { p ->
        SeatingConfig(p[kUrl].orEmpty(), p[kKey].orEmpty())
    }

    suspend fun save(ctx: Context, c: SeatingConfig) {
        ctx.seatingDs.edit { it[kUrl] = c.url.trim(); it[kKey] = c.key.trim() }
    }
}

object SeatingApi {

    suspend fun list(cfg: SeatingConfig): Result<List<SeatEntry>> = runCatching {
        val o = post(cfg, JSONObject().put("action", "list"))
        val a = o.optJSONArray("seats") ?: JSONArray()
        (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            SeatEntry(s.getInt("row"), s.optString("name"), s.optString("group"), s.optString("attending"), s.optString("table"))
        }
    }

    suspend fun assign(cfg: SeatingConfig, entries: List<SeatEntry>, table: String): Result<AssignResult> = runCatching {
        val updates = JSONArray()
        entries.forEach { updates.put(JSONObject().put("row", it.row).put("name", it.name).put("table", table)) }
        val o = post(cfg, JSONObject().put("action", "assign").put("updates", updates))
        val up = o.optJSONArray("updated") ?: JSONArray()
        val sk = o.optJSONArray("skipped") ?: JSONArray()
        AssignResult((0 until up.length()).map { up.getInt(it) }.toSet(), sk.length())
    }

    // Apps Script answers a POST with a redirect; HttpURLConnection follows it with a GET, which is what we want.
    private suspend fun post(cfg: SeatingConfig, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        require(cfg.url.isNotBlank()) { "Seating URL is not set (Settings > Connections)." }
        body.put("key", cfg.key)
        val c = URL(cfg.url.trim()).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            val o = try { JSONObject(text) } catch (e: Exception) {
                error("The seating script did not return JSON. Check the URL and that it is deployed as a web app open to \"Anyone\".")
            }
            if (o.optString("status") == "error") error(o.optString("message", "Seating script error"))
            o
        } finally {
            c.disconnect()
        }
    }
}