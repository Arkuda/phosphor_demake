package dev.phosphor.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Source-colored, fine ASCII texture; only the selected reference is retained. */
internal object ForestLakeRenderer {
    private const val CELL_HEIGHT = 1
    private const val GLYPH_WIDTH = 12
    private const val GLYPH_HEIGHT = 24
    private const val GLYPH_PADDING = 2
    private const val GLYPH_STRIDE = GLYPH_WIDTH + GLYPH_PADDING * 2
    private const val FINE_WIDTH = 1
    private const val FINE_HEIGHT = 1
    private const val FINE_STRIDE = FINE_WIDTH + 2
    private const val FIRST_GLYPH = 32
    private const val LAST_GLYPH = 126
    private const val DARK = -16777216 // #000000
    private const val WARP_MARGIN = 3
    private const val WARP_WIDTH = 64
    private val toneRamp = "@#S08Xx+=-;:. ".toCharArray()
    private val sourceRect = Rect()
    private val targetRect = RectF()
    private val sceneBounds = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val finePaint = Paint()
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tonePaint = Paint()
    private val backgroundPaint = Paint().apply { color = DARK }
    private val one = CharArray(1)
    private val glyphCoverage = FloatArray(128)
    private val fieldSample = FloatArray(4)
    private val ringX = FloatArray(64) { cos(it * PI * 2 / 64).toFloat() }
    private val ringY = FloatArray(64) { sin(it * PI * 2 / 64).toFloat() }
    private val impacts = IntArray(88) { -1 }
    private var atlas: Bitmap? = null
    private var fineAtlas: Bitmap? = null
    private var scene: Bitmap? = null
    private var tones = ByteArray(0)
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
    private var pixelHeight = 0
    private var firstWaterRow = 0
    private var lastWaterRow = 0
    private var panel = -1
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
        if (next != panel) load(next)
        val canvas = u.canvas
        // Keep the complete reference and its original aspect, including its sky.
        // Spare space is a quiet source-colored sky, never enlarged terrain.
        val scale = min(w / columns, h / pixelHeight)
        val left = (w - columns * scale) / 2f
        val first = 0
        val end = columns
        val displayScale = canvas.width / u.width
        viewScale = scale * displayScale
        // The reference already contains ASCII ink: native-pixel, area-averaged
        // masks preserve that texture without adding a second enlarged glyph grid.
        val density = min(viewScale, min(2048f / columns, 2048f / pixelHeight))
        val width = max(FINE_WIDTH, ceil((end - first) * density / FINE_WIDTH).toInt() * FINE_WIDTH)
        val height = max(FINE_HEIGHT, ceil(pixelHeight * density / FINE_HEIGHT).toInt() * FINE_HEIGHT)
        if (scene == null || cacheLeft != first || cacheRight != end ||
            rasterWidth != width || rasterHeight != height) {
            record(first, end, width, height)
        }
        val bitmap = scene ?: return
        val saved = canvas.save()
        try {
            canvas.clipRect(x, y, x + w, y + h)
            canvas.drawRect(x, y, x + w, y + h, backgroundPaint)
            canvas.translate(x + left, y + h - pixelHeight * scale)
            canvas.scale(scale, scale)
            canvas.clipRect(0f, 0f, columns.toFloat(), pixelHeight.toFloat())
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

    private fun glyphAtlas(): Bitmap {
        atlas?.let { return it }
        val bitmap = Bitmap.createBitmap((LAST_GLYPH - FIRST_GLYPH + 1) * GLYPH_STRIDE,
            GLYPH_HEIGHT + GLYPH_PADDING * 2, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val bounds = Rect()
        val font = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 48f
            color = Color.WHITE
        }
        // Cap-height rows omit both font leading and the descender reserve, which
        // otherwise leaves a dark stripe below every row of capitals/digits.
        // Shared metrics retain the different shapes and densities of dots and dashes.
        val ink = Rect()
        one[0] = 'M'
        font.getTextBounds(one, 0, 1, ink)
        val advance = font.measureText("M")
        for (value in FIRST_GLYPH + 1..LAST_GLYPH) {
            one[0] = value.toChar()
            font.getTextBounds(one, 0, 1, bounds)
            if (bounds.isEmpty) continue
            val saved = canvas.save()
            try {
                val left = (value - FIRST_GLYPH) * GLYPH_STRIDE + GLYPH_PADDING
                canvas.clipRect(left, GLYPH_PADDING, left + GLYPH_WIDTH, GLYPH_PADDING + GLYPH_HEIGHT)
                canvas.translate(left.toFloat(), GLYPH_PADDING.toFloat())
                canvas.scale(GLYPH_WIDTH / advance, GLYPH_HEIGHT.toFloat() / ink.height())
                canvas.drawText(one, 0, 1, 0f, -ink.top.toFloat(), font)
            } finally {
                canvas.restoreToCount(saved)
            }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // Area-average the master masks before minification. Bilinear sampling of a
        // 12x24 mask into a few pixels changes its ink coverage and creates moire;
        // these display-sized masks keep the measured luminance reconstruction exact.
        val fine = Bitmap.createBitmap((LAST_GLYPH - FIRST_GLYPH + 1) * FINE_STRIDE,
            FINE_HEIGHT + 2, Bitmap.Config.ALPHA_8)
        val sampleWidth = GLYPH_WIDTH / FINE_WIDTH
        val sampleHeight = GLYPH_HEIGHT / FINE_HEIGHT
        for (value in FIRST_GLYPH + 1..LAST_GLYPH) {
            val index = value - FIRST_GLYPH
            var total = 0
            for (row in 0 until FINE_HEIGHT) for (col in 0 until FINE_WIDTH) {
                var alpha = 0
                for (dy in 0 until sampleHeight) for (dx in 0 until sampleWidth) {
                    alpha += Color.alpha(pixels[(row * sampleHeight + dy + GLYPH_PADDING) * bitmap.width +
                        index * GLYPH_STRIDE + GLYPH_PADDING + col * sampleWidth + dx])
                }
                val mean = (alpha.toFloat() / (sampleWidth * sampleHeight)).roundToInt()
                fine.setPixel(index * FINE_STRIDE + 1 + col, row + 1, Color.argb(mean, 255, 255, 255))
                total += mean
            }
            glyphCoverage[value] = total / (FINE_WIDTH * FINE_HEIGHT * 255f)
        }
        fineAtlas = fine
        atlas = bitmap
        return bitmap
    }

    private fun load(selected: Int) {
        scene?.recycle()
        scene = null
        panel = -1
        DataInputStream(BufferedInputStream(L10n.context.assets.open("weather/forest-lake.bin"))).use { input ->
            check(input.readInt() == 0x464c4132) { "Invalid forest ASCII asset" }
            check(input.readInt() == 5) { "Invalid forest reference count" }
            for (index in 0..selected) {
                val width = input.readInt()
                val height = input.readInt()
                val sourceHeight = input.readInt()
                val sourceLightX = input.readFloat()
                val sourceLightY = input.readFloat()
                val sourceLightRadius = input.readFloat()
                check(width in 1..4096 && height in 1..4096 && sourceHeight == height) {
                    "Invalid forest reference dimensions"
                }
                check(sourceLightX in 0f..1f && sourceLightY in 0f..1f && sourceLightRadius in 0f..1f) {
                    "Invalid forest reference light"
                }
                if (index != selected) {
                    skipFully(input, height.toLong() * width * 5)
                    continue
                }
                val records = ByteArray(width * height * 5)
                input.readFully(records)
                val rgb = IntArray(width * height)
                val mask = ByteArray(rgb.size)
                val levels = ByteArray(rgb.size)
                for (cell in rgb.indices) {
                    val offset = cell * 5
                    levels[cell] = records[offset]
                    rgb[cell] = Color.rgb(records[offset + 1].toInt() and 255,
                        records[offset + 2].toInt() and 255, records[offset + 3].toInt() and 255)
                    mask[cell] = records[offset + 4]
                }
                columns = width
                rows = height
                pixelHeight = sourceHeight
                lightX = sourceLightX * width
                lightY = sourceLightY * sourceHeight
                lightRadius = sourceLightRadius * width
                tones = levels
                colors = rgb
                waterMask = mask
                prepareSky()
                prepareWater()
                panel = selected
            }
        }
    }

    private fun selectGlyph(value: Int) {
        val left = (value - FIRST_GLYPH) * GLYPH_STRIDE + GLYPH_PADDING
        sourceRect.set(left, GLYPH_PADDING, left + GLYPH_WIDTH, GLYPH_PADDING + GLYPH_HEIGHT)
    }

    private fun record(first: Int, end: Int, width: Int, height: Int) {
        glyphAtlas()
        val glyphs = requireNotNull(fineAtlas)
        scene?.recycle()
        scene = null
        // RGB565 lost dark blue/gold gradations and introduced visible color blocks.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(DARK)
        // The reference already contains glyph texture. Magnifying one stored sample
        // into a large new letter creates a second coarse grid. Resample the tone/color
        // field once at native display density, before selecting any new glyph.
        val across = width / FINE_WIDTH
        val down = height / FINE_HEIGHT
        val spanX = (end - first).toFloat() / across
        val spanY = rows.toFloat() / down
        for (row in 0 until down) {
            val sy = (row + .5f) * spanY - .5f
            for (col in 0 until across) {
                val sx = first + (col + .5f) * spanX - .5f
                sampleField(sx, sy, spanX, spanY)
                val tone = fieldSample[0] / 255f
                if (tone == 0f) continue
                val glyph = toneRamp[((1f - tone) * toneRamp.lastIndex).roundToInt()]
                targetRect.set((col * FINE_WIDTH).toFloat(), (row * FINE_HEIGHT).toFloat(),
                    ((col + 1) * FINE_WIDTH).toFloat(), ((row + 1) * FINE_HEIGHT).toFloat())
                val red = fieldSample[1].roundToInt()
                val green = fieldSample[2].roundToInt()
                val blue = fieldSample[3].roundToInt()
                val peak = max(red, max(green, blue))
                if (peak == 0) continue
                val inkRed = red * 255 / peak
                val inkGreen = green * 255 / peak
                val inkBlue = blue * 255 / peak
                val color = Color.rgb(inkRed, inkGreen, inkBlue)
                val inkLuma = (.299f * inkRed + .587f * inkGreen + .114f * inkBlue) / 255f
                val desired = (tone / inkLuma).coerceIn(0f, 1f)
                val coverage = glyphCoverage[glyph.code]
                // A font ramp does not have uniform ink coverage. Clipping boosted RGB
                // still leaves bright gold/blue areas muddy. Reconstruct the missing
                // luminance with a continuous source-colored underfill, not a CRT/glow pass.
                val underfill = if (desired > coverage) (desired - coverage) / (1f - coverage) else 0f
                if (underfill > 0f) {
                    tonePaint.color = color
                    tonePaint.alpha = (underfill * 255).roundToInt()
                    canvas.drawRect(targetRect, tonePaint)
                }
                if (glyph != ' ' && underfill < 1f) {
                    val glyphLeft = (glyph.code - FIRST_GLYPH) * FINE_STRIDE + 1
                    sourceRect.set(glyphLeft, 1, glyphLeft + FINE_WIDTH, 1 + FINE_HEIGHT)
                    finePaint.color = color
                    finePaint.alpha = if (desired < coverage) (desired / coverage * 255).roundToInt() else 255
                    canvas.drawBitmap(glyphs, sourceRect, targetRect, finePaint)
                }
            }
        }
        cacheLeft = first
        cacheRight = end
        rasterWidth = width
        rasterHeight = height
        rasterScaleX = width.toFloat() / (end - first)
        rasterScaleY = height.toFloat() / pixelHeight
        // Band height follows actual raster density; thin source rows must not
        // disappear entirely in a compact card.
        val count = waterBands(null)
        waterChunks = IntArray(count * 4)
        waterBands(waterChunks)
        sceneBounds.set(first.toFloat(), 0f, end.toFloat(), pixelHeight.toFloat())
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
        fieldSample[0] += (tones[cell].toInt() and 255) * weight
        fieldSample[1] += Color.red(color) * weight
        fieldSample[2] += Color.green(color) * weight
        fieldSample[3] += Color.blue(color) * weight
    }

    private fun skipFully(input: DataInputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                if (input.read() < 0) throw EOFException("Truncated forest ASCII asset")
                remaining--
            }
        }
    }

    private fun prepareSky() {
        var red = 0f
        var green = 0f
        var blue = 0f
        val count = columns * max(1, rows / 20)
        for (cell in 0 until count) {
            val color = colors[cell]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val peak = max(r, max(g, b))
            val luma = .299f * r + .587f * g + .114f * b
            if (peak == 0 || luma == 0f) continue
            val gain = min((tones[cell].toInt() and 255) / luma, 255f / peak)
            red += r * gain
            green += g * gain
            blue += b * gain
        }
        backgroundPaint.color = Color.rgb((red / count).roundToInt(),
            (green / count).roundToInt(), (blue / count).roundToInt())
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
            val y = row * CELL_HEIGHT + sin(time * 1.1f + i) * .6f
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
            val y = wrap(random(i, 421) * pixelHeight + time * (110 + i % 5 * 12), pixelHeight + 10f) - 8f
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
                val y = wrap(random(seed, 451) * pixelHeight + time * (7 + layer * 9), pixelHeight + 8f) - 7f
                val x = wrap(random(seed, 452) * columns + time * (3 + layer * 3) +
                    sin(time * (.5f + layer * .15f) + seed) * (4 + layer * 3), columns.toFloat())
                val size = 2f + depth * 2.2f
                glyph(canvas, x, y, when (i % 5) { 0 -> '*'; 1 -> '+'; else -> '.' },
                    size, size, 0xffe8f3fa.toInt(), 100 + layer * 60)
            }
        }
    }

    private fun sunlight(canvas: Canvas, time: Float) {
        val sunX = lightX
        val sunY = lightY
        val waterTop = firstWaterRow * CELL_HEIGHT.toFloat()
        val waterDepth = max(1f, (lastWaterRow - firstWaterRow) * CELL_HEIGHT.toFloat())
        for (i in 0 until 86) {
            val depth = random(i, 481)
            val y = waterTop + depth * waterDepth
            val spread = lightRadius * .45f + depth * columns * .12f
            val x = sunX + (random(i, 482) * 2f - 1f) * spread + sin(time * .7f + i) * 3f
            val pulse = max(0f, sin(time * (1.4f + i % 4 * .17f) + i * 1.9f))
            val color = sourceColor(x, y)
            // Keep warm glints in the source's existing bright reflection, not over dark banks.
            if (Color.red(color) < 75 || Color.red(color) < Color.blue(color)) continue
            waterGlyph(canvas, x, y, if (i % 7 == 0) '+' else '-', 2f + depth * 4f,
                if (i % 7 == 0) 2f else 1f, 0xffffdf9e.toInt(), (pulse * 185).toInt())
        }
        for (i in 0 until 12) {
            val angle = i * PI.toFloat() / 6f + time * .035f
            // Shimmer around the rim; the luminance-corrected core is already bright.
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
        val bitmap = atlas ?: return
        selectGlyph(ch.code)
        targetRect.set(x, y, x + width, y + height)
        glyphPaint.color = color
        glyphPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bitmap, sourceRect, targetRect, glyphPaint)
    }

    private fun sourceColor(x: Float, y: Float): Int {
        val col = x.toInt().coerceIn(0, columns - 1)
        val row = (y / CELL_HEIGHT).toInt().coerceIn(0, rows - 1)
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
