package com.orbitaldecay.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gameState = GameState()

    private val backgroundPaint = Paint().apply {
        color = 0xFF1A1A1A.toInt()
        style = Paint.Style.FILL
    }

    private val circlePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f * resources.displayMetrics.density
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val centerCirclePaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFF1A1A1A.toInt()
        isAntiAlias = true
    }

    private val winTextPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 80f * resources.displayMetrics.density
        isAntiAlias = true
    }

    private val rainbowColors = listOf(
        0xFFFF0000.toInt(), // Red
        0xFFFF7F00.toInt(), // Orange
        0xFFFFFF00.toInt(), // Yellow
        0xFF00FF00.toInt(), // Green
        0xFF00FFFF.toInt(), // Cyan
        0xFF0000FF.toInt(), // Blue
        0xFF8B00FF.toInt()  // Violet
    )

    private var centerX = 0f
    private var centerY = 0f
    private var screenMin = 0f
    private val radii = FloatArray(9)

    private var touchedCircle = -1
    private var lastTouchAngle = 0f

    init {
        startGame()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        screenMin = min(w, h).toFloat()

        // Calculate radii for all 9 circles
        for (i in 0..8) {
            radii[i] = screenMin / 18f * (i + 1)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Draw circles 1-8 with notches
        for (i in 1..8) {
            val radius = radii[i]
            val bounds = RectF(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
            )

            // Set color based on circle index
            circlePaint.color = when (i) {
                8 -> 0xFFB0B0B0.toInt() // Light gray for outermost circle
                else -> rainbowColors[i - 1] // Rainbow colors for circles 1-7
            }

            // Draw arc with notch (15° gap at current angle)
            val notchAngle = gameState.angles[i]
            val notchSize = 15f

            // Draw the arc excluding the notch
            val startAngle = notchAngle + notchSize / 2f - 90f // -90 to start from top
            val sweepAngle = 360f - notchSize

            canvas.drawArc(bounds, startAngle, sweepAngle, false, circlePaint)
        }

        // Draw center circle (circle 0) - solid fill, no notch
        canvas.drawCircle(centerX, centerY, radii[0], centerCirclePaint)

        // Draw WIN text if game is won
        if (gameState.isWon) {
            canvas.drawText("WIN", centerX, centerY + 30f * resources.displayMetrics.density, winTextPaint)
            canvas.drawText("Tap to restart", centerX, centerY + 80f * resources.displayMetrics.density,
                winTextPaint.apply { textSize = 40f * resources.displayMetrics.density })
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (gameState.isWon) {
                    // Restart game on tap when won
                    startGame()
                    return true
                }

                val touchedCircleIndex = getTouchedCircle(event.x, event.y)
                if (touchedCircleIndex in 1..8) {
                    touchedCircle = touchedCircleIndex
                    lastTouchAngle = getAngleFromCenter(event.x, event.y)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchedCircle in 1..8 && !gameState.isShuffling && !gameState.isWon) {
                    val currentAngle = getAngleFromCenter(event.x, event.y)
                    val deltaAngle = currentAngle - lastTouchAngle

                    gameState.rotateCircle(touchedCircle, deltaAngle)
                    lastTouchAngle = currentAngle

                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchedCircle = -1
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun getTouchedCircle(x: Float, y: Float): Int {
        val distance = getDistanceFromCenter(x, y)

        // Check from outermost to innermost
        for (i in 8 downTo 1) {
            val outerRadius = radii[i]
            val innerRadius = if (i > 0) radii[i - 1] else 0f

            if (distance <= outerRadius && distance > innerRadius) {
                return i
            }
        }

        return -1 // No circle touched
    }

    private fun getDistanceFromCenter(x: Float, y: Float): Float {
        val dx = x - centerX
        val dy = y - centerY
        return sqrt(dx.pow(2) + dy.pow(2))
    }

    private fun getAngleFromCenter(x: Float, y: Float): Float {
        val dx = x - centerX
        val dy = y - centerY
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0) angle += 360f
        return angle
    }

    private fun startGame() {
        gameState.reset()
        gameState.shuffle(
            onStep = { invalidate() },
            onComplete = { invalidate() }
        )
    }
}
