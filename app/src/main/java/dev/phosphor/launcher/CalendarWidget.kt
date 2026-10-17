package dev.phosphor.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class CalendarWidget(private val host: MainActivity) {
    private class EventRow(
        val title: String,
        val detail: String,
        val target: String,
        val open: () -> Unit
    )

    private var source: List<AgendaEvent>? = null
    private var rows: List<EventRow> = emptyList()
    private var zone = ZoneId.systemDefault()
    private var nextZoneCheck = 0L
    private var nextChange = 0L
    private var lastNow = 0L
    private val allEvents: () -> Unit = { host.openAgenda() }
    private val allowCalendar: () -> Unit = {
        host.permission(Manifest.permission.READ_CALENDAR) { host.device.refreshCalendar() }
    }
    private val openCalendar: () -> Unit = {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time").appendPath(System.currentTimeMillis().toString()).build()
        host.open(Intent(Intent.ACTION_VIEW, uri))
    }

    fun draw(u: TerminalUi, x: Float, y: Float, w: Float, h: Float, compact: Boolean = false) {
        if (w <= 0f || h <= 0f) return
        val saved = u.canvas.save()
        u.canvas.clipRect(x, y, x + w, y + h)
        try {
            // Reserve the action separately: neither large text nor short panels can cover it.
            val scale = u.fontScale.coerceAtLeast(.1f)
            val headerSize = min(20f * scale, h * .29f)
            val actionSize = min(18f * scale, headerSize)
            val headerH = min(h, max(24f, headerSize + 8f))
            val inset = min(14f, w * .04f)
            val action = if (compact) "all ›" else "calendar ›"
            val actionW = min(w * .42f, u.textWidth(action, actionSize / scale) + inset * 2)
            val actionX = x + w - actionW
            val headerY = y + (headerH - headerSize) / 2
            if (u.frames) {
                u.line(x, y + 9f, x + w, y + 9f, u.ink.dim)
                u.line(x, y + 9f, x, y + h, u.ink.dim)
                u.line(x + w, y + 9f, x + w, y + h, u.ink.dim)
                u.line(x, y + h, x + w, y + h, u.ink.dim)
            }
            u.rect(x + inset / 2, y, max(0f, w - inset), headerH, u.ink.bg)
            u.text(if (compact) "upcoming" else "calendar agenda", x + inset, headerY,
                headerSize / scale, u.ink.accent, max(0f, actionX - x - inset * 2))
            u.text(action, actionX + inset / 2, y + (headerH - actionSize) / 2,
                actionSize / scale, u.ink.dim, max(0f, actionW - inset))
            u.hit(if (compact) "all ›" else "open calendar", actionX, y, actionW, headerH,
                click = if (compact) allEvents else openCalendar)

            val contentY = y + headerH + 2f
            val contentH = max(0f, y + h - 4f - contentY)
            val contentW = max(0f, w - inset * 2)
            if (contentH <= 0f || contentW <= 0f) return
            if (host.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                // The complete body is the permission target, including on a 70px home panel.
                state(u, x + inset, contentY, contentW, contentH,
                    "calendar access is off", "allow calendar ›", scale, allowCalendar)
                return
            }
            refreshRows(System.currentTimeMillis())
            if (rows.isEmpty()) {
                val size = min(20f * scale, contentH)
                u.text("no upcoming events", x + inset, contentY + (contentH - size) / 2,
                    size / scale, u.ink.dim, contentW)
                return
            }

            val preferredRowH = 21f * scale + 18f * scale + 15f
            val count = min(rows.size, max(1, ((contentH + 5f) / preferredRowH).toInt()))
            val rowH = min(preferredRowH, contentH / count)
            val gap = min(4f, rowH * .08f)
            // Only shrink when necessary to retain both title and date/time at short heights.
            val textRoom = max(0f, rowH - gap * 2)
            val titleSize = min(21f * scale, textRoom * .55f)
            val detailSize = min(18f * scale, textRoom - titleSize)
            for (i in 0 until count) {
                val row = rows[i]
                val top = contentY + i * rowH
                u.text(row.title, x + inset, top + gap / 2, titleSize / scale, u.ink.fg, contentW)
                u.text(row.detail, x + inset, top + gap + titleSize, detailSize / scale,
                    u.ink.accent, contentW)
                if (i + 1 < count) u.line(x + inset, top + rowH - 1f, x + w - inset,
                    top + rowH - 1f, u.ink.faint)
                u.hit(row.target, x + inset / 2, top, w - inset, rowH, click = row.open)
            }
        } finally {
            u.canvas.restoreToCount(saved)
        }
    }

    private fun state(
        u: TerminalUi, x: Float, y: Float, w: Float, h: Float,
        message: String, action: String, scale: Float, click: () -> Unit
    ) {
        val gap = min(5f, h * .08f)
        val size = min(19f * scale, (h - gap) / 2)
        val top = y + max(0f, (h - size * 2 - gap) / 2)
        u.text(message, x, top, size / scale, u.ink.dim, w)
        u.text(action, x, top + size + gap, size / scale, u.ink.accent, w)
        u.hit("allow calendar", x, y, w, h, click = click)
    }

    private fun refreshRows(now: Long) {
        val events = host.device.events
        var zoneChanged = false
        if (source !== events || now >= nextZoneCheck || now < lastNow) {
            val currentZone = ZoneId.systemDefault()
            zoneChanged = currentZone != zone
            zone = currentZone
            nextZoneCheck = now + 60_000L
        }
        val clockMovedBack = now < lastNow
        lastNow = now
        if (source === events && !zoneChanged && !clockMovedBack && now < nextChange) return
        source = events
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        nextChange = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val projected = ArrayList<EventRow>(events.size)
        for (event in events) {
            val detail: String
            if (event.allDay) {
                // CalendarProvider stores all-day dates in UTC, not in the device time zone.
                val start = Instant.ofEpochMilli(event.start).atZone(ZoneOffset.UTC).toLocalDate()
                val rawEnd = Instant.ofEpochMilli(event.end).atZone(ZoneOffset.UTC).toLocalDate()
                val end = if (rawEnd > start) rawEnd else start.plusDays(1)
                val localEnd = end.atStartOfDay(zone).toInstant().toEpochMilli()
                if (localEnd <= now) continue
                nextChange = min(nextChange, localEnd)
                val first = if (start < today) today else start
                val last = end.minusDays(1)
                detail = if (first == last) "${date(first, today)} · all day"
                    else "${date(first, today)}–${date(last, today)} · all day"
            } else {
                val expires = max(event.start, event.end)
                if (expires <= now) continue
                nextChange = min(nextChange, expires)
                val start = Instant.ofEpochMilli(event.start).atZone(zone)
                val end = Instant.ofEpochMilli(expires).atZone(zone)
                if (event.start > now) nextChange = min(nextChange, event.start)
                val startDate = start.toLocalDate()
                val endDate = end.toLocalDate()
                val ending = if (endDate == (if (event.start <= now) today else startDate))
                    end.format(TIME) else "${date(endDate, today)} ${end.format(TIME)}"
                detail = when {
                    event.start <= now -> "today · now–$ending"
                    expires == event.start -> "${date(startDate, today)} · ${start.format(TIME)}"
                    else -> "${date(startDate, today)} · ${start.format(TIME)}–$ending"
                }
            }
            val title = event.title.ifBlank { "untitled event" }
            val open: () -> Unit = {
                host.open(Intent(Intent.ACTION_VIEW,
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end))
            }
            projected.add(EventRow(title, detail, "event ${event.title}", open))
        }
        rows = projected
    }

    private fun date(value: LocalDate, today: LocalDate): String = when (value) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> value.format(DATE).lowercase(Locale.ENGLISH)
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    }
}
