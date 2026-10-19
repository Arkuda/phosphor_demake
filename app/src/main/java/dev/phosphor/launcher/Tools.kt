package dev.phosphor.launcher

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.*

class Tools(private val host: LauncherHost) {
    private data class Task(val id: String, val text: String, val done: Boolean)
    private data class City(val label: String, val zone: String, val automatic: Boolean = false)
    private val prefs get() = host.prefs
    private val tasks = ArrayList<Task>()
    private val cities = ArrayList<City>()
    private var listPage = 0
    private var worldPage = 0
    private var notePage = 0
    private var fetchPage = 0
    private var storagePage = 0
    private var expression = ""
    private var calcResult: Calculation? = null
    private var calcError = 0
    private var calcErrorToken: String? = null
    private var notes = ""
    private var categories: List<Pair<String, Long>>? = null
    private var storageError = false
    private var loadingStorage = false
    private var closed = false
    private val executor = Executors.newSingleThreadExecutor()
    private var formatLocale: Locale? = null
    private var weekdayFormat = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private var timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private var clockTwelveFormat = DateTimeFormatter.ofPattern("hh:mm", Locale.ENGLISH)
    private var twelveHourFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private fun t(id: Int) = L10n.text(id)
    private fun refreshFormats() {
        val locale = L10n.locale
        if (formatLocale == locale) return
        formatLocale = locale
        weekdayFormat = DateTimeFormatter.ofPattern("EEE", locale)
        timeFormat = DateTimeFormatter.ofPattern("HH:mm", locale)
        twelveHourFormat = DateTimeFormatter.ofPattern("h:mm a", locale)
        clockTwelveFormat = DateTimeFormatter.ofPattern("hh:mm", locale)
    }

    init {
        val taskData = json(prefs.getString("tools_todo", "[]"))
        for (i in 0 until taskData.length()) taskData.optJSONObject(i)?.let {
            tasks.add(Task(it.optString("id", UUID.randomUUID().toString()), it.optString("text"), it.optBoolean("done")))
        }
        val worldData = json(prefs.getString("tools_world", "[]"))
        for (i in 0 until worldData.length()) worldData.optJSONObject(i)?.let {
            val zone = it.optString("zone")
            if (runCatching { ZoneId.of(zone) }.isSuccess) cities.add(City(it.optString("label", zone.substringAfterLast('/').replace('_', ' ')), zone, it.optBoolean("automatic", false)))
        }
        if (!prefs.contains("tools_world")) {
            val local = ZoneId.systemDefault().id
            cities.add(City("local", local, true))
            for ((name, zone) in listOf("london" to "Europe/London", "new york" to "America/New_York", "tokyo" to "Asia/Tokyo")) {
                if (zone != local) cities.add(City(name, zone, true))
            }
        }
        notes = prefs.getString("tools_notes", "") ?: ""
        expression = prefs.getString("tools_calc", "") ?: ""
        calculate()
        PomodoroEngine.restore(host.activity)
    }
    private fun json(value: String?) = runCatching { JSONArray(value ?: "[]") }.getOrElse { JSONArray() }
    private fun saveTasks() {
        val data = JSONArray()
        tasks.forEach { data.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
        prefs.edit().putString("tools_todo", data.toString()).apply()
        host.redraw()
    }
    private fun saveCities() {
        val data = JSONArray()
        cities.forEach { data.put(JSONObject().put("label", it.label).put("zone", it.zone).put("automatic", it.automatic)) }
        prefs.edit().putString("tools_world", data.toString()).apply()
        host.redraw()
    }
    fun close() { closed = true; executor.shutdownNow() }

    fun draw(page: String, u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        if (h < 80 || w < 100) return
        val scale = min(1f, min(h / 650f, w / 440f))
        if (scale < 1f) {
            u.canvas.save()
            u.canvas.translate(x, y)
            u.canvas.scale(scale, scale)
            val compact = TerminalUi(u.canvas, u.ink, w / scale, h / scale, u.time, u.fontScale, u.frames)
            render(page, compact, 0f, 0f, w / scale, h / scale)
            u.canvas.restore()
            compact.targets.forEach { target ->
                u.hit(target.label, x + target.rect.left * scale, y + target.rect.top * scale,
                    target.rect.width() * scale, target.rect.height() * scale, target.hold, target.click)
            }
        } else render(page, u, x, y, w, h)
    }
    private fun render(page: String, u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        refreshFormats()
        when (page) {
            "todo" -> todo(u, x, y, w, h)
            "pomodoro" -> pomodoro(u, x, y, w, h)
            "calc" -> calculator(u, x, y, w, h)
            "world" -> world(u, x, y, w, h)
            "sky" -> sky(u, x, y, w, h)
            "battery" -> battery(u, x, y, w, h)
            "storage" -> storage(u, x, y, w, h)
            "fetch" -> fetch(u, x, y, w, h)
            "notes" -> notes(u, x, y, w, h)
        }
    }
    private fun heading(u: TerminalUi, title: String, x: Float, y: Float, w: Float, h: Float, right: String = "") {
        u.panel(L10n.pageName(title), x, y, w, h, right)
    }
    private fun label(u: TerminalUi, value: String, x: Float, y: Float, w: Float, color: Int = u.ink.fg, size: Float = 19f) = u.text(value, x, y, size, color, w)
    private fun pager(u: TerminalUi, x: Float, y: Float, w: Float, page: Int, pages: Int, change: (Int) -> Unit) {
        if (pages <= 1) return
        u.button("<", x, y, 48f, 34f) { change((page - 1).coerceAtLeast(0)); host.redraw() }
        label(u, "${page + 1} / $pages", x + 65, y + 8, w - 130, u.ink.dim, 17f)
        u.button(">", x + w - 48, y, 48f, 34f) { change((page + 1).coerceAtMost(pages - 1)); host.redraw() }
    }
    private fun todo(u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        heading(u, "todo", x, y, w, h, t(R.string.tools_add))
        u.hit(t(R.string.tools_add_task), x + w - 110, y, 106f, 32f) { editTask(null) }
        val rowH = 64f
        val count = ((h - 133) / rowH).toInt().coerceAtLeast(1)
        val pages = max(1, (tasks.size + count - 1) / count)
        listPage = listPage.coerceIn(0, pages - 1)
        if (tasks.isEmpty()) {
            label(u, t(R.string.tools_todo_empty), x + 22, y + 63, w - 44, u.ink.bright)
            label(u, t(R.string.tools_todo_hint), x + 22, y + 96, w - 44, u.ink.dim)
            u.hit(t(R.string.tools_add_task), x + 15, y + 40, w - 30, h - 115) { editTask(null) }
        }
        tasks.drop(listPage * count).take(count).forEachIndexed { row, task ->
            val yy = y + 42 + row * rowH
            label(u, if (task.done) "[x]" else "[ ]", x + 18, yy + 10, 48f, if (task.done) u.ink.accent else u.ink.fg, 22f)
            label(u, task.text, x + 71, yy + 12, w - 116, if (task.done) u.ink.dim else u.ink.bright)
            u.line(x + 18, yy + rowH - 5, x + w - 18, yy + rowH - 5, u.ink.faint)
            u.hit(L10n.text(R.string.tools_toggle_task, task.text), x + 12, yy, w - 24, rowH - 3, hold = { taskMenu(task) }) {
                val index = tasks.indexOfFirst { it.id == task.id }
                if (index >= 0) { tasks[index] = tasks[index].copy(done = !tasks[index].done); saveTasks() }
            }
            u.hit(t(R.string.tools_edit_task), x + w - 46, yy, 34f, rowH - 3, hold = { taskMenu(task) }) { taskMenu(task) }
            label(u, "⋮", x + w - 38, yy + 12, 22f, u.ink.dim)
        }
        label(u, L10n.text(R.string.tools_todo_summary, tasks.count { it.done }, tasks.size), x + 20, y + h - 82, w - 40, u.ink.dim, 16f)
        pager(u, x + 20, y + h - 45, w - 40, listPage, pages) { listPage = it }
    }
    private fun taskMenu(task: Task) {
        host.choose(task.text, listOf(t(R.string.common_edit), t(if (task.done) R.string.tools_mark_incomplete else R.string.tools_mark_complete), t(R.string.tools_delete))) { action ->
            val index = tasks.indexOfFirst { it.id == task.id }
            if (index < 0) return@choose
            when (action) {
                0 -> editTask(task)
                1 -> { tasks[index] = tasks[index].copy(done = !tasks[index].done); saveTasks() }
                2 -> { tasks.removeAt(index); saveTasks() }
            }
        }
    }
    private fun editTask(task: Task?) {
        host.input(t(if (task == null) R.string.tools_add_task else R.string.tools_edit_task), task?.text ?: "") { value ->
            val text = value.trim()
            if (text.isEmpty()) { host.toast(t(R.string.tools_task_empty)); return@input }
            if (task == null) tasks.add(Task(UUID.randomUUID().toString(), text, false))
            else { val i = tasks.indexOfFirst { it.id == task.id }; if (i >= 0) tasks[i] = tasks[i].copy(text = text) }
            saveTasks()
        }
    }
    private fun pomodoro(u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        val s = PomodoroEngine.state(host.activity)
        heading(u, "pomodoro", x, y, w, h, t(if (s.running) R.string.tools_running else R.string.tools_paused))
        val diameter = min(w - 40, (h - 225).coerceAtLeast(100f))
        val cx = x + w / 2
        val cy = y + 48 + (h - 220) / 2
        val radius = diameter / 2
        val fraction = if (s.duration == 0L) 0f else s.remaining.toFloat() / s.duration
        val dots = 144
        for (i in 0 until dots) {
            val a = i * 2 * Math.PI / dots - Math.PI / 2
            val color = if (i.toFloat() / dots < fraction) u.ink.accent else u.ink.faint
            for (ring in 0..2) u.dot(cx + cos(a).toFloat() * (radius - ring * 5), cy + sin(a).toFloat() * (radius - ring * 5), 1.5f, color)
        }
        val phase = t(when (s.phase) { "focus" -> R.string.tools_focus; "short" -> R.string.tools_short_break; else -> R.string.tools_long_break })
        val phaseW = u.textWidth(phase, 20f)
        label(u, phase, cx - phaseW / 2, cy - radius - 29, w - 36, u.ink.bright)
        val seconds = (s.remaining + 999) / 1000
        val text = "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
        u.clock(text, cx - diameter * .40f, cy - diameter * .13f, diameter * .8f, diameter * .27f)
        for (i in 0..3) u.dot(cx - 33 + i * 22, cy + radius + 24, 3f, if (i < s.round % 4) u.ink.accent else u.ink.dim)
        val config = L10n.text(R.string.tools_pomo_config, prefs.getInt("pomo_focus",25), prefs.getInt("pomo_short",5), prefs.getInt("pomo_long",15))
        label(u, config, x + 24, y + h - 133, w - 48, u.ink.fg, 16f)
        u.hit(t(R.string.tools_timer_durations), x + 18, y + h - 143, w - 36, 36f) { pomodoroOptions() }
        val bw = (w - 64) / 3
        u.button(t(if (s.running) R.string.tools_pause else R.string.tools_start), x + 16, y + h - 94, bw, 40f) {
            PomodoroEngine.toggle(host.activity); requestTimerNotifications(); host.redraw()
        }
        u.button(t(R.string.tools_skip), x + 32 + bw, y + h - 94, bw, 40f) { PomodoroEngine.skip(host.activity); host.redraw() }
        u.button(t(R.string.tools_reset), x + 48 + 2 * bw, y + h - 94, bw, 40f) { PomodoroEngine.reset(host.activity); host.redraw() }
        val exact = Build.VERSION.SDK_INT < 31 || host.activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        label(u, t(if (exact) R.string.tools_alarm_active else R.string.tools_alarm_delayed), x + 20, y + h - 37, w - 40, if (exact) u.ink.dim else u.ink.warn, 15f)
        u.hit(t(R.string.tools_timer_settings), x + 16, y + h - 46, w - 32, 40f) {
            if (!exact && Build.VERSION.SDK_INT >= 31) host.open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${host.activity.packageName}")))
            else pomodoroOptions()
        }
    }
    private fun requestTimerNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && host.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            host.activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7319)
        }
    }
    private fun pomodoroOptions() {
        host.choose(t(R.string.tools_pomo_options), listOf(t(R.string.tools_focus_duration), t(R.string.tools_short_duration), t(R.string.tools_long_duration), L10n.text(R.string.tools_alerts, t(if (prefs.getBoolean("pomo_sound",true)) R.string.tools_sound_vibration else R.string.tools_silent)), t(R.string.tools_notification_settings))) { index ->
            if (index in 0..2) {
                val key = listOf("focus", "short", "long")[index]
                val phase = t(when (key) { "focus" -> R.string.tools_focus; "short" -> R.string.tools_short_break; else -> R.string.tools_long_break })
                host.input(L10n.text(R.string.tools_phase_minutes, phase), prefs.getInt("pomo_$key", listOf(25,5,15)[index]).toString()) { value ->
                    val minutes = value.trim().toIntOrNull()
                    if (minutes == null || minutes !in 1..180) host.toast(t(R.string.tools_minutes_error))
                    else { prefs.edit().putInt("pomo_$key", minutes).apply(); host.redraw() }
                }
            } else if (index == 3) { prefs.edit().putBoolean("pomo_sound", !prefs.getBoolean("pomo_sound", true)).apply(); host.redraw() }
            else host.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, host.activity.packageName))
        }
    }
    private fun calculate() {
        calcResult = null; calcError = 0; calcErrorToken = null
        if (expression.isBlank()) return
        try { calcResult = DecimalExpression(expression).parse() }
        catch (error: CalculationFailure) { calcError = error.label; calcErrorToken = error.token }
        catch (_: IllegalArgumentException) { calcError = R.string.tools_expression_error }
        catch (_: ArithmeticException) { calcError = R.string.tools_arithmetic_error }
    }
    private fun calcKey(key: String) {
        when (key) {
            "AC" -> expression = ""
            "⌫" -> expression = expression.dropLast(1)
            "=" -> if (calcResult != null) expression = decimal(calcResult!!.value)
            else -> expression += key
        }
        prefs.edit().putString("tools_calc", expression).apply()
        calculate(); host.redraw()
    }
    private fun calculator(u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        heading(u, "calc", x, y, w, h, t(R.string.tools_decimal))
        val displayH = (h * .29f).coerceIn(104f, 235f)
        label(u, if (expression.isEmpty()) "0" else expression, x + 22, y + 48, w - 44, u.ink.fg, 26f)
        val result = calcResult
        val answer = result?.let { (if (it.approximate) "≈ " else "= ") + decimal(it.value) } ?: if (expression.isBlank()) "= 0" else calcErrorToken?.let { L10n.text(calcError, it) } ?: t(calcError)
        label(u, answer, x + 22, y + 96, w - 44, if (result != null || expression.isBlank()) u.ink.bright else u.ink.dim, 24f)
        if (displayH > 150) label(u, t(R.string.tools_calc_hint), x + 22, y + displayH - 24, w - 44, u.ink.dim, 14f)
        u.hit(t(R.string.tools_edit_expression), x + 16, y + 33, w - 32, 52f) { host.input(t(R.string.tools_expression_input), expression) { expression = it; prefs.edit().putString("tools_calc", expression).apply(); calculate(); host.redraw() } }
        u.hit(t(R.string.tools_calc_result), x + 16, y + 89, w - 32, 45f, hold = {
            result?.let { val clipboard = host.activity.getSystemService(android.content.ClipboardManager::class.java); clipboard.setPrimaryClip(android.content.ClipData.newPlainText(t(R.string.tools_result), decimal(it.value))); host.toast(t(R.string.tools_result_copied)) }
        }) { if (result != null) calcKey("=") }
        val keys = listOf(listOf("AC", "(", ")", "⌫"), listOf("7", "8", "9", "÷"), listOf("4", "5", "6", "×"), listOf("1", "2", "3", "−"), listOf("0", ".", "=", "+"))
        val gap = 8f
        val cellW = (w - 40 - gap * 3) / 4
        val start = y + displayH + 22
        val cellH = ((h - displayH - 86) / 5).coerceAtLeast(24f)
        for (r in keys.indices) for (c in 0..3) {
            val key = keys[r][c]
            u.button(if (key == "AC") t(R.string.tools_calc_clear) else key, x + 20 + c * (cellW + gap), start + r * (cellH + 6), cellW, cellH) { calcKey(key) }
        }
        label(u, t(R.string.tools_calc_precision), x + 20, y + h - 29, w - 40, u.ink.dim, 13f)
    }
    private fun cityLabel(city: City): String {
        if (!city.automatic) return city.label
        return when {
            city.label == "local" -> t(R.string.tools_clock_local)
            city.zone == "Europe/London" -> t(R.string.tools_clock_london)
            city.zone == "America/New_York" -> t(R.string.tools_clock_new_york)
            city.zone == "Asia/Tokyo" -> t(R.string.tools_clock_tokyo)
            else -> city.label
        }
    }
    private fun world(u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        heading(u, "world", x, y, w, h, t(R.string.tools_add))
        u.hit(t(R.string.tools_add_city), x + w - 112, y, 108f, 32f) { chooseZone(null) }
        val perPage = ((h - 85) / 190).toInt().coerceIn(1, 4)
        val pages = max(1, (cities.size + perPage - 1) / perPage)
        worldPage = worldPage.coerceIn(0, pages - 1)
        val rowH = (h - if (pages > 1) 83 else 48) / perPage
        val now = Instant.now()
        if (cities.isEmpty()) label(u, t(R.string.tools_world_empty), x + 25, y + 70, w - 50, u.ink.dim)
        cities.drop(worldPage * perPage).take(perPage).forEachIndexed { i, city ->
            val z = now.atZone(ZoneId.of(city.zone))
            val yy = y + 24 + i * rowH
            val night = z.hour < 6 || z.hour >= 20
            u.panel(cityLabel(city), x + 12, yy, w - 24, rowH - 9, z.format(weekdayFormat).lowercase(L10n.locale))
            val twentyFour = prefs.getBoolean("clock24", true)
            val time = z.format(if (twentyFour) timeFormat else clockTwelveFormat)
            val clockH = (rowH - 88).coerceAtLeast(44f)
            u.clock(time, x + 37, yy + 26, w - 74, clockH, if (night) u.ink.fg else u.ink.bright)
            val lineY = yy + rowH - 50
            for (j in 0..40) u.dot(x + 35 + j * (w - 70) / 40, lineY, 1f, u.ink.dim)
            u.line(x + 35 + (w - 70) * .25f, lineY, x + 35 + (w - 70) * .75f, lineY, u.ink.fg)
            u.dot(x + 35 + (w - 70) * ((z.hour * 60 + z.minute) / 1440f), lineY, 4f, u.ink.accent)
            label(u, t(if (night) R.string.tools_night else R.string.tools_day) + if (!twentyFour) " · ${t(if (z.hour < 12) R.string.tools_am else R.string.tools_pm)}" else "", x + 28, lineY + 12, (w - 50) / 2, u.ink.dim, 17f)
            val diff = (z.offset.totalSeconds - ZoneId.systemDefault().rules.getOffset(now).totalSeconds) / 60
            val offset = if (diff == 0) t(R.string.tools_here) else L10n.text(if (diff > 0) R.string.tools_ahead else R.string.tools_behind, abs(diff) / 60, abs(diff) % 60)
            label(u, offset, x + w / 2, lineY + 12, w / 2 - 28, u.ink.dim, 17f)
            u.hit(L10n.text(R.string.tools_edit_city, cityLabel(city)), x + 15, yy + 8, w - 30, rowH - 17, hold = { cityMenu(city) }) { cityMenu(city) }
        }
        if (pages > 1) pager(u, x + 20, y + h - 43, w - 40, worldPage, pages) { worldPage = it }
        else label(u, t(R.string.tools_world_hint), x + 20, y + h - 24, w - 40, u.ink.dim, 13f)
    }
    private fun cityMenu(city: City) {
        host.choose(cityLabel(city) + " · " + city.zone, listOf(t(R.string.tools_rename_city), t(R.string.tools_change_zone), t(R.string.tools_remove_clock))) { option ->
            when (option) {
                0 -> host.input(t(R.string.tools_city_label), cityLabel(city)) { name -> val i = cities.indexOf(city); if (i >= 0 && name.isNotBlank()) { cities[i] = city.copy(label = name.trim(), automatic = false); saveCities() } }
                1 -> chooseZone(city)
                2 -> { cities.remove(city); saveCities() }
            }
        }
    }
    private fun chooseZone(existing: City?) {
        host.input(t(R.string.tools_find_zone), "") { query ->
            val matches = ZoneId.getAvailableZoneIds().filter { it.replace('_', ' ').contains(query.trim(), true) }.sorted()
            if (matches.isEmpty()) { host.toast(t(R.string.tools_zone_empty)); return@input }
            host.choose(t(R.string.tools_choose_zone), matches) { selected ->
                val zone = matches[selected]
                val city = City(zone.substringAfterLast('/').replace('_', ' ').lowercase(), zone, true)
                if (existing == null) cities.add(city) else { val i = cities.indexOf(existing); if (i >= 0) cities[i] = city.copy(label = existing.label, automatic = existing.automatic) }
                saveCities()
            }
        }
    }
    private fun sky(u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        heading(u, "sky", x, y, w, h, t(R.string.tools_location))
        u.hit(t(R.string.tools_sky_settings), x + w - 170, y, 164f, 32f) { weatherSettings() }
        val weather = host.device.weather
        val moonDays = ((Instant.now().epochSecond - 947182440L) / 86400.0 % 29.530588853 + 29.530588853) % 29.530588853
        val phase = moonDays / 29.530588853
        val illuminated = (1 - cos(2 * Math.PI * phase)) / 2
        val moonName = t(when (((phase * 8 + .5).toInt()) % 8) {
            0 -> R.string.tools_moon_new
            1 -> R.string.tools_moon_waxing_crescent
            2 -> R.string.tools_moon_first_quarter
            3 -> R.string.tools_moon_waxing_gibbous
            4 -> R.string.tools_moon_full
            5 -> R.string.tools_moon_waning_gibbous
            6 -> R.string.tools_moon_last_quarter
            else -> R.string.tools_moon_waning_crescent
        })
        val moonY = y + h * .66f
        val moonR = min(52f, h * .075f)
        val cx = x + w / 2
        u.dot(cx, moonY, moonR, u.ink.faint)
        // Orthographic illuminated lunar hemisphere; phase is an astronomical estimate, not a weather reading.
        for (row in -32..32) {
            val ry = row / 32f
            val span = sqrt((1 - ry * ry).coerceAtLeast(0f)) * moonR
            val terminator = cos(2 * Math.PI * phase).toFloat() * span
            val left = if (phase < .5) terminator else -span
            val right = if (phase < .5) span else -terminator
            if (right > left) u.line(cx + left, moonY + ry * moonR, cx + right, moonY + ry * moonR, u.ink.bright, max(1f, moonR / 31))
        }
        label(u, moonName, x + 24, moonY + moonR + 20, w - 48, u.ink.bright, 23f)
        label(u, L10n.text(R.string.tools_moon_detail, (illuminated * 100).roundToInt(), moonDays), x + 24, moonY + moonR + 53, w - 48, u.ink.fg, 17f)
        label(u, t(R.string.tools_moon_estimate), x + 24, y + h - 35, w - 48, u.ink.dim, 14f)
        if (weather == null) {
            label(u, t(R.string.tools_solar_unavailable), x + 24, y + 52, w - 48, u.ink.bright, 22f)
            label(u, host.device.weatherStatus, x + 24, y + 91, w - 48, u.ink.dim, 16f)
            label(u, t(R.string.tools_weather_hint), x + 24, y + 125, w - 48, u.ink.fg, 17f)
            u.button(t(R.string.tools_set_weather_city), x + 24, y + 165, w - 48, 44f) { weatherSettings() }
            return
        }
        label(u, weather.city, x + 24, y + 43, w - 48, u.ink.bright, 24f)
        val sunrise = solarTime(weather.sunrise)
        val sunset = solarTime(weather.sunset)
        label(u, L10n.text(R.string.tools_sunrise, sunrise.first), x + 24, y + 91, w - 48, u.ink.fg, 23f)
        label(u, L10n.text(R.string.tools_sunset, sunset.first), x + 24, y + 131, w - 48, u.ink.fg, 23f)
        label(u, L10n.text(R.string.tools_solar_city, weather.city), x + 24, y + 171, w - 48, u.ink.dim, 15f)
        val rise = sunrise.second
        val set = sunset.second
        if (rise != null && set != null && set > rise) {
            val now = Instant.now().epochSecond
            val progress = (now - rise).toFloat() / (set - rise)
            val baseline=y+302
            for(i in 0..64) {
                val f=i/64f
                u.dot(x+26+(w-52)*f,baseline-sin(f*Math.PI).toFloat()*83,1.2f,u.ink.dim)
            }
            u.line(x+26,baseline,x+w-26,baseline,u.ink.faint)
            if(progress in 0f..1f)u.dot(x+26+(w-52)*progress,baseline-sin(progress*Math.PI).toFloat()*83,5f,u.ink.bright)
            val day=set-rise
            label(u, L10n.text(R.string.tools_daylight, day / 3600, day % 3600 / 60, t(if (now in rise..set) R.string.tools_day else R.string.tools_night)),x+24,y+322,w-48,u.ink.accent,17f)
            goldenHour(weather,rise,set)?.let { label(u,it,x+24,y+354,w-48,u.ink.dim,14f) }
        } else label(u, t(R.string.tools_solar_date_unavailable), x + 24, y + 220, w - 48, u.ink.dim, 17f)
    }
    private fun goldenHour(weather:WeatherState,rise:Long,set:Long):String? {
        val cache=runCatching { JSONObject(prefs.getString("weather_cache","")?:"") }.getOrNull()?:return null
        val latitude=cache.optDouble("latitude",Double.NaN)
        if(!latitude.isFinite())return null
        val date=runCatching { OffsetDateTime.parse(weather.sunrise) }.getOrNull()?:return null
        val gamma=2*Math.PI*(date.dayOfYear-1)/365.2422
        val declination=.006918-.399912*cos(gamma)+.070257*sin(gamma)-.006758*cos(2*gamma)+.000907*sin(2*gamma)-.002697*cos(3*gamma)+.00148*sin(3*gamma)
        val lat=Math.toRadians(latitude)
        fun angle(altitude:Double):Double=acos((sin(Math.toRadians(altitude))-sin(lat)*sin(declination))/(cos(lat)*cos(declination)))
        val seconds=((angle(-.833)-angle(6.0))*43200/Math.PI)
        if(!seconds.isFinite()||seconds<=0||seconds>(set-rise)/2)return null
        val format=if(prefs.getBoolean("clock24",true)) timeFormat else twelveHourFormat
        fun time(epoch:Long)=Instant.ofEpochSecond(epoch).atOffset(date.offset).format(format)
        return L10n.text(R.string.tools_golden_hour, time(rise), time(rise+seconds.toLong()), time(set-seconds.toLong()), time(set))
    }
    private fun solarTime(value: String): Pair<String, Long?> {
        if (value.isBlank()) return t(R.string.common_unavailable) to null
        try { val d = OffsetDateTime.parse(value); return d.format(if (prefs.getBoolean("clock24", true)) timeFormat else twelveHourFormat) to d.toEpochSecond() } catch (_: Exception) { }
        try { val d = Instant.parse(value).atZone(ZoneId.systemDefault()); return d.format(timeFormat) to d.toEpochSecond() } catch (_: Exception) { }
        return try { LocalDateTime.parse(value).format(timeFormat) to null } catch (_: Exception) { t(R.string.common_unavailable) to null }
    }
    private fun weatherSettings() {
        host.choose(t(R.string.tools_sky_weather), listOf(t(R.string.tools_set_weather_city), t(R.string.tools_refresh_weather), t(R.string.tools_device_location), t(R.string.tools_location_permissions))) { option ->
            when (option) {
                0 -> host.input(t(R.string.tools_weather_city), prefs.getString("weather_city", "") ?: "") { city -> if (city.isNotBlank()) { host.device.refreshWeather(city.trim()); host.redraw() } }
                1 -> { host.device.refreshWeather(); host.redraw() }
                2 -> {
                    host.device.refreshWeather("")
                    if (host.activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        host.activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 7320)
                        host.toast(t(R.string.tools_location_allowed))
                    }
                    host.redraw()
                }
                3 -> host.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.activity.packageName}")))
            }
        }
    }
    private fun battery(u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        val d = host.device
        heading(u, "battery", x, y, w, h, t(if (d.charging) R.string.tools_charging else R.string.tools_on_battery))
        val level = d.batteryLevel
        label(u, if (level >= 0) "$level%" else t(R.string.common_unavailable), x + 25, y + 48, w - 50, u.ink.bright, 58f)
        u.bar(x + 25, y + 131, w - 50, 17f, level.coerceAtLeast(0) / 100f)
        val history = d.batteryHistory
        val chartY = y + 195
        val chartH = (h - 460).coerceIn(70f, 260f)
        label(u, t(R.string.tools_battery_history), x + 25, chartY - 27, w - 50, u.ink.dim, 16f)
        for (i in 0..4) {
            val yy = chartY + chartH * i / 4
            u.line(x + 48, yy, x + w - 24, yy, u.ink.faint)
            label(u, (100 - i * 25).toString(), x + 14, yy - 7, 32f, u.ink.dim, 12f)
        }
        val now = System.currentTimeMillis()
        val samples = history.filter { it.time >= now - 86_400_000 && it.level in 0..100 }
        if (samples.isEmpty()) label(u, t(R.string.tools_battery_collecting), x + 60, chartY + chartH / 2, w - 90, u.ink.dim, 16f)
        else {
            val start = max(now - 86_400_000, samples.first().time)
            val span = max(60_000L, now - start)
            var previous: Pair<Float, Float>? = null
            samples.forEach { sample ->
                val px = x + 48 + (w - 72) * ((sample.time - start).toFloat() / span).coerceIn(0f, 1f)
                val py = chartY + chartH * (1 - sample.level / 100f)
                previous?.let { u.line(it.first, it.second, px, py, u.ink.accent, 2f) }
                u.dot(px, py, 1.8f, u.ink.fg); previous = px to py
            }
            label(u, L10n.text(R.string.tools_battery_recorded, (now - samples.first().time).coerceAtLeast(0) / 60_000), x + 48, chartY + chartH + 12, w - 72, u.ink.dim, 14f)
        }
        val yy = chartY + chartH + 54
        val current = if (d.batteryCurrent == Int.MIN_VALUE || d.batteryCurrent == Int.MAX_VALUE) t(R.string.common_unavailable) else L10n.text(R.string.tools_milliamps, d.batteryCurrent / 1000)
        label(u, L10n.text(R.string.tools_battery_current, current), x + 25, yy, w - 50, u.ink.fg, 19f)
        val temperature = if (d.batteryTemp.isFinite()) "%.1f °C".format(L10n.locale, d.batteryTemp) else t(R.string.common_unavailable)
        val voltage = if (d.batteryVoltage.isFinite()) L10n.text(R.string.tools_volts, d.batteryVoltage) else t(R.string.common_unavailable)
        label(u, L10n.text(R.string.tools_battery_temperature, temperature), x + 25, yy + 33, w - 50, u.ink.fg, 19f)
        label(u, L10n.text(R.string.tools_battery_voltage, voltage), x + 25, yy + 66, w - 50, u.ink.fg, 19f)
        label(u, t(R.string.tools_battery_current_hint), x + 25, yy + 103, w - 50, u.ink.dim, 14f)
        u.button(t(R.string.tools_battery_settings), x + 24, y + h - 61, w - 48, 39f) { host.open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
    }
    private fun loadStorage() {
        if (loadingStorage || closed) return
        loadingStorage = true; storageError = false
        executor.execute {
            val result = runCatching { host.device.queryStorageCategories() }
            host.activity.runOnUiThread {
                if (!closed) {
                    loadingStorage = false
                    result.onSuccess { categories = it }.onFailure { storageError = true }
                    host.redraw()
                }
            }
        }
    }
    private fun storage(u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        if (categories == null && !loadingStorage && !storageError) loadStorage()
        val d = host.device
        heading(u, "storage", x, y, w, h, t(R.string.tools_refresh))
        u.hit(t(R.string.tools_refresh_storage), x + w - 160, y, 154f, 32f) { loadStorage(); host.redraw() }
        label(u, "${bytes(d.storageUsed)} / ${bytes(d.storageTotal)}", x + 24, y + 47, w - 48, u.ink.bright, 27f)
        u.bar(x + 24, y + 94, w - 48, 14f, if (d.storageTotal > 0) d.storageUsed.toFloat() / d.storageTotal else 0f)
        val available = if (d.storageTotal >= 0 && d.storageUsed >= 0) bytes((d.storageTotal - d.storageUsed).coerceAtLeast(0)) else t(R.string.common_unavailable)
        label(u, L10n.text(R.string.tools_storage_available, available), x + 24, y + 129, w - 48, u.ink.dim, 18f)
        val rows = categories.orEmpty()
        val perPage = ((h - 310) / 59).toInt().coerceAtLeast(1)
        val pages = max(1, (rows.size + perPage - 1) / perPage)
        storagePage = storagePage.coerceIn(0, pages - 1)
        if (loadingStorage) label(u, t(R.string.tools_storage_reading), x + 24, y + 185, w - 48, u.ink.dim)
        else if (storageError) label(u, t(R.string.tools_storage_error), x + 24, y + 185, w - 48, u.ink.warn, 17f)
        rows.drop(storagePage * perPage).take(perPage).forEachIndexed { i, (name, size) ->
            val yy = y + 182 + i * 59
            label(u, storageCategory(name), x + 24, yy, w * .6f - 24, u.ink.fg, 18f)
            label(u, bytes(size), x + w * .64f, yy, w * .36f - 24, u.ink.bright, 18f)
            u.bar(x + 24, yy + 29, w - 48, 4f, if (d.storageTotal > 0) size.toFloat() / d.storageTotal else 0f)
        }
        label(u, t(R.string.tools_storage_private), x + 24, y + h - 123, w - 48, u.ink.dim, 14f)
        label(u, t(R.string.tools_storage_unclassified), x + 24, y + h - 100, w - 48, u.ink.dim, 14f)
        pager(u, x + 24, y + h - 76, w - 48, storagePage, pages) { storagePage = it }
        u.button(t(R.string.tools_system_storage), x + 24, y + h - 42, w - 48, 32f) { host.open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
    }
    private fun storageCategory(key: String): String = when (key) {
        "Phosphor · app & data" -> t(R.string.tools_storage_app)
        "Visible photos" -> t(R.string.tools_storage_photos)
        "Visible videos" -> t(R.string.tools_storage_videos)
        "Visible audio" -> t(R.string.tools_storage_audio)
        "Visible downloads" -> t(R.string.tools_storage_downloads)
        "Other / inaccessible" -> t(R.string.tools_storage_other)
        else -> key
    }
    private fun fetch(u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        heading(u, "fetch", x, y, w, h, "android")
        val d = host.device
        val metrics = host.activity.resources.displayMetrics
        val uptime = SystemClock.elapsedRealtime() / 1000
        val rows = listOf(
            t(R.string.tools_fetch_device) to "${Build.MANUFACTURER} ${Build.MODEL}", t(R.string.tools_fetch_product) to Build.PRODUCT,
            "android" to "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}", t(R.string.tools_fetch_security) to Build.VERSION.SECURITY_PATCH,
            t(R.string.tools_fetch_build) to Build.DISPLAY, t(R.string.tools_fetch_kernel) to (System.getProperty("os.version") ?: t(R.string.common_unavailable)),
            t(R.string.tools_fetch_arch) to Build.SUPPORTED_ABIS.joinToString(", "), t(R.string.tools_fetch_uptime) to L10n.text(R.string.tools_uptime, uptime / 86400, uptime % 86400 / 3600, uptime % 3600 / 60),
            t(R.string.tools_fetch_screen) to "${metrics.widthPixels} × ${metrics.heightPixels} / ${metrics.densityDpi}dpi",
            t(R.string.tools_fetch_memory) to "${bytes(d.memoryUsed)} / ${bytes(d.memoryTotal)}", L10n.pageName("storage") to "${bytes(d.storageUsed)} / ${bytes(d.storageTotal)}",
            t(R.string.tools_fetch_network) to d.networkName, t(R.string.tools_fetch_locale) to L10n.locale.toLanguageTag(), t(R.string.tools_fetch_timezone) to ZoneId.systemDefault().id,
            t(R.string.tools_fetch_theme) to L10n.valueName(u.ink.name), t(R.string.tools_fetch_launcher) to host.activity.packageName)
        val perPage = ((h - 185) / 53).toInt().coerceAtLeast(1)
        val pages = (rows.size + perPage - 1) / perPage
        fetchPage = fetchPage.coerceIn(0, pages - 1)
        rows.drop(fetchPage * perPage).take(perPage).forEachIndexed { i, (key, value) ->
            val yy = y + 45 + i * 53
            label(u, key, x + 23, yy, 122f, u.ink.accent, 17f)
            label(u, value, x + 150, yy, w - 173, u.ink.fg, 17f)
            u.hit("$key: $value", x + 18, yy - 3, w - 36, 44f, hold = { host.toast("$key: $value") }) { host.choose(key, listOf(value)) { } }
        }
        val palette = intArrayOf(u.ink.bg, u.ink.faint, u.ink.dim, u.ink.fg, u.ink.accent, u.ink.bright, u.ink.warn)
        val sw = (w - 48) / palette.size
        palette.forEachIndexed { i, color -> u.rect(x + 24 + i * sw, y + h - 122, sw - 4, 28f, color) }
        label(u, L10n.text(R.string.tools_live_palette, L10n.valueName(u.ink.name)), x + 24, y + h - 79, w - 48, u.ink.dim, 16f)
        pager(u, x + 24, y + h - 44, w - 48, fetchPage, pages) { fetchPage = it }
    }
    private fun notes(u: TerminalUi, x: Float, y: Float, w: Float, h: Float) {
        heading(u, "notes", x, y, w, h, t(R.string.tools_edit))
        u.hit(t(R.string.tools_edit_notes), x + w - 130, y, 124f, 32f) { editNotes() }
        u.paint.textSize = 19f * u.fontScale
        val lines = ArrayList<String>()
        for (line in notes.split('\n')) {
            var rest = line
            if (rest.isEmpty()) lines.add("")
            while (rest.isNotEmpty()) {
                val length = u.paint.breakText(rest, true, w - 80, null).coerceAtLeast(1)
                lines.add(rest.take(length)); rest = rest.drop(length)
            }
        }
        val perPage = ((h - 145) / (29f * u.fontScale)).toInt().coerceAtLeast(1)
        val pages = max(1, (lines.size + perPage - 1) / perPage)
        notePage = notePage.coerceIn(0, pages - 1)
        if (notes.isEmpty()) {
            label(u, t(R.string.tools_notes_empty), x + 26, y + 57, w - 52, u.ink.dim, 21f)
            label(u, t(R.string.tools_notes_hint), x + 26, y + 96, w - 52, u.ink.fg, 18f)
        } else lines.drop(notePage * perPage).take(perPage).forEachIndexed { i, line ->
            val yy = y + 44 + i * 29f * u.fontScale
            label(u, (notePage * perPage + i + 1).toString(), x + 14, yy + 1, 34f, u.ink.dim, 12f)
            label(u, line, x + 56, yy, w - 78, u.ink.fg, 19f)
        }
        u.hit(t(R.string.tools_edit_notes), x + 12, y + 33, w - 24, h - 133, hold = { notesMenu() }) { editNotes() }
        label(u, L10n.text(R.string.tools_notes_saved, notes.length), x + 24, y + h - 83, w - 48, u.ink.dim, 15f)
        pager(u, x + 24, y + h - 44, w - 48, notePage, pages) { notePage = it }
    }
    private fun editNotes() {
        host.inputNotes(notes) { text -> notes = text; prefs.edit().putString("tools_notes", text).apply(); host.redraw() }
    }
    private fun notesMenu() {
        host.choose(t(R.string.tools_notes_title), listOf(t(R.string.common_edit), t(R.string.tools_copy_all), t(R.string.tools_delete_all))) { option ->
            when (option) {
                0 -> editNotes()
                1 -> { host.activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(t(R.string.tools_notes_title), notes)); host.toast(t(R.string.tools_notes_copied)) }
                2 -> host.choose(t(R.string.tools_notes_delete_confirm), listOf(t(R.string.common_cancel), t(R.string.tools_delete))) { choice -> if (choice == 1) { notes = ""; prefs.edit().putString("tools_notes", "").apply(); host.redraw() } }
            }
        }
    }
    private fun bytes(value: Long): String {
        if (value < 0) return t(R.string.common_unavailable)
        var amount = value.toDouble(); var index = 0
        while (amount >= 1024 && index < 4) { amount /= 1024; index++ }
        val unit = t(when (index) { 0 -> R.string.tools_bytes; 1 -> R.string.tools_kibibytes; 2 -> R.string.tools_mebibytes; 3 -> R.string.tools_gibibytes; else -> R.string.tools_tebibytes })
        return if (index == 0) "$value $unit" else "%.1f %s".format(L10n.locale, amount, unit)
    }
    private fun decimal(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}

internal data class Calculation(val value: BigDecimal, val approximate: Boolean)
internal class CalculationFailure(val label: Int, val token: String? = null) : IllegalArgumentException()

/** Decimal operations remain exact; only a non-terminating quotient is rounded (and marked). */
internal class DecimalExpression(source: String) {
    private val text = source.replace('−', '-').replace('×', '*').replace('÷', '/')
    private var index = 0
    private var approximate = false
    fun parse(): Calculation {
        val value = sum()
        spaces()
        if (index != text.length) throw CalculationFailure(R.string.tools_calc_unexpected, text[index].toString())
        return Calculation(value, approximate)
    }
    private fun spaces() { while (index < text.length && text[index].isWhitespace()) index++ }
    private fun eat(c: Char): Boolean { spaces(); if (index < text.length && text[index] == c) { index++; return true }; return false }
    private fun sum(): BigDecimal {
        var value = product()
        while (true) value = when { eat('+') -> value.add(product()); eat('-') -> value.subtract(product()); else -> return value }
    }
    private fun product(): BigDecimal {
        var value = unary()
        while (true) value = when {
            eat('*') -> value.multiply(unary())
            eat('/') -> {
                val divisor = unary()
                if (divisor.compareTo(BigDecimal.ZERO) == 0) throw CalculationFailure(R.string.tools_calc_divide_zero)
                try { value.divide(divisor) } catch (_: ArithmeticException) { approximate = true; value.divide(divisor, MathContext.DECIMAL128) }
            }
            else -> return value
        }
    }
    private fun unary(): BigDecimal {
        if (eat('+')) return unary()
        if (eat('-')) return unary().negate()
        var value = primary()
        while (eat('%')) value = value.movePointLeft(2)
        return value
    }
    private fun primary(): BigDecimal {
        if (eat('(')) { val value = sum(); if (!eat(')')) throw CalculationFailure(R.string.tools_calc_missing_bracket); return value }
        spaces()
        val start = index
        var digits = 0
        while (index < text.length && text[index].isDigit()) { index++; digits++ }
        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && text[index].isDigit()) { index++; digits++ }
        }
        if (digits == 0) throw CalculationFailure(R.string.tools_calc_number_expected)
        return text.substring(start, index).toBigDecimal()
    }
}
