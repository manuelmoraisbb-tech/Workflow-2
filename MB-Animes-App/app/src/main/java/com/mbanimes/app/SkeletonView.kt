package com.mbanimes.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** Esqueleto animado (shimmer) mostrado enquanto uma página carrega: destaque + fileiras de cartazes. */
class SkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1B21.toInt() }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var gradientWidth = 0f
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private val blocks = ArrayList<RectF>()
    private val radius = 12f * d

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildBlocks(w, h)
        gradientWidth = w * 0.6f
        shader = LinearGradient(
            0f, 0f, gradientWidth, 0f,
            intArrayOf(0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
        shine.shader = shader
    }

    private fun buildBlocks(w: Int, h: Int) {
        blocks.clear()
        val pad = 16f * d
        val gap = 12f * d
        var y = 8f * d

        // destaque (banner)
        val heroH = minOf((w - 2 * pad) * 9f / 16f, 220f * d)
        blocks.add(RectF(pad, y, w - pad, y + heroH))
        y += heroH + 24f * d

        // fileiras: título + 3 cartazes
        val cardW = (w - 2 * pad - 2 * gap) / 3f
        val cardH = cardW * 1.5f
        while (y < h) {
            blocks.add(RectF(pad, y, pad + 140f * d, y + 16f * d))
            y += 16f * d + 12f * d
            for (i in 0 until 3) {
                val x = pad + i * (cardW + gap)
                blocks.add(RectF(x, y, x + cardW, y + cardH))
            }
            y += cardH + 28f * d
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (r in blocks) canvas.drawRoundRect(r, radius, radius, base)
        shader?.let {
            matrix.setTranslate(phase * (width + gradientWidth) - gradientWidth, 0f)
            it.setLocalMatrix(matrix)
            for (r in blocks) canvas.drawRoundRect(r, radius, radius, shine)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }
}
