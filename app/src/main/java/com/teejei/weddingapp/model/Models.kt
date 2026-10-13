package com.teejei.weddingapp.model

import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class Rsvp(val label: String) { CONFIRMED("Confirmed"), DECLINED("Declined"), AWAITING("Awaiting") }
enum class UrlState { OK, MISSING, INVALID, TOKEN_MISMATCH }
enum class SortKey(val label: String) { NAME("Name"), RSVP("RSVP"), INVITATION("Invitation") }

data class Seat(val seatNo: Int, val name: String, val attending: String)

/** One guest group = one row in the "Guest Invi" sheet = one unique invitation token. */
data class Guest(
    val token: String,
    val groupName: String,
    val maxPax: Int,
    val contact: String,
    val url: String,
    val seats: List<Seat>,
    val invitationSent: Boolean,
    val sentAt: String?,
    val sentNotes: String,
    val firstOpenedAt: String?,
    val lastOpenedAt: String?,
    val visitCount: Int,
    val pendingSync: Boolean
) {
    val confirmedSeats: Int get() = seats.count { it.attending == "attending" }
    val declinedSeats: Int get() = seats.count { it.attending == "not_attending" }
    val awaitingSeats: Int get() = (maxPax - confirmedSeats - declinedSeats).coerceAtLeast(0)

    /** The sheet's rsvp_status only says "submitted"; the real answer lives in the per-seat rows. */
    val rsvp: Rsvp
        get() = when {
            confirmedSeats > 0 -> Rsvp.CONFIRMED
            declinedSeats > 0 && awaitingSeats == 0 -> Rsvp.DECLINED
            else -> Rsvp.AWAITING
        }

    val opened: Boolean get() = visitCount > 0 || !lastOpenedAt.isNullOrBlank()

    val urlState: UrlState
        get() {
            if (url.isBlank()) return UrlState.MISSING
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return UrlState.INVALID
            if (uri.scheme != "http" && uri.scheme != "https") return UrlState.INVALID
            if (uri.host.isNullOrBlank()) return UrlState.INVALID
            // Shortened links carry no token, so only a token that is present can be checked.
            val t = uri.getQueryParameter("token") ?: return UrlState.OK
            return if (t == token) UrlState.OK else UrlState.TOKEN_MISMATCH
        }
}

data class GuestFilter(
    val query: String = "",
    val rsvp: Rsvp? = null,
    val sent: Boolean? = null,
    val opened: Boolean? = null,
    val sort: SortKey = SortKey.NAME
)

data class GuestStats(
    val groups: Int = 0, val seats: Int = 0,
    val confirmedGroups: Int = 0, val declinedGroups: Int = 0, val awaitingGroups: Int = 0,
    val confirmedSeats: Int = 0, val declinedSeats: Int = 0, val awaitingSeats: Int = 0
)

data class InviteStats(
    val sent: Int = 0, val notSent: Int = 0, val opened: Int = 0, val notOpened: Int = 0, val visits: Int = 0
)

data class MemoryStats(val photos: Int = 0, val videos: Int = 0, val uploaders: Int = 0)

fun List<Guest>.guestStats() = GuestStats(
    groups = size,
    seats = sumOf { it.maxPax },
    confirmedGroups = count { it.rsvp == Rsvp.CONFIRMED },
    declinedGroups = count { it.rsvp == Rsvp.DECLINED },
    awaitingGroups = count { it.rsvp == Rsvp.AWAITING },
    confirmedSeats = sumOf { it.confirmedSeats },
    declinedSeats = sumOf { it.declinedSeats },
    awaitingSeats = sumOf { it.awaitingSeats }
)

fun List<Guest>.inviteStats() = InviteStats(
    sent = count { it.invitationSent },
    notSent = count { !it.invitationSent },
    opened = count { it.opened },
    notOpened = count { !it.opened },
    visits = sumOf { it.visitCount }
)

fun List<Guest>.applyFilter(f: GuestFilter): List<Guest> {
    val q = f.query.trim()
    val base = filter { g ->
        (q.isEmpty() || g.groupName.contains(q, true) || g.seats.any { it.name.contains(q, true) }) &&
            (f.rsvp == null || g.rsvp == f.rsvp) &&
            (f.sent == null || g.invitationSent == f.sent) &&
            (f.opened == null || g.opened == f.opened)
    }
    val byName = compareBy<Guest, String>(String.CASE_INSENSITIVE_ORDER) { it.groupName }
    return when (f.sort) {
        SortKey.NAME -> base.sortedWith(byName)
        SortKey.RSVP -> base.sortedWith(compareBy<Guest> {
            when (it.rsvp) { Rsvp.CONFIRMED -> 0; Rsvp.AWAITING -> 1; Rsvp.DECLINED -> 2 }
        }.then(byName))
        SortKey.INVITATION -> base.sortedWith(compareBy<Guest> { it.invitationSent }.then(byName))
    }
}

private val timeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())

fun formatTime(iso: String?): String? =
    iso?.takeIf { it.isNotBlank() }?.let { runCatching { timeFmt.format(Instant.parse(it)) }.getOrDefault(it) }

fun formatMillis(ms: Long): String? =
    if (ms <= 0) null else timeFmt.format(Instant.ofEpochMilli(ms))

fun shortUrl(url: String): String {
    val s = url.removePrefix("https://").removePrefix("http://")
    return if (s.length > 48) s.take(28) + "…" + s.takeLast(18) else s
}
