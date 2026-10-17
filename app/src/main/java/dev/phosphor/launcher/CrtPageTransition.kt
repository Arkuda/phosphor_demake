package dev.phosphor.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

/** Transforms the live content tree, including the artwork WebView, without screen snapshots. */
internal class CrtPageTransition(context: Context, private val content: View) : View(context) {
    var isRunning = false
        private set
    private var pendingSwap: (() -> Unit)? = null
    private var progress = 0f
    private var direction = 1
    private var ink = Inks.get(null)
    private var capturedTouch = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        interpolator = LinearInterpolator()
        addUpdateListener {
            if (this@CrtPageTransition.isRunning) {
                progress = it.animatedFraction
                if (progress >= .5f) commitPage()
                val opening = when {
                    progress < .43f -> 1f - smooth(progress / .43f)
                    progress < .56f -> 0f
                    else -> smooth((progress - .56f) / .44f)
                }.coerceAtLeast(.006f)
                content.scaleY = opening
                content.scaleX = .97f + .03f * opening
                val side = if (progress < .5f) -direction else direction
                content.translationX = side * content.width * .012f * (1f - opening)
                invalidate()
            }
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (this@CrtPageTransition.isRunning) finish(true) }
        })
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }

    fun start(direction: Int, ink: Ink, batterySaver: Boolean, swap: () -> Unit) {
        if (isRunning) return
        if (!isAttachedToWindow || content.width == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            swap()
            return
        }
        this.direction = direction
        this.ink = ink
        pendingSwap = swap
        progress = 0f
        isRunning = true
        animator.duration = if (batterySaver) 220L else 360L
        animator.start()
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
        val swap = pendingSwap
        pendingSwap = null
        progress = 0f
        invalidate()
        if (applyPending) swap?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning) return
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
