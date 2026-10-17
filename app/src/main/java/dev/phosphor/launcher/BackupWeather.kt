package dev.phosphor.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Keep one cache/renderer format; retain wttr.in's actual three-hour forecast samples. */
internal object BackupWeather {
    private val solarFormat = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)

    fun normalize(raw: JSONObject, latitude: Double, longitude: Double, zone: ZoneId, updated: Long): JSONObject {
        val now = Instant.ofEpochMilli(updated).atZone(zone)
        val current = raw.getJSONArray("current_condition").getJSONObject(0)
        val days = raw.getJSONArray("weather")
        val first = days.getJSONObject(0)
        val date = LocalDate.parse(first.getString("date"))
        val astronomy = first.getJSONArray("astronomy").getJSONObject(0)
        val rise = LocalTime.parse(astronomy.getString("sunrise"), solarFormat)
        val set = LocalTime.parse(astronomy.getString("sunset"), solarFormat)
        val times = JSONArray(); val temperatures = JSONArray(); val rain = JSONArray()
        val dates = JSONArray(); val highs = JSONArray(); val lows = JSONArray(); val codes = JSONArray()
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            val dayDate = LocalDate.parse(day.getString("date"))
            dates.put(dayDate.toString()); highs.put(day.getDouble("maxtempC")); lows.put(day.getDouble("mintempC"))
            val hours = day.getJSONArray("hourly")
            var noonCode = -1
            for (j in 0 until hours.length()) {
                val hour = hours.getJSONObject(j)
                val stamp = hour.getInt("time")
                val time = dayDate.atTime(stamp / 100, stamp % 100)
                times.put(time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                temperatures.put(hour.getDouble("tempC"))
                rain.put(if (hour.has("chanceofrain")) hour.getInt("chanceofrain") else JSONObject.NULL)
                if (stamp == 1200) noonCode = condition(hour.getInt("weatherCode"))
            }
            codes.put(noonCode)
        }
        return JSONObject()
            .put("phosphor_source", "wttr.in")
            .put("latitude", latitude).put("longitude", longitude)
            .put("timezone", zone.id).put("utc_offset_seconds", now.offset.totalSeconds)
            .put("current", JSONObject()
                .put("time", now.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .put("temperature_2m", current.getDouble("temp_C"))
                .put("apparent_temperature", current.getDouble("FeelsLikeC"))
                .put("relative_humidity_2m", current.getInt("humidity"))
                .put("weather_code", condition(current.getInt("weatherCode")))
                .put("wind_speed_10m", current.getDouble("windspeedKmph"))
                .put("wind_direction_10m", current.getDouble("winddirDegree"))
                .put("is_day", if (now.toLocalTime() >= rise && now.toLocalTime() < set) 1 else 0))
            .put("hourly", JSONObject().put("time", times).put("temperature_2m", temperatures).put("precipitation_probability", rain))
            .put("daily", JSONObject().put("time", dates).put("temperature_2m_max", highs).put("temperature_2m_min", lows)
                .put("weather_code", codes)
                .put("sunrise", JSONArray().put(date.atTime(rise).toString()))
                .put("sunset", JSONArray().put(date.atTime(set).toString())))
    }

    // WorldWeatherOnline conditions mapped to the WMO categories used by SkyRenderer.
    private fun condition(code: Int): Int = when (code) {
        113 -> 0
        116 -> 2
        119, 122 -> 3
        143, 248 -> 45
        260 -> 48
        263, 266 -> 51
        185, 281 -> 56
        284 -> 57
        176, 293, 296 -> 61
        299, 302 -> 63
        305, 308 -> 65
        311 -> 66
        314 -> 67
        179, 323, 326 -> 71
        329, 332 -> 73
        227, 230, 335, 338 -> 75
        182, 317, 320, 350 -> 77
        353 -> 80
        356 -> 81
        359 -> 82
        362, 368, 374 -> 85
        365, 371, 377 -> 86
        200, 386, 389, 392, 395 -> 95
        else -> -1
    }
}
