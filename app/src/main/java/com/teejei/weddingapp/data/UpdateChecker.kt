package com.teejei.weddingapp.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

object UpdateChecker {
    // Raw link to version.json in your GitHub repo (change "main" to "master" if that is your branch).
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/jeitee/WeddingApp/main/version.json"

    /** (versionCode, versionName) of the app installed on this phone. */
    fun installedVersion(c: Context): Pair<Int, String> {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt()
        else @Suppress("DEPRECATION") p.versionCode
        return code to (p.versionName ?: "")
    }

    /** Success(info) if a newer version exists, Success(null) if up to date, Failure if the check failed. */
    suspend fun check(c: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            // The timestamp avoids a stale cached copy of version.json.
            val conn = (URL("$VERSION_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                useCaches = false
            }
            try {
                val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val info = UpdateInfo(
                    versionCode = j.getInt("versionCode"),
                    versionName = j.getString("versionName"),
                    apkUrl = j.getString("apkUrl"),
                    notes = j.optString("notes")
                )
                if (info.versionCode > installedVersion(c).first) info else null
            } finally {
                conn.disconnect()
            }
        }
    }
}
