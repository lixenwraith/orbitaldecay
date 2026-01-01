package com.orbitaldecay.game

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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

    init {
        // Required for setShadowLayer to work
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

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

    // Touch handling for full-screen control
    private var trackpadTouchStartX = 0f
    private var trackpadTouchStartY = 0f
    private var trackpadIsDragging = false
    private var lastTrackpadDragX = 0f
    private var lastTrackpadDragY = 0f
    private var ringSelectionAccumulator = 0f  // For smooth ring switching

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w == 0 || h == 0) return  // Guard against zero dimensions

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

        // Draw rings in correct z-order (lowest elevation first, selected ring last)
        // 1. First pass: draw all non-elevated rings (elevation 0)
        // 2. Second pass: draw neighbor rings (elevation 1)
        // 3. Third pass: draw selected ring (elevation 2)

        // Collect rings by elevation level
        val elevation0 = mutableListOf<Int>()
        val elevation1 = mutableListOf<Int>()
        var elevation2: Int? = null

        for (i in 1..8) {
            val elevationLevel = when {
                gameState.selectedRing == i -> 2
                gameState.selectedRing > 0 && (i == gameState.selectedRing - 1 || i == gameState.selectedRing + 1) -> 1
                else -> 0
            }
            when (elevationLevel) {
                0 -> elevation0.add(i)
                1 -> elevation1.add(i)
                2 -> elevation2 = i
            }
        }

        // Draw in z-order: flat rings first, then neighbors, then selected
        for (i in elevation0) {
            drawRingWithElevation(canvas, i, 0)
        }
        for (i in elevation1) {
            drawRingWithElevation(canvas, i, 1)
        }
        elevation2?.let {
            drawRingWithElevation(canvas, it, 2)
        }

        // Draw center circle (ring 0) - solid fill, no notch
        canvas.drawCircle(centerX, centerY, radii[0], centerCirclePaint)

        // Draw ring number indicator in center (not a button)
        if (gameState.isButtonEnabled) {
            drawRingIndicator(canvas)
        }

        // Draw WIN text if game is won
        if (gameState.isWon) {
            winTextPaint.textSize = 60f * resources.displayMetrics.density
            canvas.drawText("SOLVED", centerX, centerY - radii[0] - 20f * resources.displayMetrics.density, winTextPaint)
            winTextPaint.textSize = 24f * resources.displayMetrics.density
            canvas.drawText("Tap to restart", centerX, centerY + radii[0] + 40f * resources.displayMetrics.density, winTextPaint)
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
        // Inner rings need larger angular gaps to be visible
        // Ring 1: 75° gap, Ring 8: 22.5° gap
        val notchSize = when (index) {
            1 -> 75f
            2 -> 60f
            3 -> 50f
            4 -> 42f
            5 -> 35f
            6 -> 30f
            7 -> 26f
            8 -> 22.5f
            else -> 30f
        }
        val startAngle = notchAngle + notchSize / 2f - 90f // -90 to start from top
        val sweepAngle = 360f - notchSize

        canvas.drawArc(bounds, startAngle, sweepAngle, false, ringPaint)

        // Clear shadow for next ring
        ringPaint.clearShadowLayer()
    }


    private fun drawRingIndicator(canvas: Canvas) {
        if (gameState.selectedRing == 0) return

        val indicatorPaint = Paint().apply {
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            textSize = radii[0] * 1.8f  // 1.5x larger than before (was 1.2f)
            typeface = Typeface.create("cursive", Typeface.NORMAL)  // Slightly cursive
            color = if (gameState.selectedRing in 1..7) {
                rainbowColors[gameState.selectedRing - 1]
            } else {
                0xFFB0B0B0.toInt()  // Gray for ring 8
            }
        }

        val textY = centerY - (indicatorPaint.descent() + indicatorPaint.ascent()) / 2f
        canvas.drawText(gameState.selectedRing.toString(), centerX, textY, indicatorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gameState.isButtonEnabled || gameState.isShuffling) {
            return super.onTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Entire screen is touch area (when game is active)
                isButtonPressed = true
                trackpadTouchStartX = event.x
                trackpadTouchStartY = event.y
                lastTrackpadDragX = event.x
                lastTrackpadDragY = event.y
                trackpadIsDragging = false
                ringSelectionAccumulator = 0f
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isButtonPressed) {
                    val dx = event.x - trackpadTouchStartX
                    val dy = event.y - trackpadTouchStartY
                    val distance = sqrt(dx.pow(2) + dy.pow(2))

                    if (distance > 10f * resources.displayMetrics.density) {
                        trackpadIsDragging = true
                    }

                    if (trackpadIsDragging && gameState.selectedRing > 0) {
                        // Horizontal drag = rotation
                        val deltaX = event.x - lastTrackpadDragX
                        val rotationAngle = deltaX * DRAG_SENSITIVITY
                        gameState.applyMove(gameState.selectedRing, rotationAngle)

                        // Vertical drag = ring selection (with threshold)
                        val deltaY = event.y - lastTrackpadDragY
                        ringSelectionAccumulator += deltaY

                        val threshold = 30f * resources.displayMetrics.density
                        if (ringSelectionAccumulator > threshold) {
                            // Drag down = select lower ring number (toward center)
                            if (gameState.selectedRing > 1) {
                                gameState.selectedRing--
                            }
                            ringSelectionAccumulator = 0f
                        } else if (ringSelectionAccumulator < -threshold) {
                            // Drag up = select higher ring number (toward outside)
                            if (gameState.selectedRing < 8) {
                                gameState.selectedRing++
                            }
                            ringSelectionAccumulator = 0f
                        }

                        lastTrackpadDragX = event.x
                        lastTrackpadDragY = event.y
                        invalidate()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isButtonPressed) {
                    if (!trackpadIsDragging) {
                        // Tap = cycle to next ring (with wrap)
                        handleTrackpadTap()
                    }
                    isButtonPressed = false
                    trackpadIsDragging = false
                    invalidate()
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun handleTrackpadTap() {
        if (gameState.isWon) {
            startGame()
        } else {
            // Cycle rings 1→2→3→...→8→1
            gameState.selectedRing = if (gameState.selectedRing == 0 || gameState.selectedRing == 8) {
                1
            } else {
                gameState.selectedRing + 1
            }
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
                if (isAttachedToWindow) {
                    postDelayed({ animateShuffle(moves, index + 1) }, SHUFFLE_MOVE_DELAY_MS - SHUFFLE_MOVE_DURATION_MS)
                }
            }
        })

        animator.start()
    }
}
