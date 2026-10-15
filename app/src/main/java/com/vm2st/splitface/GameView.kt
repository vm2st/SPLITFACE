package com.vm2st.splitface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.random.Random

/** Rendering, input and physics deliberately share the Android main thread. */
class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val game = GameEngine()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    private val popups = ArrayList<Popup>()
    private data class Popup(val text: String, val x: Float, val y: Float, val color: Int, var life: Float = .3f)
    private var hostActive = false
    private var attached = false
    private var scheduled = false
    private var lastFrameNanos = 0L
    var onStateChanged: (() -> Unit)? = null
    val isPaused get() = game.paused
    val isResetPending get() = game.resetPending

    private val frame = object : Runnable {
        override fun run() {
            scheduled = false
            if (!canAnimate()) return
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) {
                val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, .25f)
                game.advance(dt)
                val iterator = popups.iterator()
                while (iterator.hasNext()) {
                    val popup = iterator.next()
                    popup.life -= dt
                    if (popup.life <= 0f) iterator.remove()
                }
            }
            lastFrameNanos = now
            invalidate()
            scheduleFrame()
        }
    }

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    private fun canAnimate() = attached && hostActive && windowVisibility == VISIBLE &&
        isShown && hasWindowFocus() && width > 0 && height > 0 && game.ready && !game.paused

    private fun scheduleFrame() {
        if (!scheduled && canAnimate()) {
            scheduled = true
            postOnAnimation(frame)
        }
    }

    private fun stopFrames() {
        removeCallbacks(frame)
        scheduled = false
        lastFrameNanos = 0L
    }

    fun setHostActive(active: Boolean) {
        hostActive = active
        stopFrames()
        if (active) scheduleFrame()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopFrames()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        stopFrames()
        if (hasWindowFocus) scheduleFrame()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // View construction may invoke this override before Kotlin fields are initialized.
        if (attached) {
            stopFrames()
            if (visibility == VISIBLE) scheduleFrame()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stopFrames()
        game.resize(w, h)
        popups.clear()
        scheduleFrame()
    }

    fun togglePause() { game.togglePause(); stateChanged() }

    fun requestReset() {
        if (game.requestReset()) {
            popups.clear()
            addPopup(context.getString(R.string.new_game), width / 2f, height * .3f, Color.WHITE)
        }
        stateChanged()
    }

    private fun stateChanged() {
        stopFrames()
        updateDescription()
        onStateChanged?.invoke()
        invalidate()
        scheduleFrame()
    }

    private fun updateDescription() {
        contentDescription = context.getString(R.string.game_description, game.leftScore, game.rightScore)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!hostActive || !hasWindowFocus() || !game.ready) return true
                if (game.paused) {
                    // A second finger in the same gesture must not resume and then score.
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        game.resume()
                        stateChanged()
                        performClick()
                    }
                    return true
                }
                // Only the newly pressed pointer, never every held finger.
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val hit = game.hit(x, y)
                if (hit != null) {
                    val color = if (hit.leftPlayer) BLUE else RED
                    addPopup(context.getString(if (hit.foul) R.string.foul else R.string.point), x, y, color)
                    if (hit.distortion) addPopup(context.getString(R.string.distortion), width / 2f, height / 2f, Color.WHITE)
                    updateDescription()
                    invalidate()
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { /* No delayed or held-pointer actions to cancel. */ }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun addPopup(text: String, x: Float, y: Float, color: Int) {
        if (popups.size >= 32) popups.removeAt(0)
        popups.add(Popup(text, x, y, color))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!game.ready || width <= 0 || height <= 0) return
        val mid = width / 2f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(10, 42, 58)
        canvas.drawRect(0f, 0f, mid, height.toFloat(), paint)
        paint.color = Color.rgb(58, 26, 26)
        canvas.drawRect(mid, 0f, width.toFloat(), height.toFloat(), paint)
        drawDistortion(canvas, 0f, mid, game.leftDistortion, Color.argb(170, 136, 221, 255))
        drawDistortion(canvas, mid, width.toFloat(), game.rightDistortion, Color.argb(170, 255, 170, 136))
        paint.color = Color.WHITE
        paint.strokeWidth = resources.displayMetrics.density * 2f
        paint.pathEffect = dash
        canvas.drawLine(mid, 0f, mid, height.toFloat(), paint)
        paint.pathEffect = null
        drawBall(canvas, game.leftBall, BLUE)
        drawBall(canvas, game.rightBall, RED)
        val textSize = min(height * .12f, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f, resources.displayMetrics))
        paint.textSize = textSize
        paint.typeface = Typeface.MONOSPACE
        paint.color = BLUE
        paint.textAlign = Paint.Align.LEFT
        val margin = min(width * .025f, height * .05f)
        val baseline = margin - paint.fontMetrics.top
        val scoreWidth = (mid - 2f * margin).coerceAtLeast(1f)
        val leftText = game.leftScore.toString()
        val leftMeasured = paint.measureText(leftText)
        if (leftMeasured > scoreWidth) paint.textSize *= scoreWidth / leftMeasured
        canvas.drawText(leftText, margin, baseline, paint)
        paint.textAlign = Paint.Align.RIGHT
        paint.color = RED
        paint.textSize = textSize
        val rightText = game.rightScore.toString()
        val rightMeasured = paint.measureText(rightText)
        if (rightMeasured > scoreWidth) paint.textSize *= scoreWidth / rightMeasured
        canvas.drawText(rightText, width - margin, baseline, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = min(height * .055f, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics))
        for (popup in popups) {
            paint.color = popup.color
            paint.alpha = (255 * (popup.life / .3f).coerceIn(0f, 1f)).toInt()
            val halfText = paint.measureText(popup.text) / 2
            val x = if (halfText < mid) popup.x.coerceIn(halfText, width - halfText) else mid
            val y = (popup.y - (.3f - popup.life) * height * .15f)
                .coerceIn(-paint.fontMetrics.top, height.toFloat())
            canvas.drawText(popup.text, x, y, paint)
        }
        paint.alpha = 255
        if (game.paused) {
            paint.color = Color.argb(190, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.WHITE
            val message = context.getString(if (game.resetPending) R.string.reset_hint else R.string.pause_hint)
            val available = width * .9f
            val measured = paint.measureText(message)
            if (measured > available) paint.textSize *= available / measured
            canvas.drawText(message, mid, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    private fun drawDistortion(canvas: Canvas, from: Float, to: Float, remaining: Float, color: Int) {
        if (remaining <= 0f || to <= from) return
        paint.color = color
        repeat(30) {
            val x = from + Random.nextFloat() * (to - from)
            val y = Random.nextFloat() * height
            canvas.drawRect(x, y, min(x + 8f, to), min(y + 5f, height.toFloat()), paint)
        }
    }

    private fun drawBall(canvas: Canvas, ball: GameEngine.Ball, color: Int) {
        paint.color = color
        canvas.drawCircle(ball.x, ball.y, ball.radius, paint)
        paint.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(ball.x - ball.radius * .25f, ball.y - ball.radius * .25f, ball.radius * .25f, paint)
    }

    fun saveGameState(): Bundle {
        val s = game.snapshot()
        return Bundle().apply {
            putInt("version", 1)
            putFloatArray("balls", floatArrayOf(s.width, s.height,
                s.left.x, s.left.y, s.left.vx, s.left.vy, s.right.x, s.right.y, s.right.vx, s.right.vy,
                s.leftCooldown, s.rightCooldown, s.leftDistortion, s.rightDistortion))
            putInt("leftScore", s.leftScore); putInt("rightScore", s.rightScore)
            putBoolean("paused", s.paused); putBoolean("resetPending", s.resetPending)
        }
    }

    fun restoreGameState(bundle: Bundle) {
        if (bundle.getInt("version") != 1) return
        val b = bundle.getFloatArray("balls") ?: return
        if (b.size != 14) return
        val restored = game.restore(GameEngine.State(b[0], b[1],
            GameEngine.BallState(b[2], b[3], b[4], b[5]), GameEngine.BallState(b[6], b[7], b[8], b[9]),
            bundle.getInt("leftScore"), bundle.getInt("rightScore"), bundle.getBoolean("paused"),
            bundle.getBoolean("resetPending"), b[10], b[11], b[12], b[13]))
        if (restored) {
            if (width > 0 && height > 0) game.resize(width, height)
            popups.clear()
            stateChanged()
        }
    }

    companion object {
        private val BLUE = Color.rgb(59, 130, 246)
        private val RED = Color.rgb(239, 68, 68)
    }
}
