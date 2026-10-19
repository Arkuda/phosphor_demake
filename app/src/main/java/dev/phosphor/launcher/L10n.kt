package dev.phosphor.launcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** Shared by the Activity, receivers and already-running wallpaper engines. */
object L10n {
    private data class Language(val key: String, val locale: Locale, val resources: Resources)
    private lateinit var application: Context
    private lateinit var preferences: SharedPreferences
    @Volatile private var selected: Language? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "language") refresh()
    }
    val context: Context get() = application
    val locale: Locale get() = selected!!.locale
    val isRussian: Boolean get() = selected!!.key == "ru"
    val language: String get() = selected!!.key

    fun initialize(context: Context) {
        application = context.applicationContext
        preferences = application.getSharedPreferences("phosphor", Context.MODE_PRIVATE)
        refresh()
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }
    fun refresh() {
        val key = if (preferences.getString("language", "en") == "ru") "ru" else "en"
        if (selected?.key == key) return
        val locale = if (key == "ru") Locale.forLanguageTag("ru-RU") else Locale.ENGLISH
        val configuration = Configuration(application.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        selected = Language(key, locale, application.createConfigurationContext(configuration).resources)
        PomodoroEngine.refreshLabels(application)
    }
    fun text(id: Int): String = selected!!.resources.getString(id)
    fun text(id: Int, vararg args: Any): String = selected!!.resources.getString(id, *args)
    fun onOff(value: Boolean): String = text(if (value) R.string.common_on else R.string.common_off)
    fun pageName(key: String): String = when (key) {
        "dashboard" -> text(R.string.page_dashboard)
        "weather" -> text(R.string.page_weather)
        "inbox" -> text(R.string.page_inbox)
        "monitor" -> text(R.string.page_monitor)
        "zen" -> text(R.string.page_zen)
        "art" -> text(R.string.page_art)
        "agenda" -> text(R.string.page_agenda)
        "media" -> text(R.string.page_media)
        "apps" -> text(R.string.page_apps)
        "favorites" -> text(R.string.page_favorites)
        "today" -> text(R.string.page_today)
        "notes" -> text(R.string.page_notes)
        "todo" -> text(R.string.page_todo)
        "pomodoro" -> text(R.string.page_pomodoro)
        "calc" -> text(R.string.page_calc)
        "world" -> text(R.string.page_world)
        "sky" -> text(R.string.page_sky)
        "battery" -> text(R.string.page_battery)
        "storage" -> text(R.string.page_storage)
        "fetch" -> text(R.string.page_fetch)
        else -> key
    }
    fun actionName(key: String): String = when (key) {
        "torch", "flashlight" -> text(R.string.action_torch)
        "wifi", "wi-fi" -> "Wi-Fi"
        "bluetooth" -> "Bluetooth"
        "sound", "ringer" -> text(R.string.action_sound)
        "do not disturb", "dnd" -> text(R.string.action_dnd)
        "location", "gps" -> text(R.string.action_location)
        "battery saver", "saver" -> text(R.string.action_saver)
        "settings" -> text(R.string.action_settings)
        "internet", "network" -> text(R.string.action_internet)
        "alarm", "alarms", "clock" -> text(R.string.action_alarm)
        "calendar" -> text(R.string.action_calendar)
        "camera" -> text(R.string.action_camera)
        "calc", "calculator" -> text(R.string.page_calc)
        "notifications", "notification access" -> text(R.string.action_notifications)
        "display", "brightness" -> text(R.string.action_display)
        "volume" -> text(R.string.action_volume)
        "airplane mode", "airplane" -> text(R.string.action_airplane)
        "hotspot", "tethering" -> text(R.string.action_hotspot)
        "search", "browser", "web" -> text(R.string.action_search)
        else -> key
    }
    fun valueName(key: String): String = when (key) {
        "on", "true" -> text(R.string.common_on)
        "off", "false" -> text(R.string.common_off)
        "rain" -> text(R.string.value_rain)
        "stars" -> text(R.string.value_stars)
        "embers" -> text(R.string.value_embers)
        "snow" -> text(R.string.value_snow)
        "small" -> text(R.string.value_small)
        "medium" -> text(R.string.value_medium)
        "large" -> text(R.string.value_large)
        "xl" -> text(R.string.value_xl)
        "match" -> text(R.string.value_match)
        "phosphor" -> "phosphor"
        "amber" -> text(R.string.value_amber)
        "matrix" -> text(R.string.value_matrix)
        "ice" -> text(R.string.value_ice)
        "crimson" -> text(R.string.value_crimson)
        "violet" -> text(R.string.value_violet)
        "cyan" -> text(R.string.value_cyan)
        "sakura" -> text(R.string.value_sakura)
        "toxic" -> text(R.string.value_toxic)
        "navy" -> text(R.string.value_navy)
        "paper" -> text(R.string.value_paper)
        "chalk" -> text(R.string.value_chalk)
        "riso" -> text(R.string.value_riso)
        "forest" -> text(R.string.value_forest)
        "ring", "sound" -> text(R.string.value_ring)
        "vibr", "vibrate" -> text(R.string.value_vibrate)
        "mute", "silent" -> text(R.string.value_mute)
        else -> key
    }
}

class PhosphorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        L10n.initialize(this)
    }
}
