package com.example.chessai

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

object PromotionRecognizer {

    private const val FEATURE_SIZE = 36
    private const val AUTO_ACCEPT_MIN = 0.82
    private const val AUTO_ACCEPT_GAP = 0.05

    private val templates = mutableMapOf<Char, MutableList<FloatArray>>()

    data class Result(
        val piece: Char?,
        val bestScore: Double,
        val secondScore: Double,
        val confident: Boolean,
        val scores: Map<Char, Double>
    )

    fun clear() = templates.clear()

    fun learnFromBoard(standardBoardBitmap: Bitmap, virtualBoard: VirtualChessBoard) {
        clear()
        val squareWidth = standardBoardBitmap.width / 8
        val squareHeight = standardBoardBitmap.height / 8

        for (index in 0 until 64) {
            val piece = virtualBoard.getPieceAt(index) ?: continue
            if (piece.lowercaseChar() !in charArrayOf('q','r','b','n')) continue

            val row = index / 8
            val col = index % 8
            val square = Bitmap.createBitmap(
                standardBoardBitmap,
                col * squareWidth,
                row * squareHeight,
                squareWidth,
                squareHeight
            )

            try {
                templates.getOrPut(piece) { mutableListOf() }
                    .add(extractForegroundFeature(square))
            } finally {
                square.recycle()
            }
        }
    }

    fun recognize(standardBoardBitmap: Bitmap, targetIndex: Int, whitePiece: Boolean): Result {
        if (targetIndex !in 0..63) return Result(null, 0.0, 0.0, false, emptyMap())

        val squareWidth = standardBoardBitmap.width / 8
        val squareHeight = standardBoardBitmap.height / 8
        val row = targetIndex / 8
        val col = targetIndex % 8
        val square = Bitmap.createBitmap(
            standardBoardBitmap,
            col * squareWidth,
            row * squareHeight,
            squareWidth,
            squareHeight
        )

        val targetFeature = try {
            extractForegroundFeature(square)
        } finally {
            square.recycle()
        }

        val wanted = charArrayOf('q','r','b','n').map {
            if (whitePiece) it.uppercaseChar() else it
        }

        val scoreMap = linkedMapOf<Char, Double>()
        for (piece in wanted) {
            val bestForPiece = templates[piece].orEmpty()
                .maxOfOrNull { cosineSimilarity(targetFeature, it) } ?: 0.0
            scoreMap[piece] = bestForPiece
        }

        val ranked = scoreMap.entries.sortedByDescending { it.value }
        val best = ranked.getOrNull(0)
        val second = ranked.getOrNull(1)
        val bestScore = best?.value ?: 0.0
        val secondScore = second?.value ?: 0.0
        val confident = best != null && bestScore >= AUTO_ACCEPT_MIN && (bestScore - secondScore) >= AUTO_ACCEPT_GAP

        return Result(best?.key, bestScore, secondScore, confident, scoreMap)
    }

    private fun extractForegroundFeature(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, FEATURE_SIZE, FEATURE_SIZE, true)
        val pixels = IntArray(FEATURE_SIZE * FEATURE_SIZE)
        scaled.getPixels(pixels, 0, FEATURE_SIZE, 0, 0, FEATURE_SIZE, FEATURE_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        fun gray(pixel: Int): Double {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r * 0.299 + g * 0.587 + b * 0.114
        }

        val corner = 4
        var bgSum = 0.0
        var bgCount = 0
        for (y in 0 until FEATURE_SIZE) {
            for (x in 0 until FEATURE_SIZE) {
                val inCorner = (x < corner || x >= FEATURE_SIZE - corner) &&
                        (y < corner || y >= FEATURE_SIZE - corner)
                if (inCorner) {
                    bgSum += gray(pixels[y * FEATURE_SIZE + x])
                    bgCount++
                }
            }
        }

        val background = if (bgCount > 0) bgSum / bgCount else 128.0
        val feature = FloatArray(FEATURE_SIZE * FEATURE_SIZE)
        var norm = 0.0

        for (i in pixels.indices) {
            var value = abs(gray(pixels[i]) - background)
            if (value < 18.0) value = 0.0
            feature[i] = value.toFloat()
            norm += value * value
        }

        norm = sqrt(norm)
        if (norm > 0.0001) {
            for (i in feature.indices) feature[i] = (feature[i] / norm).toFloat()
        }
        return feature
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(0.0, 1.0)
    }
}