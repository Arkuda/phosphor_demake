package dev.phosphor.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs

internal enum class PageAnimation(val key: String, val label: String, val duration: Long, val saverDuration: Long) {
    CRT("crt", "CRT beam", 360L, 220L),
    TERMINAL("terminal", "terminal slide", 320L, 180L),
    MATRIX("matrix", "Matrix scan", 420L, 240L),
    NONE("none", "off · instant", 0L, 0L);

    companion object {
        fun fromKey(key: String?): PageAnimation = entries.firstOrNull { it.key == key } ?: CRT
    }
}

/** Transforms the live content tree, including the artwork WebView, without screen snapshots. */
internal class PageTransition(context: Context, private val content: View) : View(context) {
    var isRunning = false
        private set
    private var pendingSwap: (() -> Unit)? = null
    private var progress = 0f
    private var direction = 1
    private var ink = Inks.get(null)
    private var capturedTouch = false
    private var mode = PageAnimation.CRT
    private var scanEdge = 0f
    private val clip = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f * density
    }
    private val glyphs = "0101<>01/10_01[]1".toCharArray()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = LinearInterpolator()
        addUpdateListener {
            if (this@PageTransition.isRunning) {
                progress = it.animatedFraction
                if (progress >= .5f) commitPage()
                applyTransform()
                invalidate()
            }
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (this@PageTransition.isRunning) finish(true) }
        })
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }

    fun start(mode: PageAnimation, direction: Int, ink: Ink, batterySaver: Boolean, swap: () -> Unit) {
        if (isRunning) return
        if (mode == PageAnimation.NONE || !isAttachedToWindow || content.width == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            swap()
            return
        }
        this.mode = mode
        this.direction = direction
        this.ink = ink
        pendingSwap = swap
        progress = 0f
        isRunning = true
        animator.duration = if (batterySaver) mode.saverDuration else mode.duration
        applyTransform()
        animator.start()
    }

    private fun applyTransform() {
        when (mode) {
            PageAnimation.CRT -> {
                val opening = when {
                    progress < .43f -> 1f - smooth(progress / .43f)
                    progress < .56f -> 0f
                    else -> smooth((progress - .56f) / .44f)
                }.coerceAtLeast(.006f)
                content.scaleY = opening
                content.scaleX = .97f + .03f * opening
                val side = if (progress < .5f) -direction else direction
                content.translationX = side * content.width * .012f * (1f - opening)
            }
            PageAnimation.TERMINAL -> {
                val outgoing = progress < .5f
                val phase = if (outgoing) progress * 2f else (progress - .5f) * 2f
                val travel = if (outgoing) smooth(phase) else 1f - smooth(phase)
                content.translationX = (if (outgoing) -direction else direction) * content.width * travel
                scanEdge = content.translationX + if (outgoing == (direction > 0)) content.width else 0
            }
            PageAnimation.MATRIX -> {
                val phase = if (progress < .5f) progress * 2f else (progress - .5f) * 2f
                val travel = smooth(phase)
                scanEdge = content.height * if (direction > 0) travel else 1f - travel
                val row = scanEdge.toInt().coerceIn(0, content.height)
                val visibleAbove = (progress >= .5f) == (direction > 0)
                clip.set(0, if (visibleAbove) 0 else row, content.width, if (visibleAbove) row else content.height)
                content.clipBounds = clip
            }
            PageAnimation.NONE -> Unit
        }
    }

    private fun commitPage() {
        val swap = pendingSwap
        pendingSwap = null
        swap?.invoke()
    }

    fun finish(applyPending: Boolean) {
        if (!isRunning) return
        isRunning = false
        animator.cancel()
        content.scaleX = 1f
        content.scaleY = 1f
        content.translationX = 0f
        content.clipBounds = null
        val swap = pendingSwap
        pendingSwap = null
        progress = 0f
        invalidate()
        if (applyPending) swap?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning) return
        when (mode) {
            PageAnimation.CRT -> drawCrt(canvas)
            PageAnimation.TERMINAL -> drawTerminal(canvas)
            PageAnimation.MATRIX -> drawMatrix(canvas)
            PageAnimation.NONE -> Unit
        }
    }

    private fun drawCrt(canvas: Canvas) {
        val strength = (1f - content.scaleY).coerceIn(0f, 1f)
        val center = height / 2f
        val halfWidth = width * (.36f + .12f * strength)
        val left = width / 2f - halfWidth
        val right = width / 2f + halfWidth
        paint.color = TerminalUi.withAlpha(ink.accent, (strength * 22).toInt())
        canvas.drawRect(left, center - density * 10f, right, center + density * 10f, paint)
        paint.color = TerminalUi.withAlpha(ink.accent, (strength * 65).toInt())
        canvas.drawRect(left, center - density * 3f, right, center + density * 3f, paint)
        paint.color = TerminalUi.withAlpha(ink.bright, (strength * 205).toInt())
        canvas.drawRect(left, center - density * .7f, right, center + density * .7f, paint)
        val scan = if (direction > 0) progress else 1f - progress
        val beam = left + (right - left) * scan
        paint.color = TerminalUi.withAlpha(ink.accent, (strength * 150).toInt())
        canvas.drawRect(beam - density * 9f, center - density * 1.5f,
            beam + density * 9f, center + density * 1.5f, paint)
    }

    private fun drawTerminal(canvas: Canvas) {
        val strength = 1f - abs(progress * 2f - 1f)
        paint.color = TerminalUi.withAlpha(ink.accent, (strength * 32).toInt())
        canvas.drawRect(scanEdge - density * 8f, 0f, scanEdge + density * 8f, height.toFloat(), paint)
        paint.color = TerminalUi.withAlpha(ink.bright, (strength * 160).toInt())
        canvas.drawRect(scanEdge - density * .6f, 0f, scanEdge + density * .6f, height.toFloat(), paint)
        glyphPaint.color = TerminalUi.withAlpha(ink.accent, (strength * 190).toInt())
        canvas.drawText(if (direction > 0) ">_" else "<_", scanEdge + density * 4f, height * .5f, glyphPaint)
    }

    private fun drawMatrix(canvas: Canvas) {
        val strength = 1f - abs(progress * 2f - 1f)
        paint.color = TerminalUi.withAlpha(ink.accent, (strength * 35).toInt())
        canvas.drawRect(0f, scanEdge - density * 9f, width.toFloat(), scanEdge + density * 9f, paint)
        paint.color = TerminalUi.withAlpha(ink.bright, (strength * 170).toInt())
        canvas.drawRect(0f, scanEdge - density * .6f, width.toFloat(), scanEdge + density * .6f, paint)
        val step = density * 14f
        val phase = (progress * 32f).toInt()
        val columns = (width / step).toInt()
        for (column in 0..columns) {
            val trail = (column * 17 % 5) * step * .45f
            for (row in 0..1) {
                glyphPaint.color = TerminalUi.withAlpha(if (row == 0) ink.bright else ink.accent,
                    (strength * if (row == 0) 160f else 85f).toInt())
                val glyph = (column * 7 + phase + row * 3) % glyphs.size
                val y = scanEdge - direction * (trail + step * (row + .4f))
                canvas.drawText(glyphs, glyph, 1, column * step, y, glyphPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) capturedTouch = isRunning
        val consumed = capturedTouch
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) capturedTouch = false
        return consumed
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        finish(true)
    }

    override fun onDetachedFromWindow() {
        finish(false)
        super.onDetachedFromWindow()
    }

    private fun smooth(value: Float): Float = value * value * (3f - 2f * value)
}
