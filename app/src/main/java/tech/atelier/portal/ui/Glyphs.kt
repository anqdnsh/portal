package tech.atelier.portal.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

enum class Glyph { QUICK_SHARE, COMPRESS, SHARE, CONVERT, MERGE, COPY, PIN, CLOSE, TRASH, CHECK, CHEVRON_UP }

/** Line icons on a 24×24 grid, drawn straight onto the canvas. No image files. */
object Glyphs {
    private val r = RectF()
    private val path = Path()

    fun paint(color: Int, width: Float = 1.8f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    /** Draws [g] centred at ([cx], [cy]) with the given pixel [size]. [p] must be a stroke paint in grid units. */
    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, p: Paint) {
        val s = size / 24f
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(s, s)
        when (g) {
            Glyph.QUICK_SHARE -> {
                c.drawLine(12f, 3f, 12f, 15f, p)
                path.reset(); path.moveTo(7f, 8f); path.lineTo(12f, 3f); path.lineTo(17f, 8f); c.drawPath(path, p)
                r.set(5f, 7f, 19f, 21f); c.drawArc(r, 20f, 140f, false, p)
            }
            Glyph.COMPRESS -> {
                r.set(3f, 4f, 21f, 9f); c.drawRoundRect(r, 1.5f, 1.5f, p)
                path.reset(); path.moveTo(5f, 9f); path.lineTo(5f, 18f); path.quadTo(5f, 20f, 7f, 20f)
                path.lineTo(17f, 20f); path.quadTo(19f, 20f, 19f, 18f); path.lineTo(19f, 9f); c.drawPath(path, p)
                c.drawLine(10f, 13f, 14f, 13f, p)
            }
            Glyph.SHARE -> {
                c.drawCircle(18f, 5f, 2.5f, p); c.drawCircle(6f, 12f, 2.5f, p); c.drawCircle(18f, 19f, 2.5f, p)
                c.drawLine(8.2f, 10.8f, 15.8f, 6.4f, p); c.drawLine(8.2f, 13.2f, 15.8f, 17.6f, p)
            }
            Glyph.CONVERT -> {
                r.set(4f, 4f, 20f, 20f)
                c.drawArc(r, 200f, 120f, false, p)
                c.drawArc(r, 20f, 120f, false, p)
                path.reset(); path.moveTo(20f, 4.5f); path.lineTo(19.2f, 9.2f); path.lineTo(14.6f, 8.2f); c.drawPath(path, p)
                path.reset(); path.moveTo(4f, 19.5f); path.lineTo(4.8f, 14.8f); path.lineTo(9.4f, 15.8f); c.drawPath(path, p)
            }
            Glyph.MERGE -> {
                r.set(4f, 3f, 14f, 16f); c.drawRoundRect(r, 1.5f, 1.5f, p)
                r.set(10f, 8f, 20f, 21f); c.drawRoundRect(r, 1.5f, 1.5f, p)
            }
            Glyph.COPY -> {
                r.set(8f, 8f, 20f, 20f); c.drawRoundRect(r, 2f, 2f, p)
                path.reset(); path.moveTo(16f, 8f); path.lineTo(16f, 6f); path.quadTo(16f, 4f, 14f, 4f)
                path.lineTo(6f, 4f); path.quadTo(4f, 4f, 4f, 6f); path.lineTo(4f, 14f); path.quadTo(4f, 16f, 6f, 16f)
                path.lineTo(8f, 16f); c.drawPath(path, p)
            }
            Glyph.PIN -> {
                c.drawLine(12f, 16f, 12f, 21f, p)
                path.reset(); path.moveTo(9f, 3f); path.lineTo(15f, 3f); path.lineTo(14f, 9f); path.lineTo(18f, 13f)
                path.lineTo(18f, 16f); path.lineTo(6f, 16f); path.lineTo(6f, 13f); path.lineTo(10f, 9f); path.close()
                c.drawPath(path, p)
            }
            Glyph.CLOSE -> {
                c.drawLine(6f, 6f, 18f, 18f, p); c.drawLine(18f, 6f, 6f, 18f, p)
            }
            Glyph.TRASH -> {
                c.drawLine(4f, 7f, 20f, 7f, p)
                path.reset(); path.moveTo(9f, 7f); path.lineTo(9f, 4f); path.lineTo(15f, 4f); path.lineTo(15f, 7f); c.drawPath(path, p)
                path.reset(); path.moveTo(6f, 7f); path.lineTo(7f, 20f); path.lineTo(17f, 20f); path.lineTo(18f, 7f); c.drawPath(path, p)
            }
            Glyph.CHEVRON_UP -> {
                path.reset(); path.moveTo(6f, 15f); path.lineTo(12f, 9f); path.lineTo(18f, 15f); c.drawPath(path, p)
            }
            Glyph.CHECK -> {
                path.reset(); path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f); c.drawPath(path, p)
            }
        }
        c.restore()
    }
}
