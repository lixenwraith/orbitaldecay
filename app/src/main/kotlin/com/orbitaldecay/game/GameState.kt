package com.orbitaldecay.game

import kotlin.math.abs
import kotlin.random.Random

data class GameState(
    val angles: FloatArray = FloatArray(9) { 0f }
) {
    var isShuffling = false
    var isWon = false

    fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0) normalized += 360f
        return normalized
    }

    fun rotateCircle(circleIndex: Int, deltaAngle: Float) {
        if (isShuffling || isWon || circleIndex < 1 || circleIndex > 8) return

        // Rotate the touched circle
        angles[circleIndex] = normalizeAngle(angles[circleIndex] + deltaAngle)

        // Propagate to inner circle (half rotation)
        if (circleIndex > 1) {
            angles[circleIndex - 1] = normalizeAngle(angles[circleIndex - 1] + deltaAngle * 0.5f)
        }

        // Propagate to outer circle (half rotation)
        if (circleIndex < 8) {
            angles[circleIndex + 1] = normalizeAngle(angles[circleIndex + 1] + deltaAngle * 0.5f)
        }

        checkWinCondition()
    }

    fun shuffle(onStep: () -> Unit, onComplete: () -> Unit) {
        isShuffling = true
        isWon = false

        val shuffleSteps = 20
        var currentStep = 0

        fun performStep() {
            if (currentStep < shuffleSteps) {
                val randomCircle = Random.nextInt(1, 9) // circles 1-8
                val randomAngle = Random.nextInt(45, 316).toFloat() // 45° to 315°

                // Directly rotate without propagation during shuffle
                angles[randomCircle] = normalizeAngle(angles[randomCircle] + randomAngle)

                // Apply propagation
                if (randomCircle > 1) {
                    angles[randomCircle - 1] = normalizeAngle(angles[randomCircle - 1] + randomAngle * 0.5f)
                }
                if (randomCircle < 8) {
                    angles[randomCircle + 1] = normalizeAngle(angles[randomCircle + 1] + randomAngle * 0.5f)
                }

                currentStep++
                onStep()

                // Schedule next step
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    performStep()
                }, 50)
            } else {
                isShuffling = false
                onComplete()
            }
        }

        performStep()
    }

    fun reset() {
        for (i in angles.indices) {
            angles[i] = 0f
        }
        isWon = false
    }

    private fun checkWinCondition() {
        if (isShuffling) return

        // Check if all circles 1-8 are within ±5° of any common angle
        // We'll check if they're all aligned to the same angle (within tolerance)
        val tolerance = 5f
        val referenceAngle = angles[1]

        var allAligned = true
        for (i in 2..8) {
            val diff = abs(angleDifference(angles[i], referenceAngle))
            if (diff > tolerance) {
                allAligned = false
                break
            }
        }

        isWon = allAligned
    }

    private fun angleDifference(angle1: Float, angle2: Float): Float {
        var diff = angle1 - angle2
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return diff
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GameState

        if (!angles.contentEquals(other.angles)) return false
        if (isShuffling != other.isShuffling) return false
        if (isWon != other.isWon) return false

        return true
    }

    override fun hashCode(): Int {
        var result = angles.contentHashCode()
        result = 31 * result + isShuffling.hashCode()
        result = 31 * result + isWon.hashCode()
        return result
    }
}
