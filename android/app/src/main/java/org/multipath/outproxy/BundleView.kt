package org.multipath.outproxy

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class BundleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class ExitState { IDLE, BUILDING, READY, ERROR }

    private val dp = resources.displayMetrics.density
    private val cLine = context.getColor(R.color.line)
    private val cAccent = context.getColor(R.color.accent)
    private val cOk = context.getColor(R.color.ok)
    private val cError = context.getColor(R.color.error)
    private val cText = context.getColor(R.color.text)
    private val cTextDim = context.getColor(R.color.text_dim)
    private val cBg = context.getColor(R.color.bg)
    private val linkMs = resources.getInteger(R.integer.anim_link).toFloat()
    private val exitMs = resources.getInteger(R.integer.anim_exit).toFloat()

    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; color = cLine; strokeCap = Paint.Cap.ROUND
    }
    private val lit = Paint(base).apply { strokeWidth = 1.6f * dp; color = cAccent }
    private val exitBase = Paint(base).apply {
        strokeWidth = 2f * dp; pathEffect = DashPathEffect(floatArrayOf(4 * dp, 5 * dp), 0f)
    }
    private val exitLit = Paint(base).apply { strokeWidth = 3f * dp; color = cOk }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cTextDim
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = resources.getFont(R.font.plex_mono_regular); letterSpacing = 0.06f
    }

    private var n = 16
    private var paths = emptyList<Path>()
    private var measures = emptyList<PathMeasure>()
    private var order = IntArray(0)
    private var progress = FloatArray(0)
    private var target = FloatArray(0)
    private var exitProgress = 0f
    private var exitTarget = 0f
    private var exitState = ExitState.IDLE
    private var lastFrame = 0L
    private val seg = Path()

    private var px = 0f; private var kx = 0f; private var cy = 0f; private var ex = 0f

    fun set(links: Int, litLinks: Int, state: ExitState) {
        if (links != n || paths.isEmpty()) {
            n = links
            progress = FloatArray(n); target = FloatArray(n)
            order = (0 until n).sortedBy { (it * 7919 + 13) % 101 }.toIntArray()
            rebuild()
        }
        val k = litLinks.coerceIn(0, n)
        for (i in 0 until n) target[order[i]] = if (i < k) 1f else 0f
        exitTarget = if (state == ExitState.READY) 1f else 0f
        exitState = state
        if (!ValueAnimator.areAnimatorsEnabled()) {
            target.copyInto(progress); exitProgress = exitTarget
        }
        contentDescription = context.getString(R.string.bundle_desc, k, n)
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        if (width == 0) return
        val pad = 10 * dp
        px = pad; kx = width * 0.68f; ex = width - pad
        cy = (height - 18 * dp) / 2f
        val spread = cy - pad
        val list = ArrayList<Path>(n)
        for (i in 0 until n) {
            val o = if (n == 1) 0f else (i / (n - 1f) - 0.5f) * 2f * spread
            list += Path().apply {
                moveTo(px, cy)
                cubicTo(px + (kx - px) * 0.28f, cy + o * 1.05f, kx - (kx - px) * 0.32f, cy + o * 1.05f, kx, cy)
            }
        }
        paths = list
        measures = list.map { PathMeasure(it, false) }
    }

    override fun onDraw(c: Canvas) {
        if (paths.isEmpty()) rebuild()
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame) / 1e6f
        lastFrame = now
        var animating = false

        for (i in 0 until n) {
            c.drawPath(paths[i], base)
            val p = step(progress[i], target[i], dt / linkMs)
            progress[i] = p
            if (p != target[i]) animating = true
            if (p > 0f) {
                val m = measures[i]
                seg.reset()
                m.getSegment(0f, m.length * p, seg, true)
                c.drawPath(seg, lit)
            }
        }

        c.drawLine(kx, cy, ex, cy, exitBase)
        exitProgress = step(exitProgress, exitTarget, dt / exitMs)
        if (exitProgress != exitTarget) animating = true
        if (exitProgress > 0f) c.drawLine(kx, cy, kx + (ex - kx) * ease(exitProgress), cy, exitLit)

        fill.color = cText
        c.drawCircle(px, cy, 3.5f * dp, fill)
        fill.color = when (exitState) {
            ExitState.IDLE -> cLine
            ExitState.BUILDING -> cAccent
            ExitState.READY -> cOk
            ExitState.ERROR -> cError
        }
        val r = 7f * dp * (if (exitState == ExitState.READY) 1f + 0.25f * (1f - ease(exitProgress)) * exitProgress else 1f)
        c.drawCircle(kx, cy, r, fill)
        fill.color = cBg
        c.drawCircle(kx, cy, r - 2.5f * dp, fill)

        val ly = height - 3 * dp
        label.textAlign = Paint.Align.LEFT
        c.drawText(context.getString(R.string.bundle_phone), 0f, ly, label)
        label.textAlign = Paint.Align.CENTER
        c.drawText(context.getString(R.string.bundle_exit), kx, ly, label)
        label.textAlign = Paint.Align.RIGHT
        c.drawText(context.getString(R.string.bundle_net), width.toFloat(), ly, label)

        if (animating) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun step(v: Float, t: Float, d: Float) =
        if (v < t) minOf(t, v + d) else if (v > t) maxOf(t, v - d * 2) else v

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    companion object {
        fun litLinks(s: Status): Int =
            if (s.expectedTunnels == 0) 0
            else (s.clientTunnels.toFloat() / s.expectedTunnels * s.links).roundToInt()
    }
}
