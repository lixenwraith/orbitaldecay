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

    // New state variables for buttons and animations
    private var isSolving = false
    private var showMenu = false
    private var isWinAnimating = false
    private var winAnimationPhase = 0  // 0-3 for wave phases
    private var winWaveProgress = 0f

    // Button dimensions
    private var resetButtonX = 0f
    private var resetButtonY = 0f
    private var resetButtonRadius = 0f
    private var menuButtonX = 0f
    private var menuButtonY = 0f
    private var menuButtonRadius = 0f
    private var solverButtonX = 0f
    private var solverButtonY = 0f
    private var solverButtonRadius = 0f

    // Solver state
    private var isSolverRunning = false
    private var solverInterrupted = false

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

        // Button dimensions
        val buttonSize = 24f * resources.displayMetrics.density
        val buttonMargin = 20f * resources.displayMetrics.density
        resetButtonRadius = buttonSize
        resetButtonX = buttonMargin + buttonSize
        resetButtonY = buttonMargin + buttonSize
        menuButtonX = w - buttonMargin - buttonSize
        menuButtonY = buttonMargin + buttonSize
        menuButtonRadius = buttonSize
        solverButtonX = w - buttonMargin - buttonSize
        solverButtonY = h - buttonMargin - buttonSize
        solverButtonRadius = buttonSize

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

        // Draw rings in correct z-order with cascading elevation
        // Group rings by elevation and draw lowest first
        val ringsByElevation = (1..8).groupBy { ringIndex ->
            if (isWinAnimating) {
                getWinAnimationElevation(ringIndex)
            } else if (gameState.selectedRing == 0) {
                0
            } else {
                val distance = kotlin.math.abs(ringIndex - gameState.selectedRing)
                when (distance) {
                    0 -> 8  // Selected ring - highest
                    1 -> 6
                    2 -> 4
                    3 -> 3
                    4 -> 2
                    5 -> 1
                    else -> 0
                }
            }
        }.toSortedMap()  // Sort by elevation (lowest first)

        for ((elevation, rings) in ringsByElevation) {
            for (ringIndex in rings) {
                drawRingWithElevation(canvas, ringIndex, elevation)
            }
        }

        // Draw center circle (ring 0) - solid fill, no notch
        canvas.drawCircle(centerX, centerY, radii[0], centerCirclePaint)

        // Check win condition during manual play
        if (!gameState.isWon && !gameState.isShuffling && !isSolverRunning && gameState.isButtonEnabled && checkWinConditionInternal()) {
            gameState.isWon = true
            gameState.isButtonEnabled = false  // Lock interaction
            startWinAnimation()
        }

        // Draw content in center: ring indicator OR smiley
        if (gameState.isWon) {
            drawWinSmiley(canvas)
        } else if (gameState.isButtonEnabled && !gameState.isWon) {
            drawRingIndicator(canvas)
        }

        // Draw UI buttons (always visible unless menu is open)
        if (!showMenu) {
            drawResetButton(canvas)
            drawMenuButton(canvas)
            // Draw solver button only during active gameplay
            if (!gameState.isWon && gameState.isButtonEnabled && !isSolving && !isSolverRunning) {
                drawSolverButton(canvas)
            }
        }

        // Draw menu overlay last (on top of everything)
        if (showMenu) {
            drawMenuOverlay(canvas)
        }
    }

    private fun drawRingWithElevation(canvas: Canvas, index: Int, elevationLevel: Int) {
        val radius = radii[index]

        // Graduated stroke width based on elevation (0-8 scale)
        val extraStroke = elevationLevel * 0.5f * resources.displayMetrics.density
        val strokeWidth = ringWidth + extraStroke
        ringPaint.strokeWidth = strokeWidth

        // Graduated shadow based on elevation
        if (elevationLevel > 0) {
            val shadowRadius = elevationLevel * 1.2f
            val shadowOffset = elevationLevel * 0.6f
            val shadowAlpha = (0x20 + elevationLevel * 0x10).coerceAtMost(0xA0)
            val shadowColor = (shadowAlpha shl 24) or 0x000000
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
            textSize = radii[0] * 1.6f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)  // Bold serif
            color = if (gameState.selectedRing in 1..7) {
                rainbowColors[gameState.selectedRing - 1]
            } else {
                0xFFB0B0B0.toInt()  // Gray for ring 8
            }
        }

        val textY = centerY - (indicatorPaint.descent() + indicatorPaint.ascent()) / 2f
        canvas.drawText(gameState.selectedRing.toString(), centerX, textY, indicatorPaint)
    }

    private fun drawResetButton(canvas: Canvas) {
        val paint = Paint().apply {
            color = 0x80FFFFFF.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = resetButtonRadius * 1.4f
            typeface = Typeface.DEFAULT_BOLD
        }

        val textY = resetButtonY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText("↻", resetButtonX, textY, paint)
    }

    private fun drawMenuButton(canvas: Canvas) {
        val paint = Paint().apply {
            color = 0x80FFFFFF.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = menuButtonRadius * 1.4f
            typeface = Typeface.DEFAULT_BOLD
        }

        val textY = menuButtonY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText("?", menuButtonX, textY, paint)
    }

    private fun drawSolverButton(canvas: Canvas) {
        val paint = Paint().apply {
            color = 0x80FFFFFF.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = solverButtonRadius * 1.2f
            typeface = Typeface.DEFAULT_BOLD
        }

        val textY = solverButtonY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText("!", solverButtonX, textY, paint)
    }

    private fun drawMenuOverlay(canvas: Canvas) {
        // Uniform dim across entire screen
        val dimPaint = Paint().apply {
            color = 0xC0000000.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val textPaint = Paint().apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val titleSize = 28f * resources.displayMetrics.density
        val headerSize = 18f * resources.displayMetrics.density
        val textSize = 16f * resources.displayMetrics.density
        val lineHeight = textSize * 1.7f

        // Calculate available space above and below circles
        val circleTop = centerY - radii[8] - ringWidth
        val circleBottom = centerY + radii[8] + ringWidth
        val topSpace = circleTop
        val bottomSpace = height - circleBottom

        // ===== TOP SECTION =====
        // Center content vertically in available space above circle
        val topContentHeight = titleSize + lineHeight * 2.5f  // title + goal header + goal text
        var y = (topSpace - topContentHeight) / 2f + titleSize

        // Title
        textPaint.textSize = titleSize
        textPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawText("LIXEN PUZZLE", centerX, y, textPaint)
        y += lineHeight * 1.3f

        // Goal header
        textPaint.textSize = headerSize
        textPaint.color = 0xFFFFFF00.toInt()
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("GOAL", centerX, y, textPaint)
        y += lineHeight

        // Goal text
        textPaint.textSize = textSize
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Align all notches in a straight line", centerX, y, textPaint)

        // ===== BOTTOM SECTION =====
        // Center content vertically in available space below circle
        val bottomContentHeight = lineHeight * 7f  // headers + control lines + mechanics
        y = circleBottom + (bottomSpace - bottomContentHeight) / 2f + headerSize

        // Controls header
        textPaint.textSize = headerSize
        textPaint.color = 0xFFFFFF00.toInt()
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("CONTROLS", centerX, y, textPaint)
        y += lineHeight

        // Controls text with arrows
        textPaint.textSize = textSize
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Tap: Select next ring ↻", centerX, y, textPaint)
        y += lineHeight
        canvas.drawText("Drag ←→: Rotate ring", centerX, y, textPaint)
        y += lineHeight
        canvas.drawText("Drag ↑↓: Select outer/inner ring", centerX, y, textPaint)
        y += lineHeight * 1.3f

        // Mechanics header
        textPaint.textSize = headerSize
        textPaint.color = 0xFFFFFF00.toInt()
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("MECHANICS", centerX, y, textPaint)
        y += lineHeight

        // Mechanics text
        textPaint.textSize = textSize
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Rotating a ring moves", centerX, y, textPaint)
        y += lineHeight
        canvas.drawText("adjacent rings at half speed", centerX, y, textPaint)
    }

    private fun isTouchingButton(x: Float, y: Float, bx: Float, by: Float, radius: Float): Boolean {
        val dx = x - bx
        val dy = y - by
        return sqrt(dx.pow(2) + dy.pow(2)) <= radius * 1.5f  // 1.5x for easier tap
    }

    private fun solveAndReshuffle() {
        if (isSolving || gameState.isShuffling) return

        // Stop any ongoing win animation
        waveAnimator?.cancel()
        waveAnimator = null
        isWinAnimating = false
        winAnimationPhase = 0

        isSolving = true
        gameState.isButtonEnabled = false
        gameState.selectedRing = 0
        invalidate()

        // Calculate target: align to top (12 o'clock)
        val targetAngle = 0f

        // Calculate shortest rotation for each ring to reach target
        val moves = mutableListOf<Pair<Int, Float>>()
        for (i in 1..8) {
            var diff = targetAngle - gameState.angles[i]
            // Normalize to -180..180 (shortest path)
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            if (kotlin.math.abs(diff) > 0.5f) {
                moves.add(i to diff)
            }
        }

        // Animate solve (each ring independently, simultaneously)
        animateSolve(moves) {
            // After solve complete, start shuffle
            postDelayed({
                startGame()
                isSolving = false
            }, 500)
        }
    }

    private fun animateSolve(moves: List<Pair<Int, Float>>, onComplete: () -> Unit) {
        if (moves.isEmpty()) {
            onComplete()
            return
        }

        val duration = 800L
        val animators = mutableListOf<ValueAnimator>()

        for ((ring, targetDelta) in moves) {
            val startAngle = gameState.angles[ring]
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    gameState.angles[ring] = gameState.normalizeAngle(startAngle + targetDelta * progress)
                    invalidate()
                }
            }
            animators.add(animator)
        }

        // Start all animators, call onComplete when last one finishes
        animators.forEachIndexed { index, animator ->
            if (index == animators.lastIndex) {
                animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onComplete()
                    }
                })
            }
            animator.start()
        }
    }

    private fun runSolver() {
        if (isSolverRunning || isSolving || gameState.isShuffling || isWinAnimating) return
        isSolverRunning = true
        solverInterrupted = false
        gameState.isButtonEnabled = false
        gameState.selectedRing = 0
        invalidate()

        // Target: align all to 0° (top)
        val targetAngle = 0f

        // Start iterative solving
        solveIteratively(targetAngle)
    }

    private fun solveIteratively(targetAngle: Float) {
        if (solverInterrupted) {
            finishSolver()
            return
        }

        // Check if already solved
        if (checkWinConditionInternal()) {
            gameState.isWon = true
            isSolverRunning = false
            gameState.selectedRing = 0
            startWinAnimation()
            return
        }

        // Find the ring furthest from target
        var maxDiff = 0f
        var worstRing = -1
        for (ring in 1..8) {
            var diff = targetAngle - gameState.angles[ring]
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            if (kotlin.math.abs(diff) > maxDiff) {
                maxDiff = kotlin.math.abs(diff)
                worstRing = ring
            }
        }

        // If all rings within tolerance, we're done
        if (maxDiff < 0.5f || worstRing == -1) {
            gameState.checkWinCondition()
            finishSolver()
            return
        }

        // Animate moving the worst ring toward target
        var diff = targetAngle - gameState.angles[worstRing]
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f

        gameState.selectedRing = worstRing

        val duration = 150L
        val startAngle = gameState.angles[worstRing]
        val startInner = if (worstRing > 1) gameState.angles[worstRing - 1] else 0f
        val startOuter = if (worstRing < 8) gameState.angles[worstRing + 1] else 0f

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                val delta = diff * progress

                gameState.angles[worstRing] = gameState.normalizeAngle(startAngle + delta)
                if (worstRing > 1) {
                    gameState.angles[worstRing - 1] = gameState.normalizeAngle(startInner + delta * 0.5f)
                }
                if (worstRing < 8) {
                    gameState.angles[worstRing + 1] = gameState.normalizeAngle(startOuter + delta * 0.5f)
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!solverInterrupted && isAttachedToWindow) {
                        postDelayed({ solveIteratively(targetAngle) }, 30)
                    } else {
                        finishSolver()
                    }
                }
            })
        }
        animator.start()
    }

    private fun finishSolver() {
        isSolverRunning = false
        gameState.selectedRing = 0
        if (!gameState.isWon) {
            gameState.isButtonEnabled = true
        }
        invalidate()
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a < 0) a += 360f
        return a
    }

    private fun checkWinConditionInternal(): Boolean {
        // Win condition: all notch centers are within half of smallest notch opening (11.25°)
        // Use proper angular math with radians, handling wrap-around at 0/360

        val smallestNotchHalf = 22.5f / 2f  // 11.25 degrees tolerance

        // Convert all angles to radians for proper math
        val angles = (1..8).map {
            var a = gameState.angles[it] % 360f
            if (a < 0) a += 360f
            Math.toRadians(a.toDouble())
        }.sorted()

        // Find the smallest angular span that contains all points
        // This is 2π minus the largest gap between consecutive angles
        var maxGap = 0.0
        for (i in angles.indices) {
            val next = if (i == angles.lastIndex) angles[0] + 2 * Math.PI else angles[i + 1]
            val gap = next - angles[i]
            if (gap > maxGap) maxGap = gap
        }

        // The span is the complement of the largest gap
        val spanRadians = 2 * Math.PI - maxGap
        val spanDegrees = Math.toDegrees(spanRadians).toFloat()

        // Win if all notches fit within the tolerance
        return spanDegrees <= smallestNotchHalf
    }

    private fun drawWinSmiley(canvas: Canvas) {
        val paint = Paint().apply {
            color = 0xFFB0B0B0.toInt()  // Same gray as outer ring
            style = Paint.Style.STROKE
            strokeWidth = 6f * resources.displayMetrics.density
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        // 3x bigger - use most of center circle
        val size = radii[0] * 0.85f

        // Left eye
        val eyeY = centerY - size * 0.2f
        val eyeSpacing = size * 0.3f
        val eyeRadius = size * 0.12f
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX - eyeSpacing, eyeY, eyeRadius, paint)

        // Right eye
        canvas.drawCircle(centerX + eyeSpacing, eyeY, eyeRadius, paint)

        // Smile (arc)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f * resources.displayMetrics.density
        val smileRect = RectF(
            centerX - size * 0.45f,
            centerY - size * 0.15f,
            centerX + size * 0.45f,
            centerY + size * 0.5f
        )
        canvas.drawArc(smileRect, 15f, 150f, false, paint)
    }

    private fun startWinAnimation() {
        isWinAnimating = true
        gameState.isButtonEnabled = false
        gameState.selectedRing = 0
        invalidate()

        // Step 1: Align notches to their center (shortest path)
        val avgAngle = gameState.angles.slice(1..8).average().toFloat()
        val alignMoves = mutableListOf<Pair<Int, Float>>()
        for (i in 1..8) {
            var diff = avgAngle - gameState.angles[i]
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            alignMoves.add(i to diff)
        }

        animateSolve(alignMoves) {
            // Start continuous wave animation (keeps isWinAnimating = true)
            startWaveAnimation()
        }
    }

    private var waveAnimator: ValueAnimator? = null

    private fun startWaveAnimation() {
        val waveDuration = 500L

        fun animateWave() {
            // Check if still in win state (not reset)
            if (!gameState.isWon) {
                isWinAnimating = false
                winAnimationPhase = 0
                waveAnimator = null
                invalidate()
                return
            }

            // Pause animation while menu is open
            if (showMenu) {
                postDelayed({ animateWave() }, 100)
                return
            }

            waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = waveDuration
                interpolator = LinearInterpolator()
                addUpdateListener {
                    winWaveProgress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (isAttachedToWindow && gameState.isWon) {
                            // Continue waving indefinitely
                            winAnimationPhase = (winAnimationPhase % 3) + 1
                            animateWave()
                        }
                    }
                })
            }
            waveAnimator?.start()
        }

        winAnimationPhase = 1
        animateWave()
    }

    private fun getWinAnimationElevation(ringIndex: Int): Int {
        if (!isWinAnimating || winAnimationPhase == 0) return 0

        // Wave travels from center (ring 1) outward
        // Progress 0-1 maps to wave position across rings
        val waveCenter = 1f + winWaveProgress * 9f  // 1 to 10
        val distance = kotlin.math.abs(ringIndex - waveCenter)

        // Stronger bell curve elevation for more visible effect
        val elevation = if (distance < 2.5f) {
            ((2.5f - distance) / 2.5f * 10f).toInt()
        } else {
            0
        }
        return elevation
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Interrupt solver if running
                if (isSolverRunning) {
                    solverInterrupted = true
                    return true
                }

                // Check menu overlay tap (close it)
                if (showMenu) {
                    showMenu = false
                    invalidate()
                    return true
                }

                // Check reset button
                if (isTouchingButton(event.x, event.y, resetButtonX, resetButtonY, resetButtonRadius)) {
                    solveAndReshuffle()
                    return true
                }

                // Check menu button
                if (isTouchingButton(event.x, event.y, menuButtonX, menuButtonY, menuButtonRadius)) {
                    showMenu = true
                    invalidate()
                    return true
                }

                // Check solver button (before the animation blocking check)
                if (isTouchingButton(event.x, event.y, solverButtonX, solverButtonY, solverButtonRadius)) {
                    if (gameState.isButtonEnabled && !gameState.isShuffling && !isSolving && !isWinAnimating && !gameState.isWon && !isSolverRunning) {
                        runSolver()
                        return true
                    }
                }

                // Block all gameplay interaction if won (only allow reset button handled above)
                if (gameState.isWon) {
                    return true
                }

                // Block input during animations
                if (!gameState.isButtonEnabled || gameState.isShuffling || isSolving || isWinAnimating) {
                    return super.onTouchEvent(event)
                }

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
        // Clear win animation state
        waveAnimator?.cancel()
        waveAnimator = null
        isWinAnimating = false
        winAnimationPhase = 0
        winWaveProgress = 0f

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
