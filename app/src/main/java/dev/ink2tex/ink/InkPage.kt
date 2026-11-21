package dev.ink2tex.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.ceil

internal data class InkPoint(val x: Float, val y: Float, val force: Int, val timeOffsetMs: Long? = null)
internal data class InkStroke(val points: List<InkPoint>, val startMicros: Long? = null)
internal data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerY get() = (top + bottom) / 2
}
internal data class InkPage(val strokes: List<InkStroke>) {
    val bounds: InkBounds? get() = if (strokes.isEmpty()) null else boundsOf(strokes)
}

internal fun boundsOf(strokes: List<InkStroke>): InkBounds {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    for (stroke in strokes) for (point in stroke.points) {
        left = minOf(left, point.x)
        top = minOf(top, point.y)
        right = maxOf(right, point.x)
        bottom = maxOf(bottom, point.y)
    }
    return InkBounds(left, top, right, bottom)
}

internal object InkRaster {
    fun render(page: InkPage, maxEdge: Int = 1800): Bitmap {
        val bounds = page.bounds ?: return Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.WHITE) }
        val margin = 3f
        val scale = minOf(12f, maxEdge / (maxOf(bounds.height, bounds.width) + margin * 2))
        val bitmap = Bitmap.createBitmap(
            ceil((bounds.width + margin * 2) * scale).toInt().coerceAtLeast(1),
            ceil((bounds.height + margin * 2) * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(margin - bounds.left, margin - bounds.top)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 0.32f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in page.strokes) {
            val point = stroke.points.first()
            if (stroke.points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(point.x, point.y, 0.16f, paint)
            } else {
                paint.style = Paint.Style.STROKE
                val path = Path().apply {
                    moveTo(point.x, point.y)
                    for (index in 1 until stroke.points.size) {
                        val p = stroke.points[index]
                        lineTo(p.x, p.y)
                    }
                }
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }
}
