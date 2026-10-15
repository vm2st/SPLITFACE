package com.vm2st.splitface

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Pure game rules. All access is confined to the UI thread by GameView. */
internal class GameEngine {
    data class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float, var radius: Float)
    data class BallState(val x: Float, val y: Float, val vx: Float, val vy: Float)
    data class State(
        val width: Float, val height: Float,
        val left: BallState, val right: BallState,
        val leftScore: Int, val rightScore: Int,
        val paused: Boolean, val resetPending: Boolean,
        val leftCooldown: Float, val rightCooldown: Float,
        val leftDistortion: Float, val rightDistortion: Float
    )
    data class Hit(val leftPlayer: Boolean, val foul: Boolean, val distortion: Boolean)

    var width = 0f; private set
    var height = 0f; private set
    val ready get() = width > 0f && height > 0f
    var leftBall = Ball(0f, 0f, 0f, 0f, 0f); private set
    var rightBall = Ball(0f, 0f, 0f, 0f, 0f); private set
    var leftScore = 0; private set
    var rightScore = 0; private set
    var paused = false; private set
    var resetPending = false; private set
    var leftDistortion = 0f; private set
    var rightDistortion = 0f; private set
    private var leftCooldown = 0f
    private var rightCooldown = 0f
    private val scale get() = min(width, height) / 1080f
    private val radius get() = min(min(width, height) * .07f, min(width / 4f, height / 2f) * .9f)

    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        val oldWidth = width
        val oldHeight = height
        width = newWidth.toFloat()
        height = newHeight.toFloat()
        if (oldWidth <= 0 || oldHeight <= 0) {
            initBalls()
        } else {
            val velocityScale = min(width, height) / min(oldWidth, oldHeight)
            for (ball in listOf(leftBall, rightBall)) {
                ball.x *= width / oldWidth
                ball.y *= height / oldHeight
                ball.vx *= velocityScale
                ball.vy *= velocityScale
                ball.radius = radius
                clamp(ball)
            }
        }
    }

    private fun initBalls() {
        leftBall = Ball(width * .25f, height * .5f, 252f * scale, 180f * scale, radius)
        rightBall = Ball(width * .75f, height * .5f, -228f * scale, 192f * scale, radius)
    }

    fun reset() {
        leftScore = 0; rightScore = 0
        leftDistortion = 0f; rightDistortion = 0f
        leftCooldown = 0f; rightCooldown = 0f
        paused = false; resetPending = false
        if (ready) initBalls()
    }

    fun togglePause() {
        paused = !paused
        resetPending = false
    }

    /** First activation pauses; only a second activation confirms the destructive reset. */
    fun requestReset(): Boolean {
        if (resetPending) {
            reset()
            return true
        }
        resetPending = true
        paused = true
        return false
    }

    fun resume() { paused = false; resetPending = false }

    fun advance(seconds: Float) {
        if (!ready || paused || !seconds.isFinite() || seconds <= 0f) return
        // Bounded catch-up prevents long background gaps and collision tunnelling.
        val dt = min(seconds, .25f)
        val steps = ceil(dt / (1f / 120f)).toInt().coerceAtLeast(1)
        repeat(steps) { step(dt / steps) }
    }

    private fun step(dt: Float) {
        leftCooldown = (leftCooldown - dt).coerceAtLeast(0f)
        rightCooldown = (rightCooldown - dt).coerceAtLeast(0f)
        leftDistortion = (leftDistortion - dt).coerceAtLeast(0f)
        rightDistortion = (rightDistortion - dt).coerceAtLeast(0f)
        for (ball in listOf(leftBall, rightBall)) {
            ball.x += ball.vx * dt
            ball.y += ball.vy * dt
            clamp(ball)
        }
        val dx = leftBall.x - rightBall.x
        val dy = leftBall.y - rightBall.y
        val distance = hypot(dx, dy)
        val minimum = leftBall.radius + rightBall.radius
        if (distance < minimum) {
            // Coincident centres need an explicit normal, not division by zero.
            val nx = if (distance > .00001f) dx / distance else -1f
            val ny = if (distance > .00001f) dy / distance else 0f
            val correction = (minimum - distance) * .5f
            leftBall.x += nx * correction; leftBall.y += ny * correction
            rightBall.x -= nx * correction; rightBall.y -= ny * correction
            val closingSpeed = (leftBall.vx - rightBall.vx) * nx + (leftBall.vy - rightBall.vy) * ny
            if (closingSpeed < 0f) {
                val impulse = 1.7f * closingSpeed / 2f
                leftBall.vx -= impulse * nx; leftBall.vy -= impulse * ny
                rightBall.vx += impulse * nx; rightBall.vy += impulse * ny
            }
            clamp(leftBall)
            clamp(rightBall)
        }
    }

    private fun clamp(ball: Ball) {
        if (ball.x < ball.radius) {
            ball.x = ball.radius; ball.vx = abs(ball.vx)
        } else if (ball.x > width - ball.radius) {
            ball.x = width - ball.radius; ball.vx = -abs(ball.vx)
        }
        if (ball.y < ball.radius) {
            ball.y = ball.radius; ball.vy = abs(ball.vy)
        } else if (ball.y > height - ball.radius) {
            ball.y = height - ball.radius; ball.vy = -abs(ball.vy)
        }
    }

    fun hit(x: Float, y: Float): Hit? {
        if (!ready || paused || !x.isFinite() || !y.isFinite() || x < 0f || x > width || y < 0f || y > height) return null
        val leftPlayer = x < width / 2f
        if ((if (leftPlayer) leftCooldown else rightCooldown) > .00001f) return null
        val leftDistance = hypot(x - leftBall.x, y - leftBall.y)
        val rightDistance = hypot(x - rightBall.x, y - rightBall.y)
        val hitsLeft = leftDistance <= leftBall.radius
        val hitsRight = rightDistance <= rightBall.radius
        if (!hitsLeft && !hitsRight) return null
        val isLeftBall = if (hitsLeft && hitsRight) {
            if (leftDistance == rightDistance) leftPlayer else leftDistance < rightDistance
        } else hitsLeft
        val ball = if (isLeftBall) leftBall else rightBall
        val foul = leftPlayer != isLeftBall
        if (leftPlayer) {
            leftScore = if (foul) 0 else if (leftScore < Int.MAX_VALUE) leftScore + 1 else leftScore
            leftCooldown = if (foul) .15f else .12f
        } else {
            rightScore = if (foul) 0 else if (rightScore < Int.MAX_VALUE) rightScore + 1 else rightScore
            rightCooldown = if (foul) .15f else .12f
        }
        val dx = x - ball.x
        val dy = y - ball.y
        // A centre hit retains the direction instead of arbitrarily aiming to the right.
        val angle = if (hypot(dx, dy) > .00001f) atan2(dy, dx) else atan2(ball.vy, ball.vx)
        val speed = if (foul) 360f * scale else min(hypot(ball.vx, ball.vy) + 132f * scale, 600f * scale)
        ball.vx = cos(angle) * speed
        ball.vy = sin(angle) * speed
        val distortion = !foul && abs(ball.x - width / 2f) < 8f * scale
        if (distortion) {
            if (leftPlayer) rightDistortion = .3f else leftDistortion = .3f
        }
        return Hit(leftPlayer, foul, distortion)
    }

    fun snapshot(): State = State(width, height,
        BallState(leftBall.x, leftBall.y, leftBall.vx, leftBall.vy),
        BallState(rightBall.x, rightBall.y, rightBall.vx, rightBall.vy),
        leftScore, rightScore, paused, resetPending, leftCooldown, rightCooldown, leftDistortion, rightDistortion)

    fun restore(state: State): Boolean {
        if (!state.width.isFinite() || !state.height.isFinite() || state.width < 1f || state.height < 1f ||
            state.width > 100_000f || state.height > 100_000f || state.leftScore < 0 || state.rightScore < 0) return false
        val values = listOf(state.left.x, state.left.y, state.left.vx, state.left.vy,
            state.right.x, state.right.y, state.right.vx, state.right.vy,
            state.leftCooldown, state.rightCooldown, state.leftDistortion, state.rightDistortion)
        if (values.any { !it.isFinite() }) return false
        width = state.width; height = state.height
        fun ball(s: BallState): Ball {
            val speed = hypot(s.vx, s.vy)
            val factor = if (speed > 600f * scale) 600f * scale / speed else 1f
            return Ball(s.x, s.y, s.vx * factor, s.vy * factor, radius).also { clamp(it) }
        }
        leftBall = ball(state.left); rightBall = ball(state.right)
        leftScore = state.leftScore; rightScore = state.rightScore
        resetPending = state.resetPending
        paused = state.paused || resetPending
        leftCooldown = state.leftCooldown.coerceIn(0f, .15f)
        rightCooldown = state.rightCooldown.coerceIn(0f, .15f)
        leftDistortion = state.leftDistortion.coerceIn(0f, .3f)
        rightDistortion = state.rightDistortion.coerceIn(0f, .3f)
        return true
    }
}
