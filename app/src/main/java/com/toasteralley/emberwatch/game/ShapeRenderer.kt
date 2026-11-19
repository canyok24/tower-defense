package com.toasteralley.emberwatch.game

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.toasteralley.emberwatch.core.MobLook
import com.toasteralley.emberwatch.core.MobShape
import com.toasteralley.emberwatch.core.OutlineStyle
import com.toasteralley.emberwatch.core.Palette
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws mobs from unit-radius Paths built once per shape. Each draw transforms
 * the cached path into a reused scratch path (so stroke widths and dash lengths
 * stay in pixels) and issues 1-3 drawPath calls with reused Paints.
 */
class ShapeRenderer(strokePx: Float) {
    private val unit: Array<Path> = Array(MobShape.entries.size) { buildUnit(MobShape.entries[it]) }
    private val scratch = Path()
    private val matrix = Matrix()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val solid = strokePaint(strokePx)
    private val dashed = strokePaint(strokePx).apply { pathEffect = DashPathEffect(floatArrayOf(strokePx * 4, strokePx * 2.5f), 0f) }
    private val dotted = strokePaint(strokePx * 1.2f).apply {
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(0.1f, strokePx * 2.6f), 0f)
    }
    // Glow = two wide translucent strokes under a solid one (no blur: stays cheap on hardware canvases).
    private val glow = strokePaint(strokePx * 4.5f)
    private val glowMid = strokePaint(strokePx * 2.4f)
    private val thin = strokePaint(strokePx * 0.6f)

    private fun strokePaint(w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = w; strokeJoin = Paint.Join.ROUND
    }

    fun draw(c: Canvas, look: MobLook, cx: Float, cy: Float, radiusPx: Float, age: Float, flash: Boolean, hpFrac: Float) {
        val color = if (flash) Palette.TEXT else Palette.MOB_COLORS[look.colorIndex]
        val alpha = if (look.pulsing) (175 + 80 * sin(age * 4f)).toInt() else 255
        transform(look.shape, cx, cy, radiusPx, look.rotationSpeed * age)

        if (look.shape != MobShape.SPIRAL) {
            fill.color = color; fill.alpha = (alpha * (0.12f + 0.25f * hpFrac)).toInt()
            c.drawPath(scratch, fill)
        }
        when (look.style) {
            OutlineStyle.SOLID -> stroke(c, solid, color, alpha)
            OutlineStyle.DASHED -> stroke(c, dashed, color, alpha)
            OutlineStyle.DOTTED -> stroke(c, dotted, color, alpha)
            OutlineStyle.DOUBLE_LINE -> {
                stroke(c, thin, color, alpha)
                transform(look.shape, cx, cy, radiusPx * 0.72f, look.rotationSpeed * age)
                stroke(c, thin, color, alpha)
            }
            OutlineStyle.GLOW -> { stroke(c, glow, color, alpha / 6); stroke(c, glowMid, color, alpha / 3); stroke(c, solid, color, alpha) }
        }
        if (look.innerShape >= 0) {
            transform(MobShape.entries[look.innerShape], cx, cy, radiusPx * 0.4f, -look.rotationSpeed * age * 1.5f)
            stroke(c, thin, color, alpha)
        }
    }

    /** Draws a bare shape outline (used for menu decoration / UW icons). */
    fun outline(c: Canvas, shape: MobShape, cx: Float, cy: Float, r: Float, color: Int) {
        transform(shape, cx, cy, r, 0f); stroke(c, solid, color, 255)
    }

    private fun stroke(c: Canvas, p: Paint, color: Int, alpha: Int) { p.color = color; p.alpha = alpha.coerceIn(0, 255); c.drawPath(scratch, p) }

    private fun transform(shape: MobShape, cx: Float, cy: Float, r: Float, rotRad: Float) {
        matrix.reset()
        matrix.setScale(r, r)
        if (rotRad != 0f) matrix.postRotate(Math.toDegrees(rotRad.toDouble()).toFloat())
        matrix.postTranslate(cx, cy)
        unit[shape.ordinal].transform(matrix, scratch)
    }

    companion object {
        private fun poly(pts: FloatArray) = Path().apply {
            moveTo(pts[0], pts[1]); var i = 2
            while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
            close()
        }

        private fun regular(n: Int, inner: Float = 0f): Path {
            val k = if (inner > 0) n * 2 else n
            val pts = FloatArray(k * 2)
            for (i in 0 until k) {
                val a = -Math.PI / 2 + i * 2 * Math.PI / k
                val r = if (inner > 0 && i % 2 == 1) inner else 1f
                pts[i * 2] = (cos(a) * r).toFloat(); pts[i * 2 + 1] = (sin(a) * r).toFloat()
            }
            return poly(pts)
        }

        private fun circle(x: Float, y: Float, r: Float) = Path().apply { addCircle(x, y, r, Path.Direction.CW) }

        fun buildUnit(s: MobShape): Path = when (s) {
            MobShape.PENTAGON -> regular(5)
            MobShape.HEXAGON -> regular(6)
            MobShape.HEPTAGON -> regular(7)
            MobShape.OCTAGON -> regular(8)
            MobShape.NONAGON -> regular(9)
            MobShape.DECAGON -> regular(10)
            MobShape.STAR -> regular(5, inner = 0.45f)
            MobShape.CRESCENT -> Path().apply { op(circle(0f, 0f, 1f), circle(0.45f, -0.25f, 0.85f), Path.Op.DIFFERENCE) }
            MobShape.CROSS -> poly(floatArrayOf(-0.3f, -1f, 0.3f, -1f, 0.3f, -0.3f, 1f, -0.3f, 1f, 0.3f, 0.3f, 0.3f,
                0.3f, 1f, -0.3f, 1f, -0.3f, 0.3f, -1f, 0.3f, -1f, -0.3f, -0.3f, -0.3f))
            MobShape.ARROWHEAD -> poly(floatArrayOf(0f, 1f, 0.8f, -0.8f, 0f, -0.35f, -0.8f, -0.8f)) // points down (toward the wall)
            MobShape.CHEVRON -> poly(floatArrayOf(-0.9f, -0.7f, 0f, 0.1f, 0.9f, -0.7f, 0.9f, -0.1f, 0f, 0.7f, -0.9f, -0.1f))
            MobShape.PARALLELOGRAM -> poly(floatArrayOf(-0.55f, -0.6f, 1f, -0.6f, 0.55f, 0.6f, -1f, 0.6f))
            MobShape.TRAPEZOID -> poly(floatArrayOf(-0.5f, -0.6f, 0.5f, -0.6f, 1f, 0.6f, -1f, 0.6f))
            MobShape.ELLIPSE -> Path().apply { addOval(RectF(-1f, -0.6f, 1f, 0.6f), Path.Direction.CW) }
            MobShape.ROUNDED_RECT -> Path().apply { addRoundRect(RectF(-1f, -0.65f, 1f, 0.65f), 0.35f, 0.35f, Path.Direction.CW) }
            MobShape.RING -> Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addCircle(0f, 0f, 1f, Path.Direction.CW); addCircle(0f, 0f, 0.55f, Path.Direction.CCW)
            }
            MobShape.HEART -> Path().apply {
                moveTo(0f, 0.95f)
                cubicTo(-1.3f, 0.1f, -0.9f, -1.0f, 0f, -0.45f)
                cubicTo(0.9f, -1.0f, 1.3f, 0.1f, 0f, 0.95f)
                close()
            }
            MobShape.CLOVER -> Path().apply {
                op(circle(0f, -0.48f, 0.5f), circle(0f, 0.48f, 0.5f), Path.Op.UNION)
                op(circle(-0.48f, 0f, 0.5f), Path.Op.UNION)
                op(circle(0.48f, 0f, 0.5f), Path.Op.UNION)
            }
            MobShape.YIN_YANG -> Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addCircle(0f, 0f, 1f, Path.Direction.CW)
                moveTo(0f, -1f)
                arcTo(RectF(-1f, -1f, 1f, 1f), -90f, 180f)
                arcTo(RectF(-0.5f, 0f, 0.5f, 1f), 90f, 180f)
                arcTo(RectF(-0.5f, -1f, 0.5f, 0f), 90f, -180f)
                close()
                addCircle(0f, -0.5f, 0.14f, Path.Direction.CW)
                addCircle(0f, 0.5f, 0.14f, Path.Direction.CW)
            }
            MobShape.SPIRAL -> Path().apply {
                val turns = 3.2; val steps = 96
                for (i in 0..steps) {
                    val t = i / steps.toDouble()
                    val a = t * turns * 2 * Math.PI
                    val r = (0.08 + 0.92 * t).toFloat()
                    val x = (cos(a) * r).toFloat(); val y = (sin(a) * r).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
        }
    }
}
