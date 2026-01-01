package com.lixen.rings

import kotlin.math.abs
import kotlin.random.Random

class ShuffleSequence(seed: Long = System.currentTimeMillis()) {
    private val random = Random(seed)
    private val moves = mutableListOf<Pair<Int, Float>>() // (ringIndex, angle)

    fun generate(count: Int = 25): List<Pair<Int, Float>> {
        moves.clear()
        repeat(count) {
            val ring = random.nextInt(1, 9)
            val angle = (random.nextInt(4, 12) * 30f) * if (random.nextBoolean()) 1 else -1
            // Angles: ±120°, ±150°, ±180°, ±210°, ±240°, ±270°, ±300°, ±330°
            moves.add(ring to angle)
        }
        return moves
    }

    fun getReverseMoves(): List<Pair<Int, Float>> {
        return moves.reversed().map { (ring, angle) -> ring to -angle }
    }
}

data class GameState(
    val angles: FloatArray = FloatArray(9) { 0f }
) {
    var isShuffling = false
    var isWon = false
    var selectedRing: Int = 0  // 0 = none, 1-8 = selected
    var isButtonEnabled: Boolean = false

    fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0) normalized += 360f
        return normalized
    }

    fun applyMove(ring: Int, angle: Float) {
        if (isWon || ring < 1 || ring > 8) return

        // Rotate the selected ring
        angles[ring] = normalizeAngle(angles[ring] + angle)

        // Propagate to inner ring (half rotation)
        if (ring > 1) {
            angles[ring - 1] = normalizeAngle(angles[ring - 1] + angle * 0.5f)
        }

        // Propagate to outer ring (half rotation)
        if (ring < 8) {
            angles[ring + 1] = normalizeAngle(angles[ring + 1] + angle * 0.5f)
        }

        checkWinCondition()
    }

    fun reset() {
        for (i in angles.indices) {
            angles[i] = 0f
        }
        isWon = false
        selectedRing = 0
        isButtonEnabled = false
    }

    fun checkWinCondition() {
        if (isShuffling) return

        // Check if all circles 1-8 are within ±3° of each other
        val tolerance = 3f
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
        if (selectedRing != other.selectedRing) return false
        if (isButtonEnabled != other.isButtonEnabled) return false

        return true
    }

    override fun hashCode(): Int {
        var result = angles.contentHashCode()
        result = 31 * result + isShuffling.hashCode()
        result = 31 * result + isWon.hashCode()
        result = 31 * result + selectedRing.hashCode()
        result = 31 * result + isButtonEnabled.hashCode()
        return result
    }
}
