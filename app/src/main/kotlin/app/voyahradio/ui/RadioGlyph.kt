package app.voyahradio.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal class RadioGlyph(private val kind: Kind, private val color: Int) {
    enum class Kind {
        SEARCH, CLEAR, STAR, STAR_FILLED, PLAY, PAUSE, PREVIOUS, NEXT, SIGNAL, SETTINGS, BACK, UP, DOWN
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun draw(canvas: Canvas, width: Float, height: Float, size: Float) {
        val scale = min(size, min(width, height)) / 24f
        canvas.save()
        canvas.translate((width - 24 * scale) / 2f, (height - 24 * scale) / 2f)
        canvas.scale(scale, scale)
        paint.setColor(color)
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(1.7f)
        paint.setStrokeCap(Paint.Cap.ROUND)
        paint.setStrokeJoin(Paint.Join.ROUND)

        when (kind) {
            Kind.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 5.5f, paint)
                canvas.drawLine(14.5f, 14.5f, 19.5f, 19.5f, paint)
            }

            Kind.CLEAR -> {
                canvas.drawLine(6.5f, 6.5f, 17.5f, 17.5f, paint)
                canvas.drawLine(6.5f, 17.5f, 17.5f, 6.5f, paint)
            }

            Kind.STAR, Kind.STAR_FILLED -> {
                if (kind == Kind.STAR_FILLED) paint.setStyle(Paint.Style.FILL_AND_STROKE)
                path.reset()
                for (point in 0..9) {
                    val angle = Math.PI * (point / 5.0 - 0.5)
                    val radius = if (point % 2 == 0) 8.7f else 4.2f
                    val x = 12 + cos(angle).toFloat() * radius
                    val y = 12 + sin(angle).toFloat() * radius
                    if (point == 0) path.moveTo(x, y)
                    else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }

            Kind.PLAY -> triangle(canvas, 8f, 19f, 5f, 19f)
            Kind.PAUSE -> {
                paint.setStyle(Paint.Style.FILL)
                canvas.drawRoundRect(7f, 5f, 10.5f, 19f, 0.8f, 0.8f, paint)
                canvas.drawRoundRect(13.5f, 5f, 17f, 19f, 0.8f, 0.8f, paint)
            }

            Kind.PREVIOUS -> {
                canvas.drawLine(6f, 6f, 6f, 18f, paint)
                triangle(canvas, 18f, 8.5f, 6f, 18f)
            }

            Kind.NEXT -> {
                canvas.drawLine(18f, 6f, 18f, 18f, paint)
                triangle(canvas, 6f, 15.5f, 6f, 18f)
            }

            Kind.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 6.2f, paint)
                canvas.drawCircle(12f, 12f, 2.2f, paint)
                for (tooth in 0..7) {
                    val dx = cos(tooth * Math.PI / 4).toFloat()
                    val dy = sin(tooth * Math.PI / 4).toFloat()
                    canvas.drawLine(
                        12 + 6.2f * dx, 12 + 6.2f * dy,
                        12 + 9 * dx, 12 + 9 * dy, paint
                    )
                }
            }

            Kind.BACK -> {
                canvas.drawLine(5f, 12f, 19f, 12f, paint)
                canvas.drawLine(5f, 12f, 11f, 6f, paint)
                canvas.drawLine(5f, 12f, 11f, 18f, paint)
            }

            Kind.UP, Kind.DOWN -> {
                val tip = if (kind == Kind.UP) 8.5f else 15.5f
                val end = if (kind == Kind.UP) 15.5f else 8.5f
                canvas.drawLine(6f, end, 12f, tip, paint)
                canvas.drawLine(12f, tip, 18f, end, paint)
            }

            Kind.SIGNAL -> {
                paint.setStrokeWidth(1.25f)
                canvas.drawArc(RadioGlyph.OUTER_SIGNAL, 140f, 80f, false, paint)
                canvas.drawArc(RadioGlyph.OUTER_SIGNAL, -40f, 80f, false, paint)
                canvas.drawArc(RadioGlyph.INNER_SIGNAL, 140f, 80f, false, paint)
                canvas.drawArc(RadioGlyph.INNER_SIGNAL, -40f, 80f, false, paint)
                paint.setStyle(Paint.Style.FILL)
                canvas.drawCircle(12f, 12f, 2f, paint)
            }
        }
        canvas.restore()
    }

    private fun triangle(canvas: Canvas, base: Float, tip: Float, top: Float, bottom: Float) {
        paint.setStyle(Paint.Style.FILL)
        path.reset()
        path.moveTo(base, top)
        path.lineTo(tip, 12f)
        path.lineTo(base, bottom)
        path.close()
        canvas.drawPath(path, paint)
    }

    companion object {
        private val OUTER_SIGNAL = RectF(2f, 2f, 22f, 22f)
        private val INNER_SIGNAL = RectF(6f, 6f, 18f, 18f)
    }
}
