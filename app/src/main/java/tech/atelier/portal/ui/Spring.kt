package tech.atelier.portal.ui

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A real spring, stepped once per display frame (120 times a second on a 120 Hz screen).
 * Because it keeps its own position and speed, changing the target mid-way (opening, then
 * closing before it has settled) carries the motion on smoothly instead of jumping.
 *
 * [stiffness] sets how fast it moves; [dampingRatio] below 1 gives a little overshoot.
 */
class Spring(
    var stiffness: Float = 260f,
    var dampingRatio: Float = 0.68f,
    private val onUpdate: (Float) -> Unit,
) : Choreographer.FrameCallback {

    var value = 0f
        private set
    var target = 0f
        private set
    private var velocity = 0f
    private var running = false
    private var lastNs = 0L
    private var onRest: (() -> Unit)? = null

    val isRunning get() = running

    fun animateTo(to: Float, stiffness: Float = this.stiffness, damping: Float = this.dampingRatio, then: (() -> Unit)? = null) {
        target = to
        this.stiffness = stiffness
        this.dampingRatio = damping
        onRest = then
        if (!running) {
            running = true
            lastNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun snapTo(v: Float) {
        cancel()
        value = v
        target = v
        velocity = 0f
        onUpdate(v)
    }

    fun cancel() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
        onRest = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNs == 0L) 1f / 120f else ((frameTimeNanos - lastNs) / 1e9f).coerceIn(0f, 1f / 20f)
        lastNs = frameTimeNanos
        val w = sqrt(stiffness)
        val c = 2f * dampingRatio * w
        var left = dt
        // Small fixed sub-steps keep it stable and identical at 60, 90 or 120 Hz.
        while (left > 0f) {
            val h = min(left, 1f / 480f)
            val a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            left -= h
        }
        if (abs(velocity) < 0.003f && abs(value - target) < 0.0008f) {
            value = target
            velocity = 0f
            running = false
            onUpdate(value)
            val r = onRest
            onRest = null
            r?.invoke()
        } else {
            onUpdate(value)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}

/** Fixed presets so every motion in Portal feels like the same material. */
object Motion {
    // Opening: lively, ~5% overshoot, settles in about 0.4 s.
    const val OPEN_K = 260f
    const val OPEN_Z = 0.68f
    // Closing: firm and critically damped, so it tucks back into the notch without bouncing out.
    const val CLOSE_K = 420f
    const val CLOSE_Z = 1f
    // Small things (hover, press, a new item landing).
    const val POP_K = 520f
    const val POP_Z = 0.62f
    const val STAGGER_MS = 28L
}
