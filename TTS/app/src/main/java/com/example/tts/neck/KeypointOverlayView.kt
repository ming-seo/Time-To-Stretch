package com.example.tts.neck

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Draws keypoints (in upright analysis-image coordinates) over a FILL_CENTER front-camera PreviewView.
 * The front preview is mirrored, so x is flipped.
 */
class KeypointOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
    private var imageWidth = 0
    private var imageHeight = 0
    private var points: List<PointF> = emptyList()

    fun setPoints(imageWidth: Int, imageHeight: Int, points: List<PointF>) {
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.points = points
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageWidth == 0 || imageHeight == 0) return
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val dx = (width - imageWidth * scale) / 2
        val dy = (height - imageHeight * scale) / 2
        val radius = 6f * resources.displayMetrics.density
        for (p in points) {
            val x = width - (p.x * scale + dx)
            val y = p.y * scale + dy
            canvas.drawCircle(x, y, radius, paint)
        }
    }
}
