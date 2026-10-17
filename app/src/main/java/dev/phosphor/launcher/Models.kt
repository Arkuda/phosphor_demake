package dev.phosphor.launcher

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences

// Local device state only. Unknown readings stay null rather than becoming simulated data.
data class AppEntry(val label: String, val component: ComponentName)
data class Notice(val key: String, val app: String, val title: String, val text: String, val time: Long, val intent: PendingIntent?)
data class AgendaEvent(val id: Long, val title: String, val start: Long, val end: Long, val allDay: Boolean)
data class WeatherState(
    val city: String, val temperature: Double, val feels: Double, val humidity: Int,
    val code: Int, val wind: Double, val windDirection: Double, val isDay: Boolean,
    val hourlyTimes: List<String>, val hourlyTemps: List<Double>, val hourlyRain: List<Int>,
    val dailyDates: List<String>, val dailyHigh: List<Double>, val dailyLow: List<Double>,
    val dailyCodes: List<Int>, val sunrise: String, val sunset: String, val updated: Long
)
data class BatterySample(val time: Long, val level: Int, val current: Int)
data class MediaState(val title: String, val artist: String, val playing: Boolean, val position: Long, val duration: Long)

interface LauncherHost {
    val activity: Activity
    val prefs: SharedPreferences
    val device: DeviceRepository
    fun redraw()
    fun input(title: String, initial: String = "", onDone: (String) -> Unit)
    fun choose(title: String, options: List<String>, onChoose: (Int) -> Unit)
    fun open(intent: Intent)
    fun toast(message: String)
}
