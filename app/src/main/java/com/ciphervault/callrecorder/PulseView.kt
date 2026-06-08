package com.ciphervault.callrecorder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class PulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF5350")
        style = Paint.Style.FILL
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33EF5350")
        style = Paint.Style.FILL
    }

    private var phase = 0f
    private var ripplePhase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = (it.animatedValue as Float) * Math.PI.toFloat() * 2
            ripplePhase = (it.animatedValue as Float) * Math.PI.toFloat() * 2 + 0.5f * Math.PI.toFloat()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = Math.min(width, height) / 3f

        val rippleScale = 1f + 0.4f * sin(ripplePhase)
        val rippleRadius = baseRadius * rippleScale * 1.6f
        ripplePaint.alpha = (40 + 30 * sin(ripplePhase * 0.7f)).toInt().coerceIn(20, 80)
        canvas.drawCircle(cx, cy, rippleRadius, ripplePaint)

        val scale = 1f + 0.08f * sin(phase)
        val radius = baseRadius * scale
        paint.alpha = (180 + 40 * sin(phase)).toInt().coerceIn(160, 230)
        canvas.drawCircle(cx, cy, radius, paint)
    }
}
