package dev.phosphor.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONObject
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Source-colored ASCII reference with lake-only animated reflection and weather. */
internal object ForestLakeTrueAsciiRenderer {
    private const val WARP_MARGIN = 3
    private const val WARP_WIDTH = 64
    private val sourceRect = Rect()
    private val targetRect = RectF()
    private val sceneBounds = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 48f
    }
    private val glyphCapHeight: Float
    private val glyphBaseline: Float
    private val glyphAdvance: Float
    init {
        val bounds = Rect()
        glyphPaint.getTextBounds("M", 0, 1, bounds)
        glyphCapHeight = bounds.height() / glyphPaint.textSize
        glyphBaseline = -bounds.top / glyphPaint.textSize
        glyphAdvance = glyphPaint.measureText("M") / glyphPaint.textSize
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val one = CharArray(1)
    private val fieldSample = FloatArray(3)
    private val ringX = FloatArray(64) { cos(it * PI * 2 / 64).toFloat() }
    private val ringY = FloatArray(64) { sin(it * PI * 2 / 64).toFloat() }
    private val impacts = IntArray(88) { -1 }
    private val glints = IntArray(86) { -1 }
    private var scene: Bitmap? = null
    private var cacheLeft = 0
    private var cacheRight = 0
    private var rasterWidth = 0
    private var rasterHeight = 0
    private var rasterScaleX = 1f
    private var rasterScaleY = 1f
    private var viewScale = 1f
    private var colors = IntArray(0)
    private var waterMask = ByteArray(0)
    private var waterIntegral = IntArray(0)
    // Four integers per chunk: x, source row, width, number of source rows.
    private var waterChunks = IntArray(0)
    private var columns = 0
    private var rows = 0
    private var firstWaterRow = 0
    private var lastWaterRow = 0
    private var panel = -1
    private var variant = -1
    private var manifest: JSONObject? = null
    private var lightX = 0f
    private var lightY = 0f
    private var lightRadius = 0f

    /** Activity and wallpaper engines may call this singleton from different threads. */
    @Synchronized
    fun draw(u: TerminalUi, x: Float, y: Float, w: Float, h: Float, weather: WeatherState?) {
        if (w <= 0f || h <= 0f) return
        val code = weather?.code ?: -1
        val next = when {
            code in 71..77 || code in 85..86 -> 0
            code in 51..67 || code in 80..82 || code in 95..99 -> 1
            weather == null || code == 3 || code == 45 || code == 48 -> 2
            weather.isDay -> 3
            else -> 4
        }
        val variants = references().getJSONObject("variants")
        val wide = variants.getJSONArray("wide").getJSONObject(next)
        val square = variants.getJSONArray("square").getJSONObject(next)
        val aspect = w / h
        val wideAspect = wide.getInt("width").toFloat() / wide.getInt("height")
        val squareAspect = square.getInt("width").toFloat() / square.getInt("height")
        // Compare the retained fraction under cover, symmetrically for tall/wide targets.
        val nextVariant = if (min(aspect / wideAspect, wideAspect / aspect) >=
            min(aspect / squareAspect, squareAspect / aspect)) 0 else 1
        if (next != panel || nextVariant != variant) {
            load(next, nextVariant, if (nextVariant == 0) wide else square)
        }
        val canvas = u.canvas
        val scale = max(w / columns, h / rows)
        val left = (w - columns * scale) / 2f
        val top = (h - rows * scale) / 2f
        // Retain only visible source columns, plus filter/refraction support.
        val first = max(0, floor(-left / scale).toInt() - WARP_MARGIN)
        val end = min(columns, ceil((w - left) / scale).toInt() + WARP_MARGIN)
        val displayScale = canvas.width / u.width
        viewScale = scale * displayScale
        val density = min(viewScale, min(2048f / (end - first), 2048f / rows))
        val width = ceil((end - first) * density).toInt().coerceIn(1, 2048)
        val height = ceil(rows * density).toInt().coerceIn(1, 2048)
        if (scene == null || cacheLeft != first || cacheRight != end ||
            rasterWidth != width || rasterHeight != height) {
            record(first, end, width, height)
        }
        val bitmap = scene ?: return
        val saved = canvas.save()
        try {
            canvas.clipRect(x, y, x + w, y + h)
            canvas.translate(x + left, y + top)
            canvas.scale(scale, scale)
            canvas.clipRect(0f, 0f, columns.toFloat(), rows.toFloat())
            canvas.drawBitmap(bitmap, null, sceneBounds, bitmapPaint)
            water(canvas, bitmap, u.time, weather?.wind?.toFloat() ?: 0f)
            when (panel) {
                0 -> snow(canvas, u.time)
                1 -> rain(canvas, u.time, code in 51..57)
                3 -> sunlight(canvas, u.time)
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }


    private fun references(): JSONObject {
        manifest?.let { return it }
        val parsed = L10n.context.assets.open("weather/forest-lake.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        check(parsed.getInt("version") == 1) { "Invalid forest reference version" }
        manifest = parsed
        return parsed
    }

    private fun load(selected: Int, selectedVariant: Int, reference: JSONObject) {
        scene?.recycle()
        scene = null
        panel = -1
        variant = -1
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = L10n.context.assets.open(reference.getString("image")).use {
            requireNotNull(BitmapFactory.decodeStream(it, null, options)) { "Invalid forest reference PNG" }
        }
        try {
            columns = reference.getInt("width")
            rows = reference.getInt("height")
            check(columns in 1..4096 && rows in 1..4096 &&
                bitmap.width == columns && bitmap.height == rows) { "Invalid forest reference dimensions" }
            colors = IntArray(columns * rows)
            bitmap.getPixels(colors, 0, columns, 0, 0, columns, rows)
        } finally {
            bitmap.recycle()
        }
        val light = reference.getJSONArray("light")
        lightX = light.getDouble(0).toFloat() * columns
        lightY = light.getDouble(1).toFloat() * rows
        lightRadius = light.getDouble(2).toFloat() * columns
        prepareMask(reference)
        prepareWater()
        if (selected == 3) prepareGlints()
        panel = selected
        variant = selectedVariant
    }

    private fun prepareMask(reference: JSONObject) {
        val polygon = reference.getJSONArray("water")
        check(polygon.length() >= 3) { "Invalid forest water polygon" }
        val points = FloatArray(polygon.length() * 2)
        for (i in 0 until polygon.length()) {
            val point = polygon.getJSONArray(i)
            points[i * 2] = point.getDouble(0).toFloat() * columns
            points[i * 2 + 1] = point.getDouble(1).toFloat() * rows
        }
        waterMask = ByteArray(columns * rows)
        val crossings = FloatArray(polygon.length())
        for (row in 0 until rows) {
            val y = row + .5f
            var count = 0
            for (i in 0 until polygon.length()) {
                val next = (i + 1) % polygon.length()
                val ax = points[i * 2]
                val ay = points[i * 2 + 1]
                val bx = points[next * 2]
                val by = points[next * 2 + 1]
                if ((ay > y) != (by > y)) crossings[count++] = ax + (y - ay) * (bx - ax) / (by - ay)
            }
            Arrays.sort(crossings, 0, count)
            var i = 0
            while (i + 1 < count) {
                val first = ceil(crossings[i] - .5f).toInt().coerceIn(0, columns)
                val end = ceil(crossings[i + 1] - .5f).toInt().coerceIn(first, columns)
                Arrays.fill(waterMask, row * columns + first, row * columns + end, 1.toByte())
                i += 2
            }
        }
        // Remove every cell touched by the boundary. A retained cell's entire square,
        // not merely its center, is inside the conservative open-lake polygon.
        for (i in 0 until polygon.length()) {
            val next = (i + 1) % polygon.length()
            val ax = points[i * 2]
            val ay = points[i * 2 + 1]
            val bx = points[next * 2]
            val by = points[next * 2 + 1]
            val top = max(0, floor(min(ay, by)).toInt())
            val bottom = min(rows - 1, floor(max(ay, by)).toInt())
            for (row in top..bottom) {
                val t0 = if (ay == by) 0f else ((row - ay) / (by - ay)).coerceIn(0f, 1f)
                val t1 = if (ay == by) 1f else ((row + 1f - ay) / (by - ay)).coerceIn(0f, 1f)
                val x0 = ax + (bx - ax) * t0
                val x1 = ax + (bx - ax) * t1
                val first = floor(min(x0, x1)).toInt().coerceIn(0, columns)
                val end = (floor(max(x0, x1)).toInt() + 1).coerceIn(first, columns)
                Arrays.fill(waterMask, row * columns + first, row * columns + end, 0.toByte())
            }
        }
    }


    private fun record(first: Int, end: Int, width: Int, height: Int) {
        scene?.recycle()
        scene = null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (width == end - first && height == rows) {
            bitmap.setPixels(colors, first, columns, 0, 0, width, height)
        } else {
            // Sample RGB itself: the PNG already contains the final colored characters.
            // Exact box integration preserves their ink when minifying; bilinear RGB
            // interpolation handles enlargement without imposing a second glyph grid.
            val pixels = IntArray(width * height)
            val spanX = (end - first).toFloat() / width
            val spanY = rows.toFloat() / height
            for (row in 0 until height) {
                val sy = (row + .5f) * spanY - .5f
                for (col in 0 until width) {
                    val sx = first + (col + .5f) * spanX - .5f
                    sampleField(sx, sy, spanX, spanY)
                    pixels[row * width + col] = Color.rgb(fieldSample[0].roundToInt(),
                        fieldSample[1].roundToInt(), fieldSample[2].roundToInt())
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        cacheLeft = first
        cacheRight = end
        rasterWidth = width
        rasterHeight = height
        rasterScaleX = width.toFloat() / (end - first)
        rasterScaleY = height.toFloat() / rows
        // Band height follows actual raster density; thin source rows must not
        // disappear entirely in a compact card.
        val count = waterBands(null)
        waterChunks = IntArray(count * 4)
        waterBands(waterChunks)
        sceneBounds.set(first.toFloat(), 0f, end.toFloat(), rows.toFloat())
        scene = bitmap
    }

    private fun sampleField(x: Float, y: Float, spanX: Float, spanY: Float) {
        fieldSample.fill(0f)
        if (spanX > 1f || spanY > 1f) {
            // One exact box integration, not repeated bitmap bilinear minification,
            // preserves the average ink of the source's existing tiny ASCII strokes.
            val left = max(0f, x + .5f - spanX * .5f)
            val top = max(0f, y + .5f - spanY * .5f)
            val right = min(columns.toFloat(), x + .5f + spanX * .5f)
            val bottom = min(rows.toFloat(), y + .5f + spanY * .5f)
            val area = (right - left) * (bottom - top)
            for (row in floor(top).toInt() until ceil(bottom).toInt()) {
                val vertical = min(bottom, row + 1f) - max(top, row.toFloat())
                for (col in floor(left).toInt() until ceil(right).toInt()) {
                    val horizontal = min(right, col + 1f) - max(left, col.toFloat())
                    accumulateField(row * columns + col, horizontal * vertical / area)
                }
            }
            return
        }
        val sx = x.coerceIn(0f, columns - 1f)
        val sy = y.coerceIn(0f, rows - 1f)
        val x0 = sx.toInt()
        val y0 = sy.toInt()
        val x1 = min(x0 + 1, columns - 1)
        val y1 = min(y0 + 1, rows - 1)
        val fx = sx - x0
        val fy = sy - y0
        accumulateField(y0 * columns + x0, (1f - fx) * (1f - fy))
        accumulateField(y0 * columns + x1, fx * (1f - fy))
        accumulateField(y1 * columns + x0, (1f - fx) * fy)
        accumulateField(y1 * columns + x1, fx * fy)
    }

    private fun accumulateField(cell: Int, weight: Float) {
        val color = colors[cell]
        fieldSample[0] += Color.red(color) * weight
        fieldSample[1] += Color.green(color) * weight
        fieldSample[2] += Color.blue(color) * weight
    }

    private fun prepareWater() {
        firstWaterRow = rows
        lastWaterRow = 0
        // Summed non-water coverage makes complete stroke/refraction-footprint
        // checks constant-time, including antialias/filter support, at every zoom.
        val stride = columns + 1
        waterIntegral = IntArray(stride * (rows + 1))
        for (row in 0 until rows) {
            var blocked = 0
            for (col in 0 until columns) {
                if (waterMask[row * columns + col].toInt() == 0) blocked++
                waterIntegral[(row + 1) * stride + col + 1] = waterIntegral[row * stride + col + 1] + blocked
            }
        }
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                if (waterMask[row * columns + col].toInt() == 0) continue
                firstWaterRow = min(firstWaterRow, row)
                lastWaterRow = max(lastWaterRow, row)
            }
        }
        for (i in impacts.indices) {
            impacts[i] = -1
            for (attempt in 0 until 256) {
                val cell = hash(i, attempt + 311) % waterMask.size
                val x = (cell % columns).toFloat()
                val y = (cell / columns).toFloat()
                if (waterFootprint(x - 5f, y - 5f, x + 5f, y + 5f)) {
                    impacts[i] = cell
                    break
                }
            }
        }
    }

    private fun prepareGlints() {
        val depth = max(1, lastWaterRow - firstWaterRow)
        for (i in glints.indices) {
            glints[i] = -1
            val fraction = random(i, 481)
            val row = (firstWaterRow + fraction * depth).roundToInt()
            val spread = lightRadius * .45f + fraction * columns * .12f
            val col = (lightX + (random(i, 482) * 2f - 1f) * spread).roundToInt()
            var nearest = Int.MAX_VALUE
            // Find real golden ink once, not a random black gap between ASCII strokes.
            for (y in max(firstWaterRow, row - 12)..min(lastWaterRow, row + 12)) {
                for (x in max(0, col - 24)..min(columns - 1, col + 24)) {
                    val cell = y * columns + x
                    val color = colors[cell]
                    if (waterMask[cell].toInt() == 0 || Color.red(color) < 100 ||
                        Color.green(color) < 65 || Color.red(color) <= Color.blue(color) * 1.3f) continue
                    val dx = x - col
                    val dy = y - row
                    val distance = dx * dx + dy * dy
                    if (distance < nearest) {
                        nearest = distance
                        glints[i] = cell
                    }
                }
            }
        }
    }

    private fun waterBands(output: IntArray?): Int {
        var count = 0
        var row = firstWaterRow
        val bandHeight = max(2, ceil(2f / rasterScaleY).toInt())
        while (row <= lastWaterRow && row < rows) {
            val bandRows = min(bandHeight, rows - row)
            var col = 0
            while (col < columns) {
                while (col < columns && !bandWater(col, row, bandRows)) col++
                val start = col
                while (col < columns && bandWater(col, row, bandRows)) col++
                val end = col - WARP_MARGIN
                var left = start + WARP_MARGIN
                while (left < end) {
                    val width = min(WARP_WIDTH, end - left)
                    if (output != null) {
                        val offset = count * 4
                        output[offset] = left
                        output[offset + 1] = row
                        output[offset + 2] = width
                        output[offset + 3] = bandRows
                    }
                    count++
                    left += width
                }
            }
            row += bandHeight
        }
        return count
    }

    private fun bandWater(col: Int, row: Int, height: Int): Boolean {
        for (dy in 0 until height) if (waterMask[(row + dy) * columns + col].toInt() == 0) return false
        return true
    }

    private fun water(canvas: Canvas, bitmap: Bitmap, time: Float, wind: Float) {
        val speed = .8f + min(max(wind, 0f), 45f) * .018f
        var offset = 0
        while (offset < waterChunks.size) {
            val x = waterChunks[offset]
            val row = waterChunks[offset + 1]
            val width = waterChunks[offset + 2]
            val height = waterChunks[offset + 3]
            val first = max(x, cacheLeft + WARP_MARGIN)
            val end = min(x + width, cacheRight - WARP_MARGIN)
            if (first < end) {
                refractWater(canvas, bitmap, first.toFloat(), row.toFloat(), (end - first).toFloat(),
                    height.toFloat(), sin(time * speed + row * .79f + x * .009f) * 2.1f, 0f)
            }
            offset += 4
        }
        for (i in 0 until 132) {
            val cell = impacts[i % impacts.size]
            if (cell < 0) continue
            val row = cell / columns
            val x = wrap(cell % columns + time * (2f + i % 4) + sin(time * .8f + i) * 2f, columns.toFloat())
            val y = row + sin(time * 1.1f + i) * .6f
            val pulse = .5f + .5f * sin(time * 1.8f + i * 2.3f)
            val color = sourceColor(x, y)
            waterGlyph(canvas, x, y, if (i % 3 == 0) '~' else '-', 3f + i % 4, 1.2f,
                lighten(color, .12f + pulse * .2f), (45 + pulse * 95).toInt())
        }
    }

    private fun rain(canvas: Canvas, time: Float, drizzle: Boolean) {
        val duration = if (drizzle) 3.4f else 2.8f
        val count = if (drizzle) 28 else impacts.size
        for (i in 0 until count) {
            val cell = impacts[i]
            if (cell < 0) continue
            val hitX = cell % columns + .5f
            val hitY = cell / columns + .5f
            val phase = wrap(time / duration + random(i, 401), 1f)
            if (phase < .46f) {
                val travel = phase / .46f
                // The streak's bottom tip, not its upper-left corner, arrives at the ring center.
                val height = if (drizzle) 4f else 7f
                val width = if (drizzle) 1.6f else 2.4f
                val y = -9f + (hitY + 9f) * travel
                val x = hitX + (1f - travel) * 27f
                ripplePaint.color = lighten(colors[cell], .62f)
                ripplePaint.alpha = if (drizzle) 110 else 160
                ripplePaint.strokeWidth = max(.7f, .8f / viewScale)
                canvas.drawLine(x + width, y - height, x, y, ripplePaint)
            }
        }
        // Long streaks in front of the forest complement the lake-targeted drops.
        if (!drizzle) for (i in 0 until 92) {
            val y = wrap(random(i, 421) * rows + time * (110 + i % 5 * 12), rows + 10f) - 8f
            val x = wrap(random(i, 422) * columns - time * 23f - y * .16f, columns.toFloat())
            glyph(canvas, x, y, '/', 2f, 6f, 0xffb9cfda.toInt(), 100)
        }
        scene?.let { rainWater(canvas, it, time, drizzle) }
    }

    /** Lake-only pass: the same impacts drive arrival, splash, refraction and three wavefronts. */
    private fun rainWater(canvas: Canvas, bitmap: Bitmap, time: Float, drizzle: Boolean) {
        val duration = if (drizzle) 3.4f else 2.8f
        val count = if (drizzle) 28 else impacts.size
        val stroke = max(.8f, 1.1f / viewScale)
        for (i in 0 until count) {
            val cell = impacts[i]
            if (cell < 0) continue
            val phase = wrap(time / duration + random(i, 401), 1f)
            if (phase < .46f) continue
            val age = (phase - .46f) / .54f
            val hitX = cell % columns + .5f
            val hitY = cell / columns + .5f
            val color = lighten(colors[cell], if (drizzle) .34f else .48f)
            val radius = 3f + age * if (drizzle) 27f else 38f
            val depth = radius * .24f
            // Five shallow lens strips disturb the actual colored reflection, not a fake glow.
            for (band in -2..2) {
                val position = band / 3f
                val width = radius * sqrt(1f - position * position) * 2f
                val shift = sin(age * 15f - band * 1.7f + i) * (1f - age) * 2.6f
                refractWater(canvas, bitmap, hitX - width * .5f, hitY + position * depth,
                    width, max(1f, 1.5f / viewScale), shift, shift * .24f)
            }
            for (wave in 0..2) {
                val waveAge = age - wave * .18f
                if (waveAge <= 0f) continue
                val rx = 2f + waveAge * if (drizzle) 27f else 38f
                val ry = .9f + waveAge * if (drizzle) 6f else 9f
                val alpha = ((1f - age) * (if (drizzle) 165 else 225) * (1f - wave * .16f)).toInt()
                ripplePaint.color = color
                ripplePaint.alpha = alpha
                ripplePaint.strokeWidth = stroke
                for (point in ringX.indices) {
                    val next = (point + 1) % ringX.size
                    waterLine(canvas, hitX + ringX[point] * rx, hitY + ringY[point] * ry,
                        hitX + ringX[next] * rx, hitY + ringY[next] * ry)
                }
            }
            // Short upward/outward splash jets remain wholly over water, including AA ink.
            if (age < .22f) {
                val splash = age / .22f
                ripplePaint.color = lighten(colors[cell], .7f)
                ripplePaint.alpha = ((1f - splash) * 235).toInt()
                ripplePaint.strokeWidth = stroke
                for (jet in -1..1) {
                    val endX = hitX + jet * (1f + splash * 5f)
                    val endY = hitY - sin(splash * PI.toFloat()) * (if (jet == 0) 7f else 5f)
                    waterLine(canvas, hitX + jet * splash * 2f, hitY, endX, endY)
                }
            }
        }
    }

    private fun waterLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val fringe = ripplePaint.strokeWidth * .5f + 1f / viewScale
        if (!waterFootprint(min(x1, x2) - fringe, min(y1, y2) - fringe,
                max(x1, x2) + fringe, max(y1, y2) + fringe)) return
        canvas.drawLine(x1, y1, x2, y2, ripplePaint)
    }

    private fun refractWater(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float,
                             width: Float, height: Float, shiftX: Float, shiftY: Float) {
        val left = ceil((x - cacheLeft) * rasterScaleX).toInt()
        val top = ceil(y * rasterScaleY).toInt()
        val right = floor((x + width - cacheLeft) * rasterScaleX).toInt()
        val bottom = floor((y + height) * rasterScaleY).toInt()
        if (left >= right || top >= bottom) return
        val dx = (shiftX * rasterScaleX).roundToInt()
        val dy = (shiftY * rasterScaleY).roundToInt()
        if (dx == 0 && dy == 0) return
        val targetLeft = cacheLeft + left / rasterScaleX
        val targetTop = top / rasterScaleY
        val targetRight = cacheLeft + right / rasterScaleX
        val targetBottom = bottom / rasterScaleY
        val fringe = 2f / min(rasterScaleX, rasterScaleY)
        if (!waterFootprint(targetLeft + min(0, dx) / rasterScaleX - fringe,
                targetTop + min(0, dy) / rasterScaleY - fringe,
                targetRight + max(0, dx) / rasterScaleX + fringe,
                targetBottom + max(0, dy) / rasterScaleY + fringe)) return
        if (left + dx < 0 || top + dy < 0 || right + dx > bitmap.width || bottom + dy > bitmap.height) return
        sourceRect.set(left + dx, top + dy, right + dx, bottom + dy)
        targetRect.set(targetLeft, targetTop, targetRight, targetBottom)
        canvas.drawBitmap(bitmap, sourceRect, targetRect, bitmapPaint)
    }

    private fun snow(canvas: Canvas, time: Float) {
        for (layer in 0..2) {
            for (i in 0 until 86) {
                val seed = i + layer * 101
                val depth = .55f + layer * .5f
                val y = wrap(random(seed, 451) * rows + time * (7 + layer * 9), rows + 8f) - 7f
                val x = wrap(random(seed, 452) * columns + time * (3 + layer * 3) +
                    sin(time * (.5f + layer * .15f) + seed) * (4 + layer * 3), columns.toFloat())
                val size = 2f + depth * 2.2f
                glyph(canvas, x, y, when (i % 5) { 0 -> '*'; 1 -> '+'; else -> '.' },
                    size, size, 0xffe8f3fa.toInt(), 100 + layer * 60)
            }
        }
    }

    private fun sunlight(canvas: Canvas, time: Float) {
        if (lightRadius <= 0f) return
        val sunX = lightX
        val sunY = lightY
        val waterTop = firstWaterRow.toFloat()
        val waterDepth = max(1f, (lastWaterRow - firstWaterRow).toFloat())
        for (i in glints.indices) {
            val cell = glints[i]
            if (cell < 0) continue
            val y = (cell / columns).toFloat()
            val depth = (y - waterTop) / waterDepth
            val x = cell % columns + sin(time * .7f + i) * 3f
            val pulse = max(0f, sin(time * (1.4f + i % 4 * .17f) + i * 1.9f))
            waterGlyph(canvas, x, y, if (i % 7 == 0) '+' else '-', 2f + depth * 4f,
                if (i % 7 == 0) 2f else 1f, 0xffffdf9e.toInt(), (pulse * 185).toInt())
        }
        for (i in 0 until 12) {
            val angle = i * PI.toFloat() / 6f + time * .035f
            // Shimmer around the rim; the source's colored core is already bright.
            val radius = lightRadius + i % 3 * 1.6f
            val pulse = .5f + .5f * sin(time * .9f + i)
            glyph(canvas, sunX + cos(angle) * radius, sunY + sin(angle) * radius,
                '.', 1.1f, 1.1f, 0xffffe9b9.toInt(), (pulse * 100).toInt())
        }
    }

    private fun waterFootprint(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val left = floor(x1).toInt()
        val top = floor(y1).toInt()
        val right = ceil(x2).toInt()
        val bottom = ceil(y2).toInt()
        if (left < 0 || top < 0 || right > columns || bottom > rows || left >= right || top >= bottom) return false
        val stride = columns + 1
        return waterIntegral[bottom * stride + right] - waterIntegral[top * stride + right] -
            waterIntegral[bottom * stride + left] + waterIntegral[top * stride + left] == 0
    }

    private fun waterGlyph(canvas: Canvas, x: Float, y: Float, ch: Char, width: Float, height: Float,
                           color: Int, alpha: Int) {
        // Filtering/antialias support is part of the footprint, not just the glyph center.
        val fringe = 1f / viewScale
        if (!waterFootprint(x - fringe, y - fringe, x + width + fringe, y + height + fringe)) return
        glyph(canvas, x, y, ch, width, height, color, alpha)
    }

    private fun glyph(canvas: Canvas, x: Float, y: Float, ch: Char, width: Float, height: Float,
                      color: Int, alpha: Int) {
        if (alpha <= 0) return
        one[0] = ch
        // Rasterize text at its final size: bitmap minification can miss a tiny
        // dash or dot entirely when its stroke falls between bilinear samples.
        glyphPaint.textSize = height / glyphCapHeight
        glyphPaint.textScaleX = width / (glyphPaint.textSize * glyphAdvance)
        glyphPaint.color = color
        glyphPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawText(one, 0, 1, x, y + glyphPaint.textSize * glyphBaseline, glyphPaint)
    }

    private fun sourceColor(x: Float, y: Float): Int {
        val col = x.toInt().coerceIn(0, columns - 1)
        val row = y.toInt().coerceIn(0, rows - 1)
        return colors[row * columns + col]
    }

    private fun lighten(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * amount).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * amount).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt())

    private fun hash(x: Int, y: Int): Int {
        var n = x * 374761393 + y * 668265263 + 1274126177
        n = (n xor (n ushr 13)) * 1274126177
        return (n xor (n ushr 16)) and Int.MAX_VALUE
    }

    private fun random(x: Int, y: Int): Float = (hash(x, y) % 10000) / 10000f
    private fun wrap(value: Float, size: Float): Float = value - floor(value / size) * size
}
