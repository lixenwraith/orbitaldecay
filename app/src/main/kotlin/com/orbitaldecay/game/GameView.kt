package com.orbitaldecay.game

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val RING_COUNT = 9
        const val BUTTON_RADIUS_RATIO = 0.7f
        const val DRAG_SENSITIVITY = 0.5f // degrees per pixel
        const val SHUFFLE_MOVE_COUNT = 25
        const val SHUFFLE_MOVE_DELAY_MS = 80L
        const val SHUFFLE_MOVE_DURATION_MS = 60L
        const val WIN_TOLERANCE_DEGREES = 3f
    }

    private val gameState = GameState()
    private var shuffleSequence: ShuffleSequence? = null

    private val backgroundPaint = Paint().apply {
        color = 0xFF1A1A1A.toInt()
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val centerCirclePaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFF1A1A1A.toInt()
        isAntiAlias = true
    }

    private val buttonPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val buttonTextPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val winTextPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
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
    private var ringWidth = 0f
    private var buttonRadius = 0f
    private val radii = FloatArray(9)

    private var buttonAlpha = 0f
    private var isButtonPressed = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastDragX = 0f
    private var isDragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f

        val screenMin = min(w, h).toFloat()

        // Solve: outerEdge + margin = screenMin/2
        // Ring 8 outer edge = radii[8] + ringWidth/2 = 9*ringWidth + ringWidth/2 = 9.5*ringWidth
        // Margin = 2 * ringWidth
        // 9.5*ringWidth + 2*ringWidth = screenMin/2
        // 11.5*ringWidth = screenMin/2
        ringWidth = screenMin / 23f

        for (i in 0..8) {
            radii[i] = ringWidth * (i + 1)
        }

        buttonRadius = radii[0] * BUTTON_RADIUS_RATIO
        ringPaint.strokeWidth = ringWidth

        // Start game after geometry is ready (first layout only)
        if (oldw == 0 && oldh == 0) {
            startGame()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Skip if layout not complete
        if (ringWidth == 0f) return

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Draw rings 1-8 with notches and optional shadows
        for (i in 1..8) {
            val elevationLevel = when {
                gameState.selectedRing == i -> 2 // Selected ring
                gameState.selectedRing > 0 && (i == gameState.selectedRing - 1 || i == gameState.selectedRing + 1) -> 1 // Neighbor
                else -> 0 // No elevation
            }
            drawRingWithElevation(canvas, i, elevationLevel)
        }

        // Draw center circle (ring 0) - solid fill, no notch
        canvas.drawCircle(centerX, centerY, radii[0], centerCirclePaint)

        // Draw button if enabled or fading in
        if (gameState.isButtonEnabled || buttonAlpha > 0f) {
            drawButton(canvas, isButtonPressed)
        }

        // Draw WIN text if game is won
        if (gameState.isWon) {
            winTextPaint.textSize = 80f * resources.displayMetrics.density
            canvas.drawText("SOLVED", centerX, centerY - 30f * resources.displayMetrics.density, winTextPaint)
        }
    }

    private fun drawRingWithElevation(canvas: Canvas, index: Int, elevationLevel: Int) {
        val radius = radii[index]

        // Calculate stroke width with elevation
        val extraStroke = when (elevationLevel) {
            2 -> 4f * resources.displayMetrics.density // Selected
            1 -> 2f * resources.displayMetrics.density // Neighbor
            else -> 0f
        }

        val strokeWidth = ringWidth + extraStroke
        ringPaint.strokeWidth = strokeWidth

        // Set shadow based on elevation
        if (elevationLevel > 0) {
            val shadowRadius = if (elevationLevel == 2) 8f else 4f
            val shadowOffset = if (elevationLevel == 2) 4f else 2f
            val shadowColor = if (elevationLevel == 2) 0x80000000.toInt() else 0x40000000.toInt()
            ringPaint.setShadowLayer(
                shadowRadius * resources.displayMetrics.density,
                shadowOffset * resources.displayMetrics.density,
                shadowOffset * resources.displayMetrics.density,
                shadowColor
            )
        } else {
            ringPaint.clearShadowLayer()
        }

        // Set color based on ring index
        ringPaint.color = when (index) {
            8 -> 0xFFB0B0B0.toInt() // Light gray for outermost ring
            else -> rainbowColors[index - 1] // Rainbow colors for rings 1-7
        }

        // Draw arc with notch (15° gap at current angle)
        val bounds = RectF(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )
        val notchAngle = gameState.angles[index]
        val notchSize = 15f
        val startAngle = notchAngle + notchSize / 2f - 90f // -90 to start from top
        val sweepAngle = 360f - notchSize

        canvas.drawArc(bounds, startAngle, sweepAngle, false, ringPaint)

        // Clear shadow for next ring
        ringPaint.clearShadowLayer()
    }

    private fun drawButton(canvas: Canvas, pressed: Boolean) {
        val currentButtonRadius = if (pressed) buttonRadius * 0.95f else buttonRadius

        // Create 3D gradient effect
        val gradient = if (pressed) {
            // Inverted gradient when pressed (darker top-left, lighter bottom-right)
            RadialGradient(
                centerX - currentButtonRadius * 0.3f,
                centerY - currentButtonRadius * 0.3f,
                currentButtonRadius * 1.5f,
                intArrayOf(0xFF202020.toInt(), 0xFF404040.toInt(), 0xFF606060.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            // Normal gradient (lighter top-left, darker bottom-right)
            RadialGradient(
                centerX - currentButtonRadius * 0.3f,
                centerY - currentButtonRadius * 0.3f,
                currentButtonRadius * 1.5f,
                intArrayOf(0xFF606060.toInt(), 0xFF404040.toInt(), 0xFF202020.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        buttonPaint.shader = gradient
        buttonPaint.alpha = (buttonAlpha * 255).toInt()

        canvas.drawCircle(centerX, centerY, currentButtonRadius, buttonPaint)

        // Draw ring number if selected
        if (gameState.selectedRing > 0 && !gameState.isWon) {
            buttonTextPaint.textSize = currentButtonRadius * 0.8f
            buttonTextPaint.color = rainbowColors[gameState.selectedRing - 1]
            buttonTextPaint.alpha = (buttonAlpha * 255).toInt()

            // Center text vertically
            val textY = centerY - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
            canvas.drawText(gameState.selectedRing.toString(), centerX, textY, buttonTextPaint)
        } else if (gameState.isWon) {
            // Draw restart symbol
            buttonTextPaint.textSize = currentButtonRadius * 1.2f
            buttonTextPaint.color = 0xFFFFFFFF.toInt()
            buttonTextPaint.alpha = (buttonAlpha * 255).toInt()

            val textY = centerY - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
            canvas.drawText("↻", centerX, textY, buttonTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gameState.isButtonEnabled || gameState.isShuffling) {
            return super.onTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isTouchingButton(event.x, event.y)) {
                    isButtonPressed = true
                    touchStartX = event.x
                    touchStartY = event.y
                    lastDragX = event.x
                    isDragging = false
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isButtonPressed) {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    val distance = sqrt(dx.pow(2) + dy.pow(2))

                    // If moved more than 10dp, it's a drag
                    if (distance > 10f * resources.displayMetrics.density) {
                        isDragging = true
                    }

                    // Handle horizontal drag for rotation
                    if (isDragging && gameState.selectedRing > 0) {
                        val deltaX = event.x - lastDragX
                        val rotationAngle = deltaX * DRAG_SENSITIVITY
                        gameState.applyMove(gameState.selectedRing, rotationAngle)
                        lastDragX = event.x
                        invalidate()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isButtonPressed) {
                    if (!isDragging) {
                        // It's a tap
                        handleButtonTap()
                    }
                    isButtonPressed = false
                    isDragging = false
                    invalidate()
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun isTouchingButton(x: Float, y: Float): Boolean {
        val dx = x - centerX
        val dy = y - centerY
        val distance = sqrt(dx.pow(2) + dy.pow(2))
        return distance <= buttonRadius
    }

    private fun handleButtonTap() {
        if (gameState.isWon) {
            // Restart game
            startGame()
        } else {
            // Cycle to next ring (1→2→3→...→8→1)
            gameState.selectedRing = if (gameState.selectedRing == 0) {
                1
            } else if (gameState.selectedRing == 8) {
                1
            } else {
                gameState.selectedRing + 1
            }
            invalidate()
        }
    }

    private fun startGame() {
        gameState.reset()
        buttonAlpha = 0f

        // Create shuffle sequence
        shuffleSequence = ShuffleSequence()
        val moves = shuffleSequence!!.generate(SHUFFLE_MOVE_COUNT)

        // Start shuffle animation
        gameState.isShuffling = true
        animateShuffle(moves, 0)
    }

    private fun animateShuffle(moves: List<Pair<Int, Float>>, index: Int) {
        if (index >= moves.size) {
            gameState.isShuffling = false
            gameState.isButtonEnabled = true

            val fadeAnimator = ValueAnimator.ofFloat(0f, 1f)
            fadeAnimator.duration = 300
            fadeAnimator.addUpdateListener { animator ->
                buttonAlpha = animator.animatedValue as Float
                invalidate()
            }
            fadeAnimator.start()
            return
        }

        val (ring, angle) = moves[index]

        val animator = ValueAnimator.ofFloat(0f, angle)
        animator.duration = SHUFFLE_MOVE_DURATION_MS
        animator.interpolator = LinearInterpolator()

        var lastValue = 0f
        animator.addUpdateListener { valueAnimator ->
            val currentValue = valueAnimator.animatedValue as Float
            val deltaAngle = currentValue - lastValue
            lastValue = currentValue

            gameState.angles[ring] = gameState.normalizeAngle(gameState.angles[ring] + deltaAngle)
            if (ring > 1) {
                gameState.angles[ring - 1] = gameState.normalizeAngle(gameState.angles[ring - 1] + deltaAngle * 0.5f)
            }
            if (ring < 8) {
                gameState.angles[ring + 1] = gameState.normalizeAngle(gameState.angles[ring + 1] + deltaAngle * 0.5f)
            }
            invalidate()
        }

        // Schedule next move AFTER this animation ends
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                postDelayed({ animateShuffle(moves, index + 1) }, SHUFFLE_MOVE_DELAY_MS - SHUFFLE_MOVE_DURATION_MS)
            }
        })

        animator.start()
    }
}
