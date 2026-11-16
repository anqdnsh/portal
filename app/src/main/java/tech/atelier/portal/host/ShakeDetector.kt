package tech.atelier.portal.host

import kotlin.math.abs

/**
 * Spots a quick left-right jiggle: four changes of direction, each after at least
 * ~28 dp of travel, within 0.8 s. Slow or small wobbles don't count.
 */
class ShakeDetector(density: Float) {
    private val minTravel = 28f * density
    private var lastX = Float.NaN
    private var dir = 0
    private var travel = 0f
    private val turns = ArrayDeque<Long>()

    fun reset() {
        lastX = Float.NaN
        dir = 0
        travel = 0f
        turns.clear()
    }

    fun feed(x: Float, t: Long): Boolean {
        if (lastX.isNaN()) { lastX = x; return false }
        val dx = x - lastX
        lastX = x
        if (abs(dx) < 0.5f) return false
        val d = if (dx > 0) 1 else -1
        if (dir == 0 || d == dir) {
            travel += abs(dx)
            dir = d
        } else {
            if (travel >= minTravel) turns.addLast(t)
            travel = abs(dx)
            dir = d
        }
        while (turns.isNotEmpty() && t - turns.first() > 800L) turns.removeFirst()
        if (turns.size >= 4) {
            turns.clear()
            return true
        }
        return false
    }
}
