package com.teejei.weddingapp.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.teejei.weddingapp.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Context.syncDs by preferencesDataStore("sync_state")

/** Remembers (on the phone) whether the user turned Background sync on. */
object SyncPrefs {
    private val kOn = booleanPreferencesKey("background_enabled")
    fun enabled(ctx: Context): Flow<Boolean> = ctx.syncDs.data.map { it[kOn] ?: false }
    suspend fun isEnabled(ctx: Context): Boolean = enabled(ctx).first()
    suspend fun set(ctx: Context, on: Boolean) {
        ctx.syncDs.edit { it[kOn] = on }
    }

    /** How often (in minutes) the app checks for new RSVPs while it is open or Background sync is on. */
    private val kMin = intPreferencesKey("interval_minutes")
    fun minutesFlow(ctx: Context): Flow<Int> = ctx.syncDs.data.map { (it[kMin] ?: 2).coerceIn(1, 15) }
    suspend fun minutes(ctx: Context): Int = minutesFlow(ctx).first()
    suspend fun setMinutes(ctx: Context, m: Int) {
        ctx.syncDs.edit { it[kMin] = m.coerceIn(1, 15) }
    }
}

/**
 * Foreground service that keeps the app alive while it is minimized or the screen is off.
 * Every N minutes (Settings: 1, 2 or 5) it refreshes guests and runs the automatic SMS check (same code as the in-app
 * refresh); memories are refreshed every 5th check. A partial wake lock keeps the CPU running so short intervals hold
 * with the screen off. It does not run after the app is swiped out of Recents or force-stopped.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var repo: Repository
    private lateinit var sms: SmsNotifier
    @Volatile private var lastLine = "Starting..."

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = Repository(applicationContext)
        sms = SmsNotifier(applicationContext)
        val ch = NotificationChannel(CHANNEL, "Background sync", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shown while the wedding app keeps syncing in the background"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CoroutineScope(Dispatchers.IO).launch { SyncPrefs.set(applicationContext, false) }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification(lastLine)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            // Android refused to start a foreground service from the background; the app can restart it when opened.
            stopSelf()
            return START_NOT_STICKY
        }
        running.value = true
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weddingapp:sync").apply { setReferenceCounted(false); acquire() }
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                var n = 0
                while (isActive) {
                    runCycle(withMemories = n % 5 == 0)
                    n++
                    delay(SyncPrefs.minutes(applicationContext) * 60_000L)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun runCycle(withMemories: Boolean) {
        try {
            var texted = 0
            val g = repo.refreshGuests()
            if (withMemories) repo.refreshMemories()
            if (g.isSuccess) sms.process(repo.guests.first()).onSuccess { texted = it }
            val time = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH))
            lastLine = if (g.isSuccess) "Last check $time" + (if (texted > 0) " - texted $texted" else "")
            else "Couldn't sync at $time - will retry"
        } catch (e: Exception) {
            lastLine = "Sync problem - will retry"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(lastLine))
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, SyncService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Wedding app is syncing")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        running.value = false
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "background_sync"
        private const val NOTIF_ID = 4201
        private const val ACTION_STOP = "com.teejei.weddingapp.SYNC_STOP"

        /** True while the service is alive; the in-app loop pauses itself then so nothing is fetched twice. */
        val running = MutableStateFlow(false)

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SyncService::class.java))
        }
    }
}