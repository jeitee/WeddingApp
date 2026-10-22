package com.teejei.weddingapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teejei.weddingapp.data.*
import com.teejei.weddingapp.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app.applicationContext)
    private val sms = SmsNotifier(app.applicationContext)
    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.Eagerly, init)

    val settings = repo.settings.flow.state(AppSettings())
    val guests = repo.guests.state(emptyList())
    val media = repo.media.state(emptyList())
    val memoryStats = repo.memoryStats.state(MemoryStats())
    val guestStats = guests.map { it.guestStats() }.state(GuestStats())
    val inviteStats = guests.map { it.inviteStats() }.state(InviteStats())

    val filter = MutableStateFlow(GuestFilter())
    val visibleGuests = combine(guests, filter) { g, f ->
        // Stable sort: unnamed groups go last, selected sort order is kept within each part
        // The RSVP chip is applied here by seat counts (a group can have confirmed, declined and awaiting
        // seats at once), so applyFilter() is called with the RSVP part cleared.
        g.applyFilter(f.copy(rsvp = null))
            .filter { guest ->
                when (f.rsvp) {
                    null -> true
                    Rsvp.CONFIRMED -> guest.confirmedSeats > 0
                    Rsvp.DECLINED -> guest.declinedSeats > 0
                    Rsvp.AWAITING -> guest.awaitingSeats > 0
                }
            }
            .sortedBy { it.groupName.isBlank() }
    }.state(emptyList())

    val refreshing = MutableStateFlow(false)
    val guestError = MutableStateFlow<String?>(null)
    val memoryError = MutableStateFlow<String?>(null)
    val importResult = MutableStateFlow<String?>(null)

    /* ---------- App updates ---------- */

    val updateInfo = MutableStateFlow<UpdateInfo?>(null)      // non-null = a newer version is available
    val updateStatus = MutableStateFlow<String?>(null)        // message after a manual check
    val checkingUpdate = MutableStateFlow(false)

    fun checkForUpdate(manual: Boolean = false) {
        if (checkingUpdate.value) return
        viewModelScope.launch {
            checkingUpdate.value = true
            UpdateChecker.check(getApplication<Application>().applicationContext).fold(
                onSuccess = {
                    updateInfo.value = it
                    updateStatus.value = if (it == null && manual) "You're on the latest version." else null
                },
                onFailure = {
                    if (manual) updateStatus.value = "Couldn't check for updates: ${it.friendly()}"
                }
            )
            checkingUpdate.value = false
        }
    }

    val smsEnabled = sms.enabled.state(false)
    val simId = sms.simId.state(-1)
    fun loadSims(): List<SimOption> = sms.availableSims()
    fun setSim(id: Int) = viewModelScope.launch { sms.setSimId(id); toast("SIM choice saved") }
    val smsConfig = sms.config.state(MessageConfig())
    val backgroundOn = SyncPrefs.enabled(app.applicationContext).state(false)
    val syncMinutes = SyncPrefs.minutesFlow(app.applicationContext).state(2)
    fun setSyncMinutes(m: Int) = viewModelScope.launch {
        SyncPrefs.setMinutes(getApplication<Application>().applicationContext, m)
        toast("Checking every $m minute(s)")
    }
    fun setBackground(on: Boolean) = viewModelScope.launch {
        val c = getApplication<Application>().applicationContext
        SyncPrefs.set(c, on)
        if (on) SyncService.start(c) else SyncService.stop(c)
        toast(if (on) "Background sync on" else "Background sync off")
    }
    val followUpSent = MutableStateFlow<Set<String>?>(null)   // tokens already sent the follow-up (null = loading)
    val followUpBusy = MutableStateFlow<String?>(null)        // progress text while sending

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    fun toast(m: String) { _messages.tryEmit(m) }

    init {
        refresh()
        checkForUpdate()
        // While the app is open, re-check every few minutes (Settings: 1, 2 or 5) so new confirmations are picked up (and texted).
        // (When Background sync is on, the service does this instead, so it keeps going while minimized.)
        viewModelScope.launch {
            val c = getApplication<Application>().applicationContext
            while (true) { delay(SyncPrefs.minutes(c) * 60_000L); if (!SyncService.running.value) refresh() }
        }
        // If the user turned Background sync on earlier and the service is not running (phone restarted, app killed), restart it.
        viewModelScope.launch {
            val c = getApplication<Application>().applicationContext
            if (SyncPrefs.isEnabled(c) && !SyncService.running.value) SyncService.start(c)
        }
    }

    fun updateFilter(t: (GuestFilter) -> GuestFilter) = filter.update(t)

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            coroutineScope {
                val g = async { repo.refreshGuests() }
                val m = async { repo.refreshMemories() }
                guestError.value = g.await().exceptionOrNull()?.friendly()
                memoryError.value = m.await().exceptionOrNull()?.friendly()
            }
            if (guestError.value == null) {
                sms.process(repo.guests.first()).onSuccess { n -> if (n > 0) toast("Sent $n SMS confirmation(s)") }
            }
            refreshing.value = false
        }
    }

    fun setSent(g: Guest, sent: Boolean, notes: String) = viewModelScope.launch {
        repo.setSent(g.token, sent, notes).fold(
            onSuccess = { toast(if (sent) "Marked as sent" else "Marked as not sent") },
            onFailure = { toast(it.message ?: "Could not update") }
        )
    }

    fun saveSettings(s: AppSettings) = viewModelScope.launch {
        repo.settings.save(s); toast("Settings saved"); refresh()
    }

    fun importExcel(uri: Uri) = viewModelScope.launch {
        repo.importExcel(uri).fold(
            onSuccess = {
                importResult.value = "Imported ${it.imported} guest group(s) from “${it.sheet}”" +
                        if (it.skippedNoToken > 0) " (${it.skippedNoToken} row(s) skipped: no token)." else "."
            },
            onFailure = { importResult.value = "Import failed: ${it.friendly()}" }
        )
    }

    fun setSmsEnabled(on: Boolean) = viewModelScope.launch {
        sms.setEnabled(on, repo.guests.first()).fold(
            onSuccess = { toast(if (on) "SMS confirmations on. Existing confirmations were tagged and won't be texted." else "SMS confirmations off") },
            onFailure = { toast("Couldn't change the SMS setting: ${it.friendly()}") }
        )
    }

    /* ---------- Manual December 1 follow-up ---------- */

    fun saveSmsConfig(c: MessageConfig) = viewModelScope.launch {
        sms.saveConfig(c)
        toast("Message details saved")
    }

    fun followUpMessage(g: Guest) = sms.finalizeMessage(g, smsConfig.value)
    fun hasValidNumber(g: Guest) = sms.hasValidNumber(g)

    fun loadFollowUp() = viewModelScope.launch {
        sms.followUpSentTokens().fold(
            onSuccess = { followUpSent.value = it },
            onFailure = { followUpSent.value = emptySet(); toast("Couldn't load follow-up status: ${it.friendly()}") }
        )
    }

    fun sendFollowUps(targets: List<Guest>) = viewModelScope.launch {
        if (followUpBusy.value != null || targets.isEmpty()) return@launch
        var sent = 0; var already = 0; var failed = 0
        targets.forEachIndexed { i, g ->
            followUpBusy.value = "Sending ${i + 1} of ${targets.size}…"
            sms.sendFollowUp(g).fold(
                onSuccess = { ok ->
                    if (ok) sent++ else already++
                    followUpSent.value = (followUpSent.value ?: emptySet()) + g.token
                },
                onFailure = { failed++ }
            )
            if (i < targets.lastIndex) delay(3000L)   // space the texts out
        }
        followUpBusy.value = null
        toast(buildString {
            append("Follow-up: $sent sent")
            if (already > 0) append(", $already already sent")
            if (failed > 0) append(", $failed failed (will stay unsent)")
        })
    }

    /* ---------- Manual confirmation texts ---------- */

    val confirmPending = MutableStateFlow<Map<String, List<Int>>?>(null)   // token -> untexted confirmed seats (null = loading)
    val confirmBusy = MutableStateFlow<String?>(null)

    fun confirmationMessage(g: Guest) =
        sms.confirmationMessage(g, confirmPending.value?.get(g.token).orEmpty(), smsConfig.value)

    fun loadConfirmations() = viewModelScope.launch {
        confirmPending.value = null
        sms.unsentConfirmations(repo.guests.first()).fold(
            onSuccess = { confirmPending.value = it },
            onFailure = { confirmPending.value = emptyMap(); toast("Couldn't load confirmations: ${it.friendly()}") }
        )
    }

    fun sendConfirmations(targets: List<Guest>) = viewModelScope.launch {
        if (confirmBusy.value != null || targets.isEmpty()) return@launch
        var sent = 0; var already = 0; var failed = 0
        targets.forEachIndexed { i, g ->
            confirmBusy.value = "Sending ${i + 1} of ${targets.size}…"
            sms.sendConfirmation(g).fold(
                onSuccess = { n ->
                    if (n > 0) sent++ else already++
                    confirmPending.value = confirmPending.value?.minus(g.token)
                },
                onFailure = { failed++ }
            )
            if (i < targets.lastIndex) delay(3000L)
        }
        confirmBusy.value = null
        toast(buildString {
            append("Confirmations: $sent sent")
            if (already > 0) append(", $already already sent")
            if (failed > 0) append(", $failed failed (will stay unsent)")
        })
    }

    /* ---------- Decline notices (automatic switch + manual list) ---------- */

    val declineEnabled = sms.declineEnabled.state(false)
    val declinePending = MutableStateFlow<Map<String, List<Int>>?>(null)   // token -> untexted declined seats (null = loading)
    val declineBusy = MutableStateFlow<String?>(null)

    fun setDeclineEnabled(on: Boolean) = viewModelScope.launch {
        sms.setDeclineEnabled(on, repo.guests.first()).fold(
            onSuccess = { toast(if (on) "Decline notices on. Existing declines were tagged and won't be texted." else "Decline notices off") },
            onFailure = { toast("Couldn't change the setting: ${it.friendly()}") }
        )
    }

    fun declineMessage(g: Guest) = sms.declineMessage(g, declinePending.value?.get(g.token).orEmpty(), smsConfig.value)

    fun loadDeclines() = viewModelScope.launch {
        declinePending.value = null
        sms.unsentDeclines(repo.guests.first()).fold(
            onSuccess = { declinePending.value = it },
            onFailure = { declinePending.value = emptyMap(); toast("Couldn't load declines: ${it.friendly()}") }
        )
    }

    fun sendDeclines(targets: List<Guest>) = viewModelScope.launch {
        if (declineBusy.value != null || targets.isEmpty()) return@launch
        var sent = 0; var already = 0; var failed = 0
        targets.forEachIndexed { i, g ->
            declineBusy.value = "Sending ${i + 1} of ${targets.size}…"
            sms.sendDecline(g).fold(
                onSuccess = { n ->
                    if (n > 0) sent++ else already++
                    declinePending.value = declinePending.value?.minus(g.token)
                },
                onFailure = { failed++ }
            )
            if (i < targets.lastIndex) delay(3000L)
        }
        declineBusy.value = null
        toast(buildString {
            append("Decline notices: $sent sent")
            if (already > 0) append(", $already already sent")
            if (failed > 0) append(", $failed failed (will stay unsent)")
        })
    }

    fun exportExcel(uri: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { GuestExporter.export(getApplication(), uri, guests.value) }
        }
        result.fold(
            onSuccess = { toast("Exported $it guest group(s) to Excel") },
            onFailure = { toast("Export failed: ${it.friendly()}") }
        )
    }
}