package com.teejei.weddingapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.teejei.weddingapp.data.*
import com.teejei.weddingapp.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app.applicationContext)
    private fun <T> Flow<T>.state(init: T) = stateIn(viewModelScope, SharingStarted.Eagerly, init)

    val settings = repo.settings.flow.state(AppSettings())
    val guests = repo.guests.state(emptyList())
    val media = repo.media.state(emptyList())
    val memoryStats = repo.memoryStats.state(MemoryStats())
    val guestStats = guests.map { it.guestStats() }.state(GuestStats())
    val inviteStats = guests.map { it.inviteStats() }.state(InviteStats())

    val filter = MutableStateFlow(GuestFilter())
    val visibleGuests = combine(guests, filter) { g, f -> g.applyFilter(f) }.state(emptyList())

    val refreshing = MutableStateFlow(false)
    val guestError = MutableStateFlow<String?>(null)
    val memoryError = MutableStateFlow<String?>(null)
    val importResult = MutableStateFlow<String?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    fun toast(m: String) { _messages.tryEmit(m) }

    init { refresh() }

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
}
