package io.hwpbyhwp.core.layout

import io.hwpbyhwp.core.model.Affine
import io.hwpbyhwp.core.model.ArcKind
import io.hwpbyhwp.core.model.Pt
import io.hwpbyhwp.core.model.ShapeKind
import io.hwpbyhwp.core.model.ShapeObject
import kotlin.math.min

/** Builds page-space path operations for a drawing object's outline under transform [m]. */
internal object ShapePaths {

    /** Bezier handle length for a quarter ellipse. */
    private const val K = 0.5522847498

    fun outline(s: ShapeObject, m: Affine): List<PathOp>? {
        val p = s.points
        val b = Builder(m)
        when (s.kind) {
            ShapeKind.LINE -> if (p.size >= 2) { b.move(p[0]); b.line(p[1]) } else return null
            ShapeKind.RECTANGLE -> if (p.size >= 4) rect(b, p, s.roundRatio) else return null
            ShapeKind.POLYGON -> if (p.size >= 2) {
                b.move(p[0]); p.drop(1).forEach(b::line)
                // Hangul closes polygons whose last vertex returns to the first.
                if (p.size > 2 && p.first() == p.last()) b.close()
            } else return null
            ShapeKind.CURVE -> if (p.size >= 2) { b.move(p[0]); p.drop(1).forEach(b::line) } else return null
            ShapeKind.ELLIPSE -> if (p.size >= 3) {
                val (c, a1, a2) = p
                val u = Pt(a1.x - c.x, a1.y - c.y)
                val v = Pt(a2.x - c.x, a2.y - c.y)
                b.move(Pt(c.x + u.x, c.y + u.y))
                quarter(b, c, u, v); quarter(b, c, v, Pt(-u.x, -u.y)); quarter(b, c, Pt(-u.x, -u.y), Pt(-v.x, -v.y)); quarter(b, c, Pt(-v.x, -v.y), u)
                b.close()
            } else return null
            ShapeKind.ARC -> if (p.size >= 3) {
                val (c, a1, a2) = p
                val u = Pt(a1.x - c.x, a1.y - c.y)
                val v = Pt(a2.x - c.x, a2.y - c.y)
                b.move(a1)
                quarter(b, c, u, v)
                when (s.arcKind) {
                    ArcKind.PIE -> { b.line(c); b.close() }
                    ArcKind.CHORD -> b.close()
                    ArcKind.ARC -> Unit
                }
            } else return null
            ShapeKind.CONTAINER, ShapeKind.PICTURE -> return null
        }
        return b.ops
    }

    /** Quarter ellipse from c+u to c+v, where u and v are conjugate semi-axes. */
    private fun quarter(b: Builder, c: Pt, u: Pt, v: Pt) {
        b.cubic(
            Pt(c.x + u.x + K * v.x, c.y + u.y + K * v.y),
            Pt(c.x + v.x + K * u.x, c.y + v.y + K * u.y),
            Pt(c.x + v.x, c.y + v.y),
        )
    }

    private fun rect(b: Builder, p: List<Pt>, ratio: Int) {
        val left = p.minOf { it.x }; val right = p.maxOf { it.x }
        val top = p.minOf { it.y }; val bottom = p.maxOf { it.y }
        val r = min(right - left, bottom - top) / 2 * ratio.coerceIn(0, 100) / 100.0
        if (r <= 0.0) {
            b.move(Pt(left, top)); b.line(Pt(right, top)); b.line(Pt(right, bottom)); b.line(Pt(left, bottom)); b.close()
            return
        }
        b.move(Pt(left + r, top))
        b.line(Pt(right - r, top)); quarter(b, Pt(right - r, top + r), Pt(0.0, -r), Pt(r, 0.0))
        b.line(Pt(right, bottom - r)); quarter(b, Pt(right - r, bottom - r), Pt(r, 0.0), Pt(0.0, r))
        b.line(Pt(left + r, bottom)); quarter(b, Pt(left + r, bottom - r), Pt(0.0, r), Pt(-r, 0.0))
        b.line(Pt(left, top + r)); quarter(b, Pt(left + r, top + r), Pt(-r, 0.0), Pt(0.0, -r))
        b.close()
    }

    private class Builder(private val m: Affine) {
        val ops = mutableListOf<PathOp>()
        private fun tx(p: Pt) = m.x(p.x, p.y).toFloat()
        private fun ty(p: Pt) = m.y(p.x, p.y).toFloat()
        fun move(p: Pt) { ops += PathOp.MoveTo(tx(p), ty(p)) }
        fun line(p: Pt) { ops += PathOp.LineTo(tx(p), ty(p)) }
        fun cubic(c1: Pt, c2: Pt, p: Pt) { ops += PathOp.CubicTo(tx(c1), ty(c1), tx(c2), ty(c2), tx(p), ty(p)) }
        fun close() { ops += PathOp.Close }
    }
}
