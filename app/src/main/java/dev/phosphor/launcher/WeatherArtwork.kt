package dev.phosphor.launcher

import android.graphics.Paint
import kotlin.math.min

enum class WeatherArtStyle(val key: String, private val labelResource: Int) {
    LANDSCAPE("landscape", R.string.art_style_landscape),
    TERMINAL("terminal", R.string.art_style_terminal),
    POSTCARD("postcard", R.string.art_style_postcard),
    ORBITAL("orbital", R.string.art_style_orbital),
    FOREST_LAKE("forest_lake", R.string.art_style_forest_lake),
    FOREST_LAKE_TRUE_ASCII("forest_lake_true_ascii", R.string.art_style_forest_lake_true_ascii);

    val label: String get() = L10n.text(labelResource)

    companion object {
        fun fromKey(key: String?): WeatherArtStyle = when (key) {
            TERMINAL.key -> TERMINAL
            POSTCARD.key -> POSTCARD
            ORBITAL.key -> ORBITAL
            FOREST_LAKE.key -> FOREST_LAKE
            FOREST_LAKE_TRUE_ASCII.key -> FOREST_LAKE_TRUE_ASCII
            else -> LANDSCAPE
        }
    }
}

/**
 * Original ASCII drawings; no upstream source or glyph rows are incorporated.
 * Terminal-weather references researched for the visual vocabulary:
 * https://github.com/schachmat/wego (ISC), https://github.com/chubin/wttr.in (Apache-2.0).
 * These are local illustrations, not a radar feed or an additional weather provider.
 */
object WeatherArtwork {
    private enum class Condition(private val captionResource: Int) {
        CLEAR(R.string.art_weather_clear), PARTLY(R.string.art_weather_partly), CLOUDY(R.string.art_weather_cloudy),
        FOG(R.string.art_weather_fog), DRIZZLE(R.string.art_weather_drizzle), RAIN(R.string.art_weather_rain),
        SNOW(R.string.art_weather_snow), THUNDER(R.string.art_weather_thunder), UNKNOWN(R.string.art_weather_unknown);

        val caption: String get() = L10n.text(captionResource)
    }

    private class Glyph(vararg val rows: String) {
        val columns = rows.maxOf { it.length }
    }

    // All glyphs and font metrics storage are prepared once, not in the drawing loop.
    private val metrics = Paint.FontMetrics()
    private val sun = Glyph(
        "     |     ", "  \\  |  /  ", "   .===.   ",
        "-- ( O ) --", "   '==='   ", "  /  |  \\  ", "     |     "
    )
    private val moon = Glyph(
        "    ___    ", "  .'  /  . ", " /   /     ",
        "|   (      ", " \\   \\     ", "  '.__\\  + ", "           "
    )
    private val cloud = Glyph(
        "     __..__    ", "  __/      \\_  ", " /           \\ ",
        " \\___________/ "
    )
    private val overcast = Glyph(
        "  ___..___     ", "_/        \\__  ", "\\___   __..__\\ ",
        "  __\\_/      \\ ", " /            |", " \\___________/ "
    )
    private val fog = Glyph(
        " __    ____    _", "   ____    ____ ", " _    ____    __",
        "   ____    ____ ", " __    ____    _"
    )
    private val question = Glyph("  ___  ", " /   \\ ", "    /  ", "   |   ", "   .   ")
    private val rain = Glyph(" / / / / / / / ", "  / / / / / /  ", " / / / / / / / ")
    private val drizzle = Glyph("  .    .    .  ", "    .    .     ", " .    .    .   ")
    private val snow = Glyph(" *   *   *   * ", "   *   *   *   ", " *   *   *   * ")
    private val thunder = Glyph(" /   /___  /   ", "   /    /  /   ", " /     /_    / ")

    private val smallSun = Glyph(" \\ | / ", "--(o)--", " / | \\ ")
    private val smallMoon = Glyph("  _   .", " / )   ", " \\_) + ")
    private val smallCloud = Glyph("   __/\\__   ", " _/      \\_ ", "(__________)")
    private val hills = Glyph(
        "      /\\        /\\        ^      ",
        "  /\\ /  \\  /\\  /  \\      /|\\     ",
        "_/  V    \\/  \\/    \\__/ /|\\ __/\\_",
        "_________________________|_______"
    )
    private val mist = Glyph(
        "___   ______   _____   _________",
        "   ______   _______   _____     ",
        "_____    ______   ______   _____"
    )
    private val stars = Glyph(" +        .        + ", "     .         +     ")

    private val dial = Glyph(
        "          +          ",
        "     .----|----.     ",
        "   .'           '.   ",
        "  /               \\  ",
        " |                 | ",
        " |                 | ",
        "+-                 -+",
        " |                 | ",
        " |                 | ",
        "  \\               /  ",
        "   '.           .'   ",
        "     '----|----'     ",
        "          +          "
    )
    private val dialSun = Glyph("  \\ | /  ", " -- @ -- ", "  / | \\  ")
    private val dialMoon = Glyph("  /)  .  ", " ( |     ", "  \\)  +  ")
    private val dialCloud = Glyph("   .^^.  ", " .(####).", " '------'")
    private val dialFog = Glyph("==  === =", " ===  ===", "=  === ==")
    private val dialDrizzle = Glyph(" .  .  . ", "   .  .  ")
    private val dialRain = Glyph(" // // //", "// // // ")
    private val dialSnow = Glyph(" *  *  * ", "  *  *   ")
    private val dialThunder = Glyph("   /__ / ", "     /   ")
    private val dialUnknown = Glyph("   ??    ", "    ?    ", "    .    ")

    fun draw(u: TerminalUi, x: Float, y: Float, w: Float, h: Float, weather: WeatherState?, style: WeatherArtStyle) {
        if (w <= 0f || h <= 0f) return
        if (style == WeatherArtStyle.LANDSCAPE) {
            SkyRenderer.draw(u, x, y, w, h, weather)
            return
        }
        if (style == WeatherArtStyle.FOREST_LAKE) {
            ForestLakeRenderer.draw(u, x, y, w, h, weather)
            return
        }
        if (style == WeatherArtStyle.FOREST_LAKE_TRUE_ASCII) {
            ForestLakeTrueAsciiRenderer.draw(u, x, y, w, h, weather)
            return
        }
        val condition = condition(weather?.code)
        val night = weather?.isDay == false
        val columns = when (style) {
            WeatherArtStyle.TERMINAL -> 21
            WeatherArtStyle.POSTCARD -> 34
            else -> 23
        }
        val rows = when (style) {
            WeatherArtStyle.TERMINAL -> 11
            WeatherArtStyle.POSTCARD -> 12
            else -> 13
        }
        val p = u.paint
        p.textSize = 20f * u.fontScale
        p.clearShadowLayer()
        p.getFontMetrics(metrics)
        val cell = p.measureText("M")
        val line = metrics.descent - metrics.ascent
        val baseline = -metrics.ascent
        // Scale the complete monospace picture, never truncate its rows. This also
        // bounds XL text settings in the compact home rectangle and fills Zen space.
        val inset = min(4f, min(w, h) * .04f)
        val scale = min((w - inset * 2) / (columns * cell), (h - inset * 2) / (rows * line))
        val c = u.canvas
        val save = c.save()
        c.clipRect(x, y, x + w, y + h)
        c.translate(x + (w - columns * cell * scale) / 2, y + (h - rows * line * scale) / 2)
        c.scale(scale, scale)
        when (style) {
            WeatherArtStyle.TERMINAL -> {
                text(u, if (weather == null) "---" else L10n.text(if (night) R.string.art_weather_night else R.string.art_weather_day), 0f, 0, cell, line, baseline, u.ink.dim)
                when (condition) {
                    Condition.CLEAR -> centered(u, if (night) moon else sun, 21, 2, cell, line, baseline, u.ink.bright)
                    Condition.PARTLY -> {
                        glyph(u, if (night) smallMoon else smallSun, 2f, 1, cell, line, baseline, u.ink.bright)
                        glyph(u, cloud, 5f, 3, cell, line, baseline, u.ink.fg)
                    }
                    Condition.CLOUDY -> centered(u, overcast, 21, 2, cell, line, baseline, u.ink.fg)
                    Condition.FOG -> centered(u, fog, 21, 3, cell, line, baseline, u.ink.fg)
                    Condition.UNKNOWN -> centered(u, question, 21, 2, cell, line, baseline, u.ink.dim)
                    else -> {
                        centered(u, cloud, 21, 1, cell, line, baseline, u.ink.fg)
                        centered(u, precipitation(condition), 21, 5, cell, line, baseline, if (condition == Condition.THUNDER) u.ink.warn else u.ink.accent)
                    }
                }
                val caption = condition.caption
                text(u, caption, (21 - caption.length) / 2f, 10, cell, line, baseline, u.ink.dim)
            }
            WeatherArtStyle.POSTCARD -> {
                if (weather != null) glyph(u, if (night) smallMoon else smallSun, 2f, 1, cell, line, baseline, u.ink.bright)
                if (night) glyph(u, stars, 11f, 0, cell, line, baseline, u.ink.dim)
                when (condition) {
                    Condition.CLEAR -> Unit
                    Condition.UNKNOWN -> glyph(u, question, 20f, 1, cell, line, baseline, u.ink.dim)
                    Condition.FOG -> glyph(u, mist, 1f, 4, cell, line, baseline, u.ink.fg)
                    else -> {
                        if (condition == Condition.CLOUDY) glyph(u, smallCloud, 8f, 2, cell, line, baseline, u.ink.dim)
                        glyph(u, smallCloud, 19f, 2, cell, line, baseline, u.ink.fg)
                        if (condition != Condition.CLOUDY && condition != Condition.PARTLY) {
                            glyph(u, precipitation(condition), 16f, 5, cell, line, baseline, if (condition == Condition.THUNDER) u.ink.warn else u.ink.accent)
                        }
                    }
                }
                glyph(u, hills, 0f, 8, cell, line, baseline, u.ink.dim)
            }
            WeatherArtStyle.ORBITAL -> {
                centered(u, dial, 23, 0, cell, line, baseline, u.ink.dim)
                val phase = if (weather == null) "---" else L10n.text(if (night) R.string.art_weather_night else R.string.art_weather_day)
                text(u, phase, (23 - phase.length) / 2f, 2, cell, line, baseline, u.ink.accent)
                val icon = when (condition) {
                    Condition.CLEAR -> if (night) dialMoon else dialSun
                    Condition.FOG -> dialFog
                    Condition.UNKNOWN -> dialUnknown
                    else -> dialCloud
                }
                centered(u, icon, 23, 4, cell, line, baseline, u.ink.bright)
                val drops = when (condition) {
                    Condition.DRIZZLE -> dialDrizzle
                    Condition.RAIN -> dialRain
                    Condition.SNOW -> dialSnow
                    Condition.THUNDER -> dialThunder
                    else -> null
                }
                if (drops != null) centered(u, drops, 23, 7, cell, line, baseline, if (condition == Condition.THUNDER) u.ink.warn else u.ink.accent)
                if (condition == Condition.PARTLY) text(u, if (night) "+  c" else "+  o", 7f, 7, cell, line, baseline, u.ink.accent)
                val caption = condition.caption
                text(u, caption, (23 - caption.length) / 2f, 10, cell, line, baseline, u.ink.fg)
            }
            WeatherArtStyle.LANDSCAPE, WeatherArtStyle.FOREST_LAKE, WeatherArtStyle.FOREST_LAKE_TRUE_ASCII -> Unit
        }
        c.restoreToCount(save)
    }

    private fun condition(code: Int?): Condition = when (code) {
        0 -> Condition.CLEAR
        1, 2 -> Condition.PARTLY
        3 -> Condition.CLOUDY
        45, 48 -> Condition.FOG
        51, 53, 55, 56, 57 -> Condition.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> Condition.RAIN
        71, 73, 75, 77, 85, 86 -> Condition.SNOW
        95, 96, 99 -> Condition.THUNDER
        else -> Condition.UNKNOWN
    }

    private fun precipitation(condition: Condition): Glyph = when (condition) {
        Condition.DRIZZLE -> drizzle
        Condition.SNOW -> snow
        Condition.THUNDER -> thunder
        else -> rain
    }

    private fun centered(u: TerminalUi, art: Glyph, columns: Int, row: Int, cell: Float, line: Float, baseline: Float, color: Int) =
        glyph(u, art, (columns - art.columns) / 2f, row, cell, line, baseline, color)

    private fun glyph(u: TerminalUi, art: Glyph, column: Float, row: Int, cell: Float, line: Float, baseline: Float, color: Int) {
        u.paint.color = color
        for (i in art.rows.indices) u.canvas.drawText(art.rows[i], column * cell, (row + i) * line + baseline, u.paint)
    }

    private fun text(u: TerminalUi, value: String, column: Float, row: Int, cell: Float, line: Float, baseline: Float, color: Int) {
        u.paint.color = color
        u.canvas.drawText(value, column * cell, row * line + baseline, u.paint)
    }
}
