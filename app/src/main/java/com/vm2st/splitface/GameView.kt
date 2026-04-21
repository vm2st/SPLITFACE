package com.vm2st.splitface

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.*

class GameView : SurfaceView, SurfaceHolder.Callback, Runnable {

    // Конструкторы для создания из кода и из XML
    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        holder.addCallback(this)
        isFocusable = true
    }

    companion object {
        private const val DISTORTION_FRAMES = 18
    }

    private var thread: Thread? = null
    private var isRunning = false

    // Размеры экрана
    private var width = 0
    private var height = 0
    private var midX = 0f

    // Мячи
    private lateinit var leftBall: Ball
    private lateinit var rightBall: Ball
    private var ballRadius = 0f

    // Счёт
    private var leftScore = 0
    private var rightScore = 0

    // Помехи (дисторшн)
    private var leftDistortion = 0
    private var rightDistortion = 0

    // Задержка удара
    private var hitCooldown = false

    // Всплывающие сообщения
    private data class Popup(val text: String, var x: Float, var y: Float, var life: Int, val color: Int)
    private val popups = mutableListOf<Popup>()

    // Пауза и сброс
    private var paused = false
    private var resetRequested = false

    // Кисти для рисования
    private val paintLine = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val paintDash = Paint().apply {
        color = Color.BLACK
        strokeWidth = 6f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    }
    private val paintText = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        typeface = Typeface.MONOSPACE
    }
    private val paintPopup = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintButton = Paint().apply {
        isAntiAlias = true
        color = Color.argb(200, 30, 30, 50)
        style = Paint.Style.FILL
    }
    private val paintButtonStroke = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val paintButtonText = Paint().apply {
        color = Color.argb(255, 220, 220, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    data class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float, var radius: Float)

    override fun surfaceCreated(holder: SurfaceHolder) {
        startGameLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        this.width = width
        this.height = height
        midX = width / 2f
        ballRadius = min(width, height) * 0.07f
        initBalls()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopGameLoop()
    }

    private fun startGameLoop() {
        if (thread == null || !isRunning) {
            isRunning = true
            thread = Thread(this)
            thread?.start()
        }
    }

    private fun stopGameLoop() {
        isRunning = false
        thread?.join()
        thread = null
    }

    override fun run() {
        while (isRunning) {
            if (!paused) {
                updatePhysics()
                updateDistortion()
                updatePopups()
            }
            draw()
            Thread.sleep(16)
        }
    }

    private fun initBalls() {
        val baseWidth = 1080f
        val scale = min(width, height) / baseWidth
        leftBall = Ball(
            x = width * 0.25f,
            y = height * 0.5f,
            vx = 4.2f * scale,
            vy = 3.0f * scale,
            radius = ballRadius
        )
        rightBall = Ball(
            x = width * 0.75f,
            y = height * 0.5f,
            vx = -3.8f * scale,
            vy = 3.2f * scale,
            radius = ballRadius
        )
    }

    private fun updatePhysics() {
        leftBall.x += leftBall.vx
        leftBall.y += leftBall.vy
        rightBall.x += rightBall.vx
        rightBall.y += rightBall.vy

        clampBall(leftBall)
        clampBall(rightBall)

        val dx = leftBall.x - rightBall.x
        val dy = leftBall.y - rightBall.y
        val dist = hypot(dx, dy)
        val minDist = leftBall.radius + rightBall.radius
        if (dist < minDist) {
            val angle = atan2(dy, dx)
            val overlap = minDist - dist
            val corrX = cos(angle) * overlap * 0.5f
            val corrY = sin(angle) * overlap * 0.5f
            leftBall.x += corrX
            leftBall.y += corrY
            rightBall.x -= corrX
            rightBall.y -= corrY

            val nx = dx / dist
            val ny = dy / dist
            val vrelx = leftBall.vx - rightBall.vx
            val vrely = leftBall.vy - rightBall.vy
            val dot = vrelx * nx + vrely * ny
            if (dot < 0) {
                val e = 0.7f
                val imp = (1 + e) * dot / 2
                leftBall.vx -= imp * nx
                leftBall.vy -= imp * ny
                rightBall.vx += imp * nx
                rightBall.vy += imp * ny
            }
        }
    }

    private fun clampBall(ball: Ball) {
        if (ball.y - ball.radius < 0) {
            ball.y = ball.radius
            ball.vy = -ball.vy
        }
        if (ball.y + ball.radius > height) {
            ball.y = height - ball.radius
            ball.vy = -ball.vy
        }
        if (ball.x - ball.radius < 0) {
            ball.x = ball.radius
            ball.vx = -ball.vx
        }
        if (ball.x + ball.radius > width) {
            ball.x = width - ball.radius
            ball.vx = -ball.vx
        }
    }

    private fun updateDistortion() {
        if (leftDistortion > 0) leftDistortion--
        if (rightDistortion > 0) rightDistortion--
    }

    private fun updatePopups() {
        val iterator = popups.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life -= 2
            if (p.life <= 0) iterator.remove()
        }
    }

    private fun addPopup(text: String, x: Float, y: Float, color: Int) {
        popups.add(Popup(text, x, y, 35, color))
    }

    fun resetGame() {
        leftScore = 0
        rightScore = 0
        leftDistortion = 0
        rightDistortion = 0
        initBalls()
        popups.clear()
        addPopup("НОВАЯ ИГРА", width / 2f, height * 0.1f, Color.WHITE)
        if (paused) {
            paused = false
            resetRequested = false
        }
    }

    private fun handleHit(touchX: Float, touchY: Float) {
        if (paused) return
        if (hitCooldown) return

        val hitLeft = hypot(touchX - leftBall.x, touchY - leftBall.y) <= leftBall.radius
        val hitRight = hypot(touchX - rightBall.x, touchY - rightBall.y) <= rightBall.radius
        if (!hitLeft && !hitRight) return

        val isLeftZone = touchX < midX
        val isLeftBall = hitLeft

        if ((isLeftZone && !isLeftBall) || (!isLeftZone && isLeftBall)) {
            if (isLeftZone) {
                leftScore = 0
                addPopup("ФОЛ! 0", touchX, touchY, Color.rgb(59, 130, 246))
            } else {
                rightScore = 0
                addPopup("ФОЛ! 0", touchX, touchY, Color.rgb(239, 68, 68))
            }
            val ball = if (isLeftBall) leftBall else rightBall
            val angle = atan2(touchY - ball.y, touchX - ball.x)
            ball.vx = cos(angle) * 6f
            ball.vy = sin(angle) * 6f
            hitCooldown = true
            Handler(Looper.getMainLooper()).postDelayed({ hitCooldown = false }, 150)
            return
        }

        val ball = if (isLeftBall) leftBall else rightBall
        if (isLeftZone) {
            leftScore++
            addPopup("+1", ball.x, ball.y - 20, Color.rgb(59, 130, 246))
        } else {
            rightScore++
            addPopup("+1", ball.x, ball.y - 20, Color.rgb(239, 68, 68))
        }

        val angle = atan2(touchY - ball.y, touchX - ball.x)
        var speed = hypot(ball.vx, ball.vy) + 2.2f
        if (speed > 10f) speed = 10f
        ball.vx = cos(angle) * speed
        ball.vy = sin(angle) * speed

        val onLine = abs(ball.x - midX) < 8f
        if (onLine) {
            if (isLeftZone) {
                rightDistortion = DISTORTION_FRAMES
                addPopup("ПОМЕХА", midX, height / 2f, Color.rgb(255, 170, 136))
            } else {
                leftDistortion = DISTORTION_FRAMES
                addPopup("ПОМЕХА", midX, height / 2f, Color.rgb(136, 221, 255))
            }
        }

        hitCooldown = true
        Handler(Looper.getMainLooper()).postDelayed({ hitCooldown = false }, 120)
    }

    private fun draw() {
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawRect(0f, 0f, midX, height.toFloat(), Paint().apply { color = Color.rgb(10, 42, 58) })
            canvas.drawRect(midX, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.rgb(58, 26, 26) })

            val random = java.util.Random()
            if (leftDistortion > 0) {
                val noisePaint = Paint().apply { color = Color.argb(170, 136, 221, 255) }
                repeat(30) {
                    val x1 = random.nextInt(midX.toInt()).toFloat()
                    val y1 = random.nextInt(height).toFloat()
                    canvas.drawRect(x1, y1, x1 + 8f, y1 + 5f, noisePaint)
                }
            }
            if (rightDistortion > 0) {
                val noisePaint = Paint().apply { color = Color.argb(170, 255, 170, 136) }
                repeat(30) {
                    val x1 = (midX.toInt() + random.nextInt((width - midX).toInt())).toFloat()
                    val y1 = random.nextInt(height).toFloat()
                    canvas.drawRect(x1, y1, x1 + 8f, y1 + 5f, noisePaint)
                }
            }

            canvas.drawLine(midX, 0f, midX, height.toFloat(), paintDash)
            canvas.drawLine(midX, 0f, midX, height.toFloat(), paintLine)

            drawBall(canvas, leftBall, Color.rgb(59, 130, 246), Color.rgb(30, 58, 138))
            drawBall(canvas, rightBall, Color.rgb(239, 68, 68), Color.rgb(127, 26, 26))

            val safeTop = 50f      // отступ сверху
            val safeLeft = 50f     // отступ слева
            val safeRight = 80f    // отступ справа (чтобы текст не прижимался к краю)

            paintText.textSize = min(width * 0.08f, 70f)
            paintText.color = Color.rgb(59, 130, 246)
            canvas.drawText(leftScore.toString(), safeLeft, safeTop, paintText)
            paintText.color = Color.rgb(239, 68, 68)
            canvas.drawText(rightScore.toString(), width - safeRight, safeTop, paintText)

            val buttonSize = min(width, height) * 0.1f
            val buttonY = height * 0.12f
            canvas.drawCircle(width / 2f, buttonY + buttonSize / 2, buttonSize / 2, paintButton)
            canvas.drawCircle(width / 2f, buttonY + buttonSize / 2, buttonSize / 2, paintButtonStroke)
            paintButtonText.textSize = buttonSize * 0.6f
            canvas.drawText("↻", width / 2f, buttonY + buttonSize * 0.72f, paintButtonText)

            paintPopup.textSize = min(width * 0.05f, 32f)
            for (p in popups) {
                paintPopup.color = p.color
                canvas.drawText(p.text, p.x, p.y - p.life / 1.5f, paintPopup)
            }

            if (paused) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.argb(80, 0, 0, 0) })
                val msgPaint = Paint().apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    textSize = min(width * 0.05f, 28f)
                    typeface = Typeface.DEFAULT_BOLD
                }
                val bgPaint = Paint().apply {
                    color = Color.argb(200, 0, 0, 0)
                    style = Paint.Style.FILL
                }
                val pauseMessage = "Нажмите ещё раз, чтобы сбросить"
                val msgWidth = msgPaint.measureText(pauseMessage)
                canvas.drawRect(width / 2f - msgWidth / 2 - 30, height / 2f - 40, width / 2f + msgWidth / 2 + 30, height / 2f + 40, bgPaint)
                canvas.drawText(pauseMessage, width / 2f, height / 2f + 10, msgPaint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawBall(canvas: Canvas, ball: Ball, colorStart: Int, colorEnd: Int) {
        val gradient = RadialGradient(ball.x - 6, ball.y - 6, 5f, colorStart, colorEnd, Shader.TileMode.CLAMP)
        val paint = Paint().apply {
            shader = gradient
            isAntiAlias = true
        }
        canvas.drawCircle(ball.x, ball.y, ball.radius, paint)
        paint.shader = null
        paint.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(ball.x - 3, ball.y - 3, ball.radius * 0.25f, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        performClick()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                for (i in 0 until event.pointerCount) {
                    val x = event.getX(i)
                    val y = event.getY(i)

                    val buttonSize = min(width, height) * 0.1f
                    val buttonY = height * 0.12f
                    val inButton = hypot(x - width / 2f, y - (buttonY + buttonSize / 2)) <= buttonSize / 2

                    if (inButton) {
                        if (!paused) {
                            paused = true
                            resetRequested = true
                        } else {
                            resetGame()
                            paused = false
                            resetRequested = false
                        }
                        return true
                    }
                }

                if (paused) {
                    paused = false
                    resetRequested = false
                    return true
                }

                for (i in 0 until event.pointerCount) {
                    handleHit(event.getX(i), event.getY(i))
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}