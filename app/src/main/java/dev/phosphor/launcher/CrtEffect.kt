package dev.phosphor.launcher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/** Shared glass treatment; shaders and their tiny phosphor texture are retained between frames. */
internal class CrtEffect {
    private val texture = Paint()
    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val bounds = RectF()
    private val vignetteMatrix = Matrix()
    private val sweepMatrix = Matrix()
    private var accent = 0
    private var width = 0f
    private var height = 0f

    fun draw(u: TerminalUi) {
        if (u.ink.print) return
        if (accent != u.ink.accent) {
            accent = u.ink.accent
            // One dark raster row and a faint phosphor column per triad, without a frame-sized layer.
            val pixels = IntArray(9) { i ->
                if (i / 3 == 2) 0x66000000
                else if (i % 3 == 0) TerminalUi.withAlpha(u.ink.accent, 12)
                else 0
            }
            val tile = Bitmap.createBitmap(pixels, 3, 3, Bitmap.Config.ARGB_8888)
            texture.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            sweep.shader = LinearGradient(0f, 0f, 0f, SWEEP_HEIGHT,
                intArrayOf(0, TerminalUi.withAlpha(u.ink.accent, 18), 0), null, Shader.TileMode.CLAMP)
            frame.color = TerminalUi.withAlpha(u.ink.accent, 105)
        }
        if (width != u.width || height != u.height) {
            width = u.width
            height = u.height
            bounds.set(1f, 1f, width - 1f, height - 1f)
            val shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(0, 0x10000000, 0x68000000), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
            vignetteMatrix.setScale(width * .7f, height * .66f)
            vignetteMatrix.postTranslate(width / 2f, height / 2f)
            shader.setLocalMatrix(vignetteMatrix)
            vignette.shader = shader
        }
        u.canvas.drawRect(0f, 0f, width, height, texture)
        // A slow, low-contrast refresh band; no full-screen flicker or abrupt flashes.
        val top = (u.time * 55f) % (height + SWEEP_HEIGHT) - SWEEP_HEIGHT
        sweepMatrix.setTranslate(0f, top)
        sweep.shader.setLocalMatrix(sweepMatrix)
        u.canvas.drawRect(0f, top, width, top + SWEEP_HEIGHT, sweep)
        u.canvas.drawRect(0f, 0f, width, height, vignette)
        if(u.frames)u.canvas.drawRoundRect(bounds, 14f, 14f, frame)
    }

    private companion object {
        const val SWEEP_HEIGHT = 96f
    }
}
