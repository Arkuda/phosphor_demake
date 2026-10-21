package dev.phosphor.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.*

/** Original procedural glyph painting. Its natural colors deliberately do not follow launcher ink. */
internal object ForestLakeRenderer {
    private const val COLS = 512
    private const val MAX_ROWS = 640
    private const val CELL = 6f
    private const val LINE = 8f
    private const val SKY = 0
    private const val ROCK = 1
    private const val TREE = 2
    private const val BANK = 3
    private const val WATER = 4
    private const val LIGHT = 5
    private const val ICE = 6
    private const val BARK = 7
    private val glyphs = CharArray(COLS * MAX_ROWS)
    private val shades = ByteArray(glyphs.size)
    private val surfaces = ByteArray(glyphs.size)
    private val colors = IntArray(256)
    private val backgrounds = IntArray(128)
    private val skyBack = IntArray(MAX_ROWS)
    private val heights = IntArray(COLS)
    private val waterGlyphs = CharArray(COLS)
    private val waterShades = ByteArray(COLS)
    private val dropX = IntArray(84)
    private val dropY = IntArray(84)
    private val ringX = FloatArray(20) { cos(it * (2 * PI / 20)).toFloat() }
    private val ringY = FloatArray(20) { sin(it * (2 * PI / 20)).toFloat() }
    private val one = CharArray(1)
    private var scene: Bitmap? = null
    private val sceneBounds = RectF()
    private val scenePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rows = 0
    private var horizon = 0
    private var terrainRows = 0
    private var mode = -1
    private var day = false
    private var known = false
    private var snow = false
    private var cloudy = false

    private fun hash(x: Int, y: Int = 0): Int {
        var n = x * 374761393 + y * 668265263 + 1274126177
        n = (n xor (n ushr 13)) * 1274126177
        return (n xor (n ushr 16)) and Int.MAX_VALUE
    }
    private fun random(x: Int, y: Int = 0) = hash(x, y) % 10000 / 10000f
    private fun noise(x: Float, seed: Int): Float {
        val i = floor(x).toInt()
        val f = x - i
        val t = f * f * (3 - 2 * f)
        return random(i, seed) * (1 - t) + random(i + 1, seed) * t
    }
    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt())
    private fun rgb(hex: Int) = hex or (0xff shl 24)
    private fun ramp(material: Int, low: Int, high: Int) {
        for (i in 0..15) colors[material * 16 + i] = blend(rgb(low), rgb(high), i / 15f)
    }

    fun draw(u: TerminalUi, x: Float, y: Float, w: Float, h: Float, weather: WeatherState?) {
        if (w <= 0 || h <= 0) return
        val count = (COLS * CELL * h / (w * LINE)).roundToInt().coerceIn(30, MAX_ROWS)
        val code = weather?.code
        val snowy = code in 71..77 || code in 85..86
        val rainy = code in 51..67 || code in 80..82 || code in 95..99
        val foggy = code == 45 || code == 48
        val overcast = code == 3 || rainy || snowy || foggy
        val next = (if (weather != null) 1 else 0) or (if (weather?.isDay == true) 2 else 0) or
            (if (snowy) 4 else 0) or (if (overcast) 8 else 0)
        if (rows != count || mode != next) {
            rows = count; mode = next; known = weather != null
            day = weather?.isDay == true; snow = snowy; cloudy = overcast
            palette()
            build()
            paint.typeface = u.paint.typeface
            paint.textSize = 10f
            paint.isFakeBoldText = true
            record()
        }
        val canvas = u.canvas
        val save = canvas.save()
        canvas.clipRect(x, y, x + w, y + h)
        val scale = max(w / (COLS * CELL), h / (rows * LINE))
        canvas.translate(x + (w - COLS * CELL * scale) / 2, y + (h - rows * LINE * scale) / 2)
        canvas.scale(scale, scale)
        scene?.let { canvas.drawBitmap(it, null, sceneBounds, scenePaint) }
        sky(canvas, u.time, code == 1 || code == 2)
        water(canvas, u.time, weather?.wind?.toFloat() ?: 0f)
        if (foggy) mist(canvas, u.time)
        if (snowy) snowfall(canvas, u.time)
        if (rainy) rainfall(canvas, u.time, code in 51..57)
        canvas.restoreToCount(save)
    }

    private fun palette() {
        when {
            !known || cloudy -> {
                ramp(SKY, 0x172936, 0xa0b6bd)
                ramp(ROCK, 0x142632, 0x91a7b1)
                ramp(TREE, 0x020c10, 0x436159)
                ramp(BANK, 0x101e24, 0x9ba69b)
                ramp(WATER, 0x0b202b, 0x779cae)
                ramp(LIGHT, 0x567e94, 0xe4f4ff)
                ramp(ICE, 0x648aa4, 0xf0faff)
                ramp(BARK, 0x0a1318, 0x6f7770)
            }
            day -> {
                ramp(SKY, 0x273a50, 0xffca72)
                ramp(ROCK, 0x253c45, 0xb9b7a3)
                ramp(TREE, 0x09281b, 0x9fb765)
                ramp(BANK, 0x292b23, 0xd1b07b)
                ramp(WATER, 0x0b3047, 0x8ac5dc)
                ramp(LIGHT, 0xa66427, 0xffeeac)
                ramp(ICE, 0x8ca4ad, 0xffe8c1)
                ramp(BARK, 0x151c18, 0xa18a51)
            }
            else -> {
                ramp(SKY, 0x010916, 0x456c91)
                ramp(ROCK, 0x0a1a2a, 0x7197ba)
                ramp(TREE, 0x01070d, 0x244c50)
                ramp(BANK, 0x081421, 0x688aa1)
                ramp(WATER, 0x041525, 0x4d84b8)
                ramp(LIGHT, 0x3c72ab, 0xe2f5ff)
                ramp(ICE, 0x537fa9, 0xd5ecff)
                ramp(BARK, 0x030b13, 0x465f6d)
            }
        }
        if (snow) {
            ramp(TREE, 0x030c14, 0x42605f)
            ramp(BANK, 0x172b3e, 0xb4cedc)
        }
        for (i in 0 until 128) {
            val material = i / 16
            backgrounds[i] = blend(rgb(0x01050a), colors[i], when (material) {
                SKY -> .36f
                TREE, BARK -> .14f
                else -> .3f
            })
            colors[i + 128] = blend(colors[WATER * 16 + 2], colors[i], .63f)
        }
    }

    private fun put(x: Int, y: Int, ch: Char, material: Int, tone: Int, surface: Int = material) {
        if (x !in 0 until COLS || y !in 0 until rows) return
        val i = y * COLS + x
        glyphs[i] = ch; shades[i] = (material * 16 + tone.coerceIn(0, 15)).toByte(); surfaces[i] = surface.toByte()
    }
    private fun peak(x: Float, center: Float, height: Float, width: Float): Float =
        max(0f, 1f - abs(x - center) / width).pow(.85f) * height

    private fun build() {
        glyphs.fill(' '); shades.fill(0); surfaces.fill(0)
        horizon = (rows * .76f).roundToInt()
        // A taller viewport reveals more sky/water, not vertically stretched ridges.
        // Terrain proportions are tied to the scene width, independently of its height.
        terrainRows = min(rows, (COLS * .3f).roundToInt())
        skyField()
        mountains()
        // Far treeline and overlapping wooded foothills are deliberately small and hazy.
        for (layer in 0..2) for (i in 0..104) {
            val x = (i * COLS / 105f + random(i, layer) * 2).toInt()
            val base = horizon - 1 - layer
            val shoulder = abs(x.toFloat() / COLS - .54f)
            val height = (terrainRows * (.035f + shoulder * .18f) + random(i, layer + 5) * terrainRows * .075f).toInt()
            tree(x, base, height, layer, i + layer * 117)
        }
        shores()
        // Irregular framing, not an evenly spaced row of geometric triangles.
        for (side in 0..1) for (i in 8 downTo 0) {
            val nx = .015f + i * .027f + random(i, 81 + side) * .018f
            val x = ((if (side == 0) nx else 1 - nx) * COLS).toInt()
            val base = (rows * (.98f - nx * .48f)).toInt()
            val height = (min(rows.toFloat(), terrainRows * 1.6f) * (.98f - nx * 2.5f) * (.72f + random(i, side + 91) * .36f)).toInt()
            tree(x, base, height, 3, i + side * 53)
        }
        for (side in 0..1) {
            val x = if (side == 0) (COLS * .085f).toInt() else (COLS * .94f).toInt()
            trunk(x, side)
            for (i in 0..19) {
                val nx = .02f + random(i, side + 120) * .26f
                val cx = ((if (side == 0) nx else 1 - nx) * COLS).toInt()
                val cy = (rows * (.85f + nx * .4f + random(i, side + 121) * .04f)).toInt().coerceAtMost(rows - 2)
                boulder(cx, cy, 4 + hash(i, side + 122) % 11, 2 + hash(i, side + 123) % 4, i)
            }
        }
        for (i in dropX.indices) {
            val y = horizon + 1 + hash(i, 71) % max(1, rows - horizon - 2)
            var x = COLS / 2
            for (attempt in 0..23) {
                val candidate = hash(i + attempt * 109, 81) % COLS
                if (surfaces[y * COLS + candidate].toInt() == WATER) { x = candidate; break }
            }
            dropX[i] = x; dropY[i] = y
        }
    }

    private fun skyField() {
        for (y in 0 until rows) {
            val ny = y.toFloat() / horizon
            val glow = (ny * .86f).coerceIn(0f, 1f)
            skyBack[y] = if (day && !cloudy) blend(rgb(0x172324), rgb(0xe8ad41), (ny * .6f + .15f).coerceIn(0f, 1f))
                else blend(rgb(0x010711), colors[SKY * 16 + (glow * 15).toInt()], if (cloudy) .58f else .5f)
            if (y >= horizon) continue
            for (x in 0 until COLS) {
                val cloud = noise(x * .045f + sin(y * .2f) * 2, y / 3 + 41) * .6f + noise(x * .11f, y / 2 + 42) * .4f
                val band = sin(y * .52f + noise(x * .023f, 43) * 4)
                val density = .35f + cloud * .44f + max(0f, band) * .14f
                val light = (ny * 6 + cloud * 7 + band * 2).roundToInt().coerceIn(0, 15)
                val ch = if (random(x, y) < density) {
                    if (band > .3f) "_-~=:"[hash(x, y) % 5] else ".,:;'"[hash(x, y) % 5]
                } else ' '
                val warmCloud = day && !cloudy && band > -.2f
                put(x, y, ch, if (warmCloud) LIGHT else SKY, if (warmCloud) (light + 2).coerceAtMost(15) else light, SKY)
            }
        }
        if (known && !cloudy) {
            val cx = (COLS * .715f).toInt(); val cy = (rows * .23f).toInt().coerceAtLeast(4)
            for (dy in -4..4) for (dx in -6..6) {
                val radius = dx * dx / 29f + dy * dy / 16f
                if (radius > 1) continue
                put(cx + dx, cy + dy, if (radius > .78f) '.' else "@%#*"[hash(dx, dy) % 4], LIGHT,
                    (15 - radius * 2 - (if (!day) random(dx, dy) * 3 else 0f)).toInt(), LIGHT)
            }
        }
    }

    private fun mountains() {
        for (layer in 0..2) {
            for (x in 0 until COLS) {
                val nx = x.toFloat() / COLS
                val ridge = when (layer) {
                    0 -> max(peak(nx, .24f, .31f, .2f), max(peak(nx, .61f, .5f, .25f), peak(nx, .83f, .34f, .19f)))
                    1 -> max(peak(nx, .37f, .32f, .22f), max(peak(nx, .58f, .45f, .18f), peak(nx, .76f, .29f, .19f)))
                    else -> max(peak(nx, .17f, .27f, .29f), peak(nx, .88f, .29f, .28f))
                }
                val rough = (noise(x * .14f, layer + 3) - .5f) * .028f + (noise(x * .43f, layer + 4) - .5f) * .013f
                heights[x] = (horizon - terrainRows * (ridge + rough)).roundToInt().coerceIn(2, horizon)
            }
            for (x in 0 until COLS) {
                val top = heights[x]
                val slope = (heights[min(COLS - 1, x + 2)] - heights[max(0, x - 2)]) / 4f
                for (y in top until horizon) {
                    val depth = (y - top).toFloat() / max(1, horizon - top)
                    val fold = noise(x * .2f + depth * 12, layer + 8) * .6f + noise(x * .6f + depth * 5, 14) * .4f
                    val face = (.48f + slope * .16f + (fold - .5f) * .85f).coerceIn(.05f, 1f)
                    val gully = abs(sin(x * .29f + (y - top) * .24f + noise(x * .07f, 18) * 7))
                    val cap = depth < (if (snow) .33f else .11f) + fold * .1f || (gully < .13f && depth < .6f && face > .4f)
                    val shade = (3 + face * 9 - depth * 3 + layer).toInt().coerceIn(0, 15)
                    val material = if (cap) ICE else ROCK
                    val ch = when {
                        cap -> "_,:~=^"[hash(x, y) % 6]
                        gully < .18f -> if (slope > 0) '/' else '\\'
                        else -> ".,:;irsx"[hash(x, y) % 8]
                    }
                    put(x, y, ch, material, if (cap) min(15, shade + 3) else shade, ROCK)
                }
            }
        }
    }

    private fun shores() {
        for (y in horizon until rows) for (x in 0 until COLS) {
            val d = (y - horizon).toFloat() / max(1, rows - horizon - 1)
            val shore = (COLS * (.1f + d * .17f) + noise(y * .33f, 56) * 5).toInt()
            val edge = min(abs(x - shore), abs(x - (COLS - shore)))
            if (x > shore && x < COLS - shore) {
                put(x, y, ' ', WATER, (3 + d * 3).toInt())
            } else {
                val n = hash(x, y)
                val material = if (snow && n % 5 != 0) ICE else if (n % 7 < 2) TREE else BANK
                put(x, y, if (edge < 2) "_=~"[n % 3] else ",:;.*'"[n % 6], material, if (edge < 2) 12 else 3 + n % 8, BANK)
            }
        }
    }

    private fun tree(cx: Int, base: Int, height: Int, layer: Int, seed: Int) {
        if (height < 2) return
        val width = height * (.25f + random(seed, 31) * .12f)
        val tiers = max(1.5f, height / 18f)
        for (dy in 0 until height) {
            val y = base - height + dy
            if (y !in 0 until rows) continue
            val progress = dy.toFloat() / height
            val branch = (dy / tiers + random(seed, 32)) % 1f
            val spread = (width * progress.pow(.8f) * (1 - branch * .35f)).roundToInt()
            val bend = (sin(progress * 5 + seed) * progress * 1.5f).toInt()
            val left = (spread * (.78f + noise(dy / tiers, seed + 33) * .4f)).roundToInt()
            val right = (spread * (.75f + noise(dy / tiers, seed + 34) * .5f)).roundToInt()
            for (dx in -left..right) {
                val n = hash(cx + dx + seed, y)
                val edge = dx <= -left + 1 || dx >= right - 1
                val lit = dx > 0 && edge || branch < .18f
                val frost = snow && (lit || edge) && n % 7 < 4 && (abs(dx) > spread * .25f || edge)
                val material = if (frost) ICE else if (lit && day && !cloudy && n % 4 == 0) BARK else TREE
                val shade = if (frost) 10 + n % 6 else if (layer < 3) 4 + layer * 2 + n % 3 else if (lit) 6 + n % 6 else n % 4
                val ch = when {
                    dy == 0 -> '^'
                    frost -> "_'.,"[n % 4]
                    dx == 0 && progress > .78f -> '|'
                    edge && n % 3 != 0 -> if (dx < 0) '/' else '\\'
                    n % 10 < (if (lit) 8 else 4) -> "',:;i"[n % 5]
                    else -> ' '
                }
                put(cx + dx + bend, y, ch, material, shade, TREE)
            }
        }
        for (y in base - max(1, height / 6)..base) put(cx, y, '|', BARK, 3 + layer, TREE)
    }

    private fun trunk(cx: Int, seed: Int) {
        for (y in 0 until rows) {
            val spread = 1 + y * 2 / rows
            val bend = (sin(y * .035f + seed) * 1.4f).toInt()
            for (dx in -spread..spread) {
                val n = hash(dx + cx, y)
                put(cx + dx + bend, y, if (n % 4 == 0) ':' else if (dx == -spread) '(' else '|', BARK,
                    if (dx == spread) 8 + n % 3 else 1 + n % 4, TREE)
            }
        }
    }
    private fun boulder(cx: Int, cy: Int, rx: Int, ry: Int, seed: Int) {
        for (dy in -ry..ry) for (dx in -rx..rx) {
            val sphere = dx * dx / (rx * rx.toFloat()) + dy * dy / (ry * ry.toFloat())
            if (sphere > 1 + random(dx + seed, dy) * .12f) continue
            val light = (-dy / ry.toFloat() * .65f + dx / rx.toFloat() * .2f + .45f).coerceIn(0f, 1f)
            val material = if (snow && dy <= 0) ICE else if (dy < 0 && hash(dx, seed) % 5 == 0) TREE else BANK
            put(cx + dx, cy + dy, if (dy <= -ry + 1) "_~=,"[hash(dx, seed) % 4] else ";:.,'"[hash(dx, dy) % 5], material,
                (2 + light * 12).toInt(), BANK)
        }
    }

    private fun record() {
        // Rasterize generated glyphs only when geometry/weather changes. The retained
        // 1536 px cache avoids replaying tens of thousands of tiny glyphs every frame.
        val width = (COLS * CELL / 2).toInt()
        val height = (rows * LINE / 2).toInt()
        val old = scene
        val bitmap = if (old != null && old.width == width && old.height == height) old
            else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scene = it }
        bitmap.eraseColor(Color.BLACK)
        sceneBounds.set(0f, 0f, COLS * CELL, rows * LINE)
        val c = Canvas(bitmap)
        c.scale(.5f, .5f)
        for (y in 0 until rows) {
            paint.color = if (y < horizon) skyBack[y] else backgrounds[WATER * 16 + 2]
            c.drawRect(0f, y * LINE, COLS * CELL, (y + 1) * LINE, paint)
            var x = 0
            while (x < COLS) {
                val i = y * COLS + x
                val shade = shades[i].toInt() and 255
                val surface = surfaces[i].toInt()
                val start = x++
                while (x < COLS && shades[y * COLS + x] == shades[i] && surfaces[y * COLS + x].toInt() == surface) x++
                if (surface != SKY && surface != WATER) {
                    paint.color = backgrounds[shade]
                    c.drawRect(start * CELL, y * LINE, x * CELL, (y + 1) * LINE, paint)
                }
                if (surface != WATER) {
                    paint.color = colors[shade]
                    c.drawText(glyphs, y * COLS + start, x - start, start * CELL, y * LINE + 7f, paint)
                }
            }
        }
    }
    private fun mark(c: Canvas, x: Float, y: Float, ch: Char, color: Int) {
        one[0] = ch; paint.color = color
        c.drawText(one, 0, 1, x * CELL, y * LINE + 7f, paint)
    }
    private fun sky(c: Canvas, time: Float, partly: Boolean) {
        if (known && !day && !cloudy) for (i in 0..139) {
            val x = (random(i, 201) * COLS + time * (.04f + i % 3 * .012f)) % COLS
            val y = (random(i, 202) * horizon * .72f + time * .007f) % (horizon * .72f)
            if (surfaces[y.toInt() * COLS + x.toInt()].toInt() != SKY) continue
            val twinkle = sin(time * (1.1f + i % 5 * .2f) + i * 2.4f)
            mark(c, x, y, if (i % 17 == 0) '+' else '.', colors[LIGHT * 16 + (8 + twinkle * 6).toInt()])
        }
        for (i in 0 until (if (cloudy) 9 else if (partly) 5 else 2)) {
            val y = 2 + random(i, 214) * horizon * .5f
            val start = (random(i, 215) * COLS + time * (if (cloudy) 1f else .3f)) % (COLS + 70) - 45
            for (k in 0 until 35 + hash(i, 216) % 36) {
                val x = (start + k).toInt()
                if (x !in 0 until COLS || surfaces[y.toInt() * COLS + x].toInt() != SKY || k % 5 == 0) continue
                mark(c, x.toFloat(), y + sin(k * .3f) * .3f, if (k % 4 == 0) '~' else '-', colors[SKY * 16 + if (cloudy) 10 else 7])
            }
        }
    }

    private fun water(c: Canvas, time: Float, wind: Float) {
        val speed = .65f + min(wind, 45f) * .018f
        val sparklePhase = (time * 2.5f).toInt()
        for (y in horizon until rows) {
            val depth = (y - horizon).toFloat() / max(1, rows - horizon)
            val shift = sin(time * speed + y * 1.2f) * (1 + depth * 3)
            val sourceY = (horizon - (y - horizon) * 2f - 1).roundToInt().coerceIn(0, horizon - 1)
            for (x in 0 until COLS) {
                waterGlyphs[x] = ' '; waterShades[x] = 0
                if (surfaces[y * COLS + x].toInt() != WATER) continue
                val sourceX = (x + shift).roundToInt().coerceIn(0, COLS - 1)
                val source = sourceY * COLS + sourceX
                val wave = sin(x * .26f + y * 1.65f + time * speed * 2)
                val n = hash(x, y)
                val path = known && !cloudy && abs(x - COLS * .715f - shift * 2) < 2 + depth * 18
                val shade = when {
                    path && wave > -.45f && n % 5 != 0 -> LIGHT * 16 + if (wave > .6f) 15 else 10
                    wave < .45f && n % 6 != 0 -> (shades[source].toInt() and 127) + 128
                    wave > .2f && n % 5 != 0 -> WATER * 16 + (5 + depth * 5 + wave * 3).toInt()
                    hash(x / 2 + sparklePhase, y) % 17 == 0 -> WATER * 16 + 12
                    else -> WATER * 16 + 3
                }
                waterShades[x] = shade.toByte()
                waterGlyphs[x] = if (path && wave > .6f) '=' else if (wave > .6f) '~' else if (n % 3 == 0) '_' else '-'
            }
            var x = 0
            while (x < COLS) {
                if (waterGlyphs[x] == ' ') { x++; continue }
                val start = x; val shade = waterShades[x++]
                while (x < COLS && waterShades[x] == shade && waterGlyphs[x] != ' ') x++
                paint.color = colors[shade.toInt() and 255]
                c.drawText(waterGlyphs, start, x - start, start * CELL, y * LINE + 7f, paint)
            }
        }
    }
    private fun mist(c: Canvas, time: Float) {
        for (i in 0..12) for (k in 0..76) {
            val x = (k * 3f + i * 13 + time * .8f) % COLS
            val y = horizon * .46f + i * horizon * .033f
            if (y >= rows || surfaces[y.toInt() * COLS + x.toInt()].toInt() == TREE || k % 5 == 0) continue
            mark(c, x, y, '-', colors[SKY * 16 + 9 + i % 3])
        }
    }
    private fun snowfall(c: Canvas, time: Float) {
        for (i in 0..155) {
            val depth = .3f + random(i, 240)
            val y = (random(i, 241) * (rows + 3) + time * (2 + depth * 3.4f)) % (rows + 3) - 2
            val x = (random(i, 242) * COLS + time * (1 + depth) + sin(time * .7f + i) * (1.5f + depth * 2)) % COLS
            mark(c, x, y, if (i % 4 == 0) '*' else if (i % 7 == 0) '+' else '.', colors[ICE * 16 + if (depth > .9f) 15 else 10])
        }
    }
    private fun rainfall(c: Canvas, time: Float, drizzle: Boolean) {
        for (i in 0 until (if (drizzle) 32 else dropX.size)) {
            val phase = (time / (if (drizzle) 2.9f else 2.1f) + random(i, 261)) % 1f
            val hitX = dropX[i].toFloat(); val hitY = dropY[i].toFloat()
            if (phase < .62f) {
                val travel = phase / .62f
                val y = -4 + (hitY + 4) * travel
                val x = hitX + (1 - travel) * 9
                mark(c, x, y, if (drizzle) '.' else '/', colors[ICE * 16 + 10])
                if (!drizzle) mark(c, x + .4f, y - 1.3f, '/', colors[ICE * 16 + 5])
            } else {
                val age = (phase - .62f) / .38f
                val rx = .6f + age * 6.2f; val ry = .13f + age * .85f
                for (j in ringX.indices) {
                    val x = hitX + ringX[j] * rx; val y = hitY + ringY[j] * ry
                    val col = x.roundToInt(); val row = y.roundToInt()
                    if (col !in 0 until COLS || row !in 0 until rows || surfaces[row * COLS + col].toInt() != WATER) continue
                    mark(c, x, y, if (j % 10 in 2..7) '_' else '-', colors[WATER * 16 + (14 - age * 9).toInt()])
                }
                if (age < .1f) mark(c, hitX, hitY - .4f, '^', colors[ICE * 16 + 14])
            }
        }
        if (!drizzle) for (i in 0..46) {
            val y = (random(i, 271) * rows + time * 37) % rows
            val x = (random(i, 272) * COLS - y * .18f + COLS) % COLS
            mark(c, x, y, '/', colors[ICE * 16 + 7])
        }
    }
}
