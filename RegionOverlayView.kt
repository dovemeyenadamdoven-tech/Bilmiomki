package com.example.altyazidublaj

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Altyazı bölgesini gösteren çerçeve. İçinden sürükle = taşı, sağ alt köşeden sürükle = boyutlandır. */
class RegionOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    val rect = RectF()
    var onChanged: (() -> Unit)? = null

    private var fl = 0.1f
    private var ft = 0.65f
    private var fr = 0.9f
    private var fb = 0.85f

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
        style = Paint.Style.FILL
    }

    private var mode = 0 // 0: yok, 1: taşı, 2: boyutlandır
    private var lastX = 0f
    private var lastY = 0f

    fun setFractions(l: Float, t: Float, r: Float, b: Float) {
        fl = l; ft = t; fr = r; fb = b
        applyFractions()
    }

    fun fractions(): FloatArray = floatArrayOf(fl, ft, fr, fb)

    private fun applyFractions() {
        if (width > 0 && height > 0) {
            rect.set(fl * width, ft * height, fr * width, fb * height)
            invalidate()
        }
    }

    private fun saveFractions() {
        if (width > 0 && height > 0) {
            fl = rect.left / width
            ft = rect.top / height
            fr = rect.right / width
            fb = rect.bottom / height
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFractions()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(rect, boxPaint)
        canvas.drawCircle(rect.right, rect.bottom, 22f, handlePaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = 90f
                mode = when {
                    abs(e.x - rect.right) < hit && abs(e.y - rect.bottom) < hit -> 2
                    rect.contains(e.x, e.y) -> 1
                    else -> 0
                }
                lastX = e.x
                lastY = e.y
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (mode == 1) {
                    val w = rect.width()
                    val h = rect.height()
                    val l = (rect.left + dx).coerceIn(0f, width - w)
                    val t = (rect.top + dy).coerceIn(0f, height - h)
                    rect.offsetTo(l, t)
                } else if (mode == 2) {
                    rect.right = (rect.right + dx).coerceIn(rect.left + 80f, width.toFloat())
                    rect.bottom = (rect.bottom + dy).coerceIn(rect.top + 50f, height.toFloat())
                }
                lastX = e.x
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != 0) {
                    saveFractions()
                    onChanged?.invoke()
                }
                mode = 0
            }
        }
        return true
    }
}
