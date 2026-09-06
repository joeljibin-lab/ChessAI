package com.example.chessai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs

/**
 * Full-board piece recognizer used ONLY by manual HARD RESYNC.
 *
 * It is deliberately not part of the normal live frame loop.
 *
 * Training:
 * - Runs once after the starting position is confirmed.
 * - Uses the already-trusted VirtualChessBoard as labels.
 * - Learns piece appearance from the real board/theme on screen.
 *
 * Recognition:
 * - Runs once when the user presses RESYNC and the board has settled.
 * - Produces the 64 visible pieces without trusting the current virtual board.
 *
 * Feature design:
 * The background of a light square and dark square is very different.
 * To make templates usable after a piece moves to the opposite square color,
 * every sampled pixel is represented relative to the square's own corner
 * background color rather than as raw RGB.
 */
object BoardPositionRecognizer {

    private const val TAG = "ChessAI_HardResync"

    private const val FEATURE_GRID = 14
    private const val FEATURE_SIZE = FEATURE_GRID * FEATURE_GRID * 3

    private const val STORAGE_MAGIC = 0x43484149
    private const val STORAGE_VERSION = 1
    private const val STORAGE_FILE = "board_position_templates_v1.bin"

    /*
     * Ignore the outer border because:
     * - recommendation outlines live there
     * - Chess.com move highlights often affect square edges
     */
    private const val CROP_FRACTION = 0.12

    /*
     * HARD RESYNC recognition is deliberately conservative.
     *
     * Recognition is staged:
     *
     *   1. EMPTY vs OCCUPIED
     *   2. WHITE vs BLACK
     *   3. PIECE TYPE
     *
     * The CURRENT VirtualChessBoard is never consulted during recognition.
     * If any stage is uncertain, the entire RESYNC fails instead of guessing.
     */
    private const val MAX_CLASS_DISTANCE = 64.0

    /*
     * Required separation between EMPTY and the best occupied class.
     */
    private const val MIN_OCCUPANCY_MARGIN = 5.0

    /*
     * Required separation between the best White-piece class and the best
     * Black-piece class.
     */
    private const val MIN_COLOR_MARGIN = 4.0

    /*
     * Required separation between the best and second-best piece TYPE within
     * the already-selected color.
     */
    private const val MIN_TYPE_MARGIN = 3.0

    /*
     * For classes with several saved examples (pawns, empty squares, rooks,
     * etc.), use the average of the closest few examples instead of trusting a
     * single lucky nearest-template match.
     */
    private const val CLASS_NEAREST_VARIANTS_TO_AVERAGE = 3

    /*
     * Piece TYPE is mostly a silhouette problem once occupancy and color have
     * already been selected. Use a nearest-template score so a rook on h8 can
     * match the actual saved rook appearance instead of being diluted by
     * averaging the other rook variant.
     */
    private const val TYPE_SHAPE_WEIGHT = 0.70
    private const val TYPE_RGB_WEIGHT = 0.30

    private val templates =
        mutableMapOf<Char, MutableList<DoubleArray>>()

    @Volatile
    private var trained = false

    @Volatile
    private var loadedFromStorage = false


    data class Result(
        val pieces: CharArray,
        val fenPlacement: String,
        val averageDistance: Double,
        val weakestMargin: Double
    )


    fun isTrained(): Boolean =
        trained


    fun wasLoadedFromStorage(): Boolean =
        loadedFromStorage


    /** Clears RAM only. The persistent template file is untouched. */
    fun clearMemory() {
        templates.clear()
        trained = false
        loadedFromStorage = false
    }


    /** Backward-compatible alias: RAM only, never deletes saved templates. */
    fun clear() {
        clearMemory()
    }


    fun loadFromStorage(context: Context): Boolean {
        clearMemory()

        val file = context.getFileStreamPath(STORAGE_FILE)
        if (file == null || !file.exists()) {
            Log.d(TAG, "No saved board recognizer templates found.")
            return false
        }

        return try {
            DataInputStream(
                BufferedInputStream(context.openFileInput(STORAGE_FILE))
            ).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                val featureSize = input.readInt()

                if (magic != STORAGE_MAGIC || version != STORAGE_VERSION || featureSize != FEATURE_SIZE) {
                    throw IllegalStateException("Template file format mismatch")
                }

                val classCount = input.readInt()
                if (classCount !in 1..32) {
                    throw IllegalStateException("Invalid class count=$classCount")
                }

                repeat(classCount) {
                    val piece = input.readChar()
                    val variantCount = input.readInt()
                    if (piece !in ".prnbqkPRNBQK" || variantCount !in 1..64) {
                        throw IllegalStateException("Invalid template entry")
                    }

                    val variants = mutableListOf<DoubleArray>()
                    repeat(variantCount) {
                        val size = input.readInt()
                        if (size != FEATURE_SIZE) {
                            throw IllegalStateException("Invalid feature size=$size")
                        }
                        val feature = DoubleArray(size)
                        for (i in feature.indices) feature[i] = input.readDouble()
                        variants.add(feature)
                    }
                    templates[piece] = variants
                }
            }

            val required = setOf('.', 'P','N','B','R','Q','K','p','n','b','r','q','k')
            val valid = templates.keys.containsAll(required) &&
                    required.all { piece ->
                        val variants = templates[piece]
                        variants != null && variants.isNotEmpty() && variants.all { it.size == FEATURE_SIZE }
                    }

            if (!valid) {
                clearMemory()
                Log.w(TAG, "Saved recognizer templates were incomplete.")
                false
            } else {
                trained = true
                loadedFromStorage = true
                Log.d(TAG, "Loaded persistent board recognizer templates. classes=${templates.keys.sorted()}")
                true
            }
        } catch (e: Exception) {
            clearMemory()
            Log.e(TAG, "Failed to load saved board recognizer templates.", e)
            false
        }
    }


    fun saveToStorage(context: Context, overwrite: Boolean = false): Boolean {
        if (!trained) return false

        val existing = context.getFileStreamPath(STORAGE_FILE)
        if (!overwrite && existing != null && existing.exists()) {
            Log.d(TAG, "Persistent board templates already exist; not overwriting.")
            return true
        }

        return try {
            DataOutputStream(
                BufferedOutputStream(context.openFileOutput(STORAGE_FILE, Context.MODE_PRIVATE))
            ).use { output ->
                output.writeInt(STORAGE_MAGIC)
                output.writeInt(STORAGE_VERSION)
                output.writeInt(FEATURE_SIZE)
                output.writeInt(templates.size)

                for (piece in templates.keys.sorted()) {
                    val variants = templates[piece] ?: continue
                    output.writeChar(piece.code)
                    output.writeInt(variants.size)
                    for (feature in variants) {
                        output.writeInt(feature.size)
                        for (value in feature) output.writeDouble(value)
                    }
                }
            }
            Log.d(TAG, "Saved persistent board recognizer templates.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save board recognizer templates.", e)
            false
        }
    }


    fun deleteSavedTemplates(context: Context): Boolean {
        clearMemory()
        return context.deleteFile(STORAGE_FILE)
    }


    /**
     * Learn templates from one trusted board.
     *
     * The board bitmap MUST already be normalized to standard orientation:
     * a8 = index 0, h1 = index 63.
     */
    fun learnFromTrustedBoard(
        standardBoardBitmap: Bitmap,
        virtualBoard: VirtualChessBoard
    ) {

        clear()

        val squares =
            ChessBoardUtils.sliceBoardIntoSquares(
                standardBoardBitmap
            )

        try {

            for (index in 0 until 64) {

                val piece =
                    virtualBoard.getPieceAt(index)
                        ?: '.'

                val feature =
                    extractFeature(
                        squares[index]
                    )

                templates
                    .getOrPut(piece) {
                        mutableListOf()
                    }
                    .add(feature)
            }

            /*
             * Starting chess positions always contain all normal piece types.
             * Even if White made the first move before a Black-side setup,
             * at least one example of every normal type still remains.
             */
            val required =
                setOf(
                    '.',
                    'P', 'N', 'B', 'R', 'Q', 'K',
                    'p', 'n', 'b', 'r', 'q', 'k'
                )

            val missing =
                required.filter {
                    it !in templates
                }

            trained =
                missing.isEmpty()

            loadedFromStorage =
                false

            if (trained) {

                Log.d(
                    TAG,
                    "Board recognizer trained. classes=${templates.keys.sorted()}"
                )

            } else {

                Log.w(
                    TAG,
                    "Board recognizer training incomplete. missing=$missing"
                )
            }

        } finally {

            squares.forEach {
                try {
                    it.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }


    /**
     * Recognize one settled current board.
     *
     * Returns null when confidence is insufficient.
     */
    fun recognize(
        standardBoardBitmap: Bitmap
    ): Result? {

        if (!trained) {

            Log.w(
                TAG,
                "HARD RESYNC recognition requested before template training/loading."
            )

            return null
        }


        val squares =
            ChessBoardUtils.sliceBoardIntoSquares(
                standardBoardBitmap
            )


        try {

            val pieces =
                CharArray(64) {
                    '.'
                }


            var totalChosenDistance =
                0.0

            var weakestMargin =
                Double.POSITIVE_INFINITY


            for (index in 0 until 64) {

                val squareName =
                    ChessBoardUtils.indexToSquareName(
                        index
                    )


                val feature =
                    extractFeature(
                        squares[index]
                    )


                /*
                 * ========================================================
                 * STAGE 1 — EMPTY vs OCCUPIED
                 * ========================================================
                 *
                 * Do not ask "which of 13 labels is closest?".
                 *
                 * First prove that the square is either empty or occupied.
                 */
                val emptyDistance =
                    classDistance(
                        feature =
                            feature,
                        piece =
                            '.'
                    )


                val occupiedRanked =
                    ".prnbqkPRNBQK"
                        .filter {
                            it != '.'
                        }
                        .map {
                                piece ->

                            piece to
                                    classDistance(
                                        feature =
                                            feature,
                                        piece =
                                            piece
                                    )
                        }
                        .sortedBy {
                            it.second
                        }


                if (occupiedRanked.isEmpty()) {

                    Log.w(
                        TAG,
                        "HARD RESYNC failed at $squareName: no occupied templates."
                    )

                    return null
                }


                val bestOccupied =
                    occupiedRanked[0]


                val occupancyMargin =
                    kotlin.math.abs(
                        emptyDistance -
                                bestOccupied.second
                    )


                val occupancyBestDistance =
                    minOf(
                        emptyDistance,
                        bestOccupied.second
                    )


                if (
                    occupancyBestDistance >
                    MAX_CLASS_DISTANCE ||
                    occupancyMargin <
                    MIN_OCCUPANCY_MARGIN
                ) {

                    Log.w(
                        TAG,
                        "HARD RESYNC uncertain OCCUPANCY at $squareName | " +
                                "empty=${format(emptyDistance)} " +
                                "occupied=${bestOccupied.first}:${format(bestOccupied.second)} " +
                                "margin=${format(occupancyMargin)}"
                    )

                    return null
                }


                if (
                    emptyDistance <
                    bestOccupied.second
                ) {

                    pieces[index] =
                        '.'

                    totalChosenDistance +=
                        emptyDistance

                    weakestMargin =
                        minOf(
                            weakestMargin,
                            occupancyMargin
                        )

                    continue
                }


                /*
                 * ========================================================
                 * STAGE 2 — WHITE vs BLACK
                 * ========================================================
                 *
                 * At this point the square is already known to be occupied.
                 * Now decide only the piece COLOR.
                 */
                val whiteRanked =
                    charArrayOf(
                        'P',
                        'N',
                        'B',
                        'R',
                        'Q',
                        'K'
                    )
                        .map {
                                piece ->

                            piece to
                                    classDistance(
                                        feature =
                                            feature,
                                        piece =
                                            piece
                                    )
                        }
                        .sortedBy {
                            it.second
                        }


                val blackRanked =
                    charArrayOf(
                        'p',
                        'n',
                        'b',
                        'r',
                        'q',
                        'k'
                    )
                        .map {
                                piece ->

                            piece to
                                    classDistance(
                                        feature =
                                            feature,
                                        piece =
                                            piece
                                    )
                        }
                        .sortedBy {
                            it.second
                        }


                val bestWhite =
                    whiteRanked.firstOrNull()
                        ?: return null

                val bestBlack =
                    blackRanked.firstOrNull()
                        ?: return null


                val colorMargin =
                    kotlin.math.abs(
                        bestWhite.second -
                                bestBlack.second
                    )


                val bestColorDistance =
                    minOf(
                        bestWhite.second,
                        bestBlack.second
                    )


                if (
                    bestColorDistance >
                    MAX_CLASS_DISTANCE ||
                    colorMargin <
                    MIN_COLOR_MARGIN
                ) {

                    Log.w(
                        TAG,
                        "HARD RESYNC uncertain COLOR at $squareName | " +
                                "white=${bestWhite.first}:${format(bestWhite.second)} " +
                                "black=${bestBlack.first}:${format(bestBlack.second)} " +
                                "margin=${format(colorMargin)}"
                    )

                    return null
                }


                val selectedColorPieces =
                    if (
                        bestWhite.second <
                        bestBlack.second
                    ) {
                        charArrayOf(
                            'P',
                            'N',
                            'B',
                            'R',
                            'Q',
                            'K'
                        )
                    } else {
                        charArrayOf(
                            'p',
                            'n',
                            'b',
                            'r',
                            'q',
                            'k'
                        )
                    }


                /*
                 * For TYPE, re-rank within the selected color using a
                 * silhouette-aware NEAREST template score.
                 *
                 * Occupancy/color above remain conservative class averages.
                 */
                val selectedColorRanked =
                    selectedColorPieces
                        .map {
                                piece ->

                            piece to
                                    classTypeDistance(
                                        feature =
                                            feature,
                                        piece =
                                            piece
                                    )
                        }
                        .sortedBy {
                            it.second
                        }


                /*
                 * ========================================================
                 * STAGE 3 — PIECE TYPE
                 * ========================================================
                 *
                 * Only compare P/N/B/R/Q/K inside the color already selected.
                 */
                if (
                    selectedColorRanked.size <
                    2
                ) {

                    Log.w(
                        TAG,
                        "HARD RESYNC failed at $squareName: not enough type candidates."
                    )

                    return null
                }


                val bestType =
                    selectedColorRanked[0]

                val secondType =
                    selectedColorRanked[1]


                val typeMargin =
                    secondType.second -
                            bestType.second


                if (
                    bestType.second >
                    MAX_CLASS_DISTANCE ||
                    typeMargin <
                    MIN_TYPE_MARGIN
                ) {

                    val allTypeScores =
                        selectedColorRanked
                            .joinToString(",") {
                                    candidate ->
                                "${candidate.first}:${format(candidate.second)}"
                            }


                    Log.w(
                        TAG,
                        "HARD RESYNC uncertain TYPE at $squareName | " +
                                "best=${bestType.first}:${format(bestType.second)} " +
                                "second=${secondType.first}:${format(secondType.second)} " +
                                "margin=${format(typeMargin)} " +
                                "all=[$allTypeScores]"
                    )

                    return null
                }


                pieces[index] =
                    bestType.first


                totalChosenDistance +=
                    bestType.second


                weakestMargin =
                    minOf(
                        weakestMargin,
                        occupancyMargin,
                        colorMargin,
                        typeMargin
                    )
            }


            /*
             * ========================================================
             * INDEPENDENT BOARD SANITY CHECK
             * ========================================================
             *
             * These checks use ONLY the freshly recognized 64-square array.
             * They do not compare against the old VirtualChessBoard/FEN.
             */
            val sanityError =
                validateRecognizedBoard(
                    pieces
                )


            if (sanityError != null) {

                Log.w(
                    TAG,
                    "HARD RESYNC rejected reconstructed board: $sanityError"
                )

                return null
            }


            val fenPlacement =
                toFenPlacement(
                    pieces
                )


            val average =
                totalChosenDistance /
                        64.0


            Log.d(
                TAG,
                "HARD RESYNC independently recognized current board. " +
                        "avg=${format(average)} " +
                        "weakestMargin=${format(weakestMargin)} " +
                        "placement=$fenPlacement"
            )


            return Result(
                pieces =
                    pieces,
                fenPlacement =
                    fenPlacement,
                averageDistance =
                    average,
                weakestMargin =
                    weakestMargin
            )

        } finally {

            squares.forEach {

                try {
                    it.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }


    /**
     * Distance from a current square feature to one saved piece class.
     *
     * We intentionally average a few nearest saved variants when available.
     * That makes an accidental one-template resemblance less likely to win.
     */
    private fun classDistance(
        feature: DoubleArray,
        piece: Char
    ): Double {

        val variants =
            templates[piece]
                ?: return Double.POSITIVE_INFINITY


        if (variants.isEmpty()) {
            return Double.POSITIVE_INFINITY
        }


        val distances =
            variants
                .map {
                        template ->

                    featureDistance(
                        feature,
                        template
                    )
                }
                .sorted()


        val count =
            minOf(
                CLASS_NEAREST_VARIANTS_TO_AVERAGE,
                distances.size
            )


        if (count <= 0) {
            return Double.POSITIVE_INFINITY
        }


        var sum =
            0.0


        for (i in 0 until count) {
            sum +=
                distances[i]
        }


        return sum /
                count
    }


    /**
     * Piece-type distance after color is already known.
     *
     * Use the single best saved variant for this class. This is important for
     * low-variant classes such as rooks/bishops/knights, where averaging both
     * starting-square appearances can make an exact current rook look worse
     * than a one-sample queen class.
     *
     * Score combines:
     * - RGB-relative appearance
     * - foreground silhouette magnitude
     */
    private fun classTypeDistance(
        feature: DoubleArray,
        piece: Char
    ): Double {

        val variants =
            templates[piece]
                ?: return Double.POSITIVE_INFINITY


        if (variants.isEmpty()) {
            return Double.POSITIVE_INFINITY
        }


        var best =
            Double.POSITIVE_INFINITY


        for (template in variants) {

            val rgb =
                featureDistance(
                    feature,
                    template
                )

            val shape =
                shapeDistance(
                    feature,
                    template
                )


            val score =
                TYPE_RGB_WEIGHT * rgb +
                        TYPE_SHAPE_WEIGHT * shape


            if (score < best) {
                best = score
            }
        }


        return best
    }


    /**
     * Compare only the magnitude of "pixel - local background" at each grid
     * point. This preserves the piece silhouette while reducing sensitivity to
     * small theme/highlight color shifts.
     */
    private fun shapeDistance(
        a: DoubleArray,
        b: DoubleArray
    ): Double {

        val triples =
            minOf(
                a.size,
                b.size
            ) /
                    3


        if (triples <= 0) {
            return Double.POSITIVE_INFINITY
        }


        var sum =
            0.0


        for (i in 0 until triples) {

            val base =
                i * 3


            val ar =
                a[base]

            val ag =
                a[base + 1]

            val ab =
                a[base + 2]


            val br =
                b[base]

            val bg =
                b[base + 1]

            val bb =
                b[base + 2]


            val aMagnitude =
                kotlin.math.sqrt(
                    ar * ar +
                            ag * ag +
                            ab * ab
                ) /
                        kotlin.math.sqrt(3.0)


            val bMagnitude =
                kotlin.math.sqrt(
                    br * br +
                            bg * bg +
                            bb * bb
                ) /
                        kotlin.math.sqrt(3.0)


            sum +=
                kotlin.math.abs(
                    aMagnitude -
                            bMagnitude
                )
        }


        return sum /
                triples
    }


    /**
     * Reject obviously impossible reconstructed boards.
     *
     * IMPORTANT:
     * This is independent of the old virtual board.
     */
    private fun validateRecognizedBoard(
        pieces: CharArray
    ): String? {

        if (pieces.size != 64) {
            return "board does not contain 64 squares"
        }


        val whiteKings =
            pieces.count {
                it == 'K'
            }

        val blackKings =
            pieces.count {
                it == 'k'
            }


        if (whiteKings != 1) {
            return "expected exactly 1 white king, found $whiteKings"
        }

        if (blackKings != 1) {
            return "expected exactly 1 black king, found $blackKings"
        }


        val whitePawns =
            pieces.count {
                it == 'P'
            }

        val blackPawns =
            pieces.count {
                it == 'p'
            }


        if (whitePawns > 8) {
            return "too many white pawns: $whitePawns"
        }

        if (blackPawns > 8) {
            return "too many black pawns: $blackPawns"
        }


        val whitePieces =
            pieces.count {
                it.isUpperCase()
            }

        val blackPieces =
            pieces.count {
                it.isLowerCase()
            }


        if (whitePieces > 16) {
            return "too many white pieces: $whitePieces"
        }

        if (blackPieces > 16) {
            return "too many black pieces: $blackPieces"
        }


        /*
         * A settled legal chess position cannot contain an unpromoted pawn on
         * rank 1 or rank 8.
         */
        for (col in 0 until 8) {

            val top =
                pieces[col]

            val bottom =
                pieces[
                    56 +
                            col
                ]


            if (
                top == 'P' ||
                top == 'p'
            ) {
                return "pawn found on rank 8"
            }


            if (
                bottom == 'P' ||
                bottom == 'p'
            ) {
                return "pawn found on rank 1"
            }
        }


        /*
         * Kings cannot occupy adjacent squares in a legal settled position.
         */
        val whiteKingIndex =
            pieces.indexOfFirst {
                it == 'K'
            }

        val blackKingIndex =
            pieces.indexOfFirst {
                it == 'k'
            }


        if (
            whiteKingIndex >= 0 &&
            blackKingIndex >= 0
        ) {

            val whiteRow =
                whiteKingIndex /
                        8

            val whiteCol =
                whiteKingIndex %
                        8

            val blackRow =
                blackKingIndex /
                        8

            val blackCol =
                blackKingIndex %
                        8


            if (
                kotlin.math.abs(
                    whiteRow -
                            blackRow
                ) <= 1 &&
                kotlin.math.abs(
                    whiteCol -
                            blackCol
                ) <= 1
            ) {

                return "white and black kings are adjacent"
            }
        }


        return null
    }


    /**
     * Convert visual 64-square array to only the first FEN field.
     */
    fun toFenPlacement(
        pieces: CharArray
    ): String {

        require(
            pieces.size == 64
        )

        val rows =
            mutableListOf<String>()

        for (row in 0 until 8) {

            var empty =
                0

            val builder =
                StringBuilder()

            for (col in 0 until 8) {

                val piece =
                    pieces[
                        row * 8 +
                                col
                    ]

                if (
                    piece == '.' ||
                    piece == ' '
                ) {

                    empty++

                } else {

                    if (empty > 0) {
                        builder.append(
                            empty
                        )
                        empty = 0
                    }

                    builder.append(
                        piece
                    )
                }
            }

            if (empty > 0) {
                builder.append(
                    empty
                )
            }

            rows.add(
                builder.toString()
            )
        }

        return rows.joinToString("/")
    }


    /**
     * Feature vector relative to local square background.
     *
     * The four corner patches estimate the square background.
     * Each inner-grid RGB sample stores:
     *
     *     pixel RGB - background RGB
     *
     * so a piece silhouette transfers better between light/dark squares.
     */
    private fun extractFeature(
        bitmap: Bitmap
    ): DoubleArray {

        val width =
            bitmap.width

        val height =
            bitmap.height


        val background =
            estimateBackground(
                bitmap
            )


        val left =
            (width * CROP_FRACTION)
                .toInt()
                .coerceIn(
                    0,
                    width - 1
                )

        val right =
            (width * (1.0 - CROP_FRACTION))
                .toInt()
                .coerceIn(
                    left + 1,
                    width
                )

        val top =
            (height * CROP_FRACTION)
                .toInt()
                .coerceIn(
                    0,
                    height - 1
                )

        val bottom =
            (height * (1.0 - CROP_FRACTION))
                .toInt()
                .coerceIn(
                    top + 1,
                    height
                )


        val feature =
            DoubleArray(
                FEATURE_SIZE
            )


        var out =
            0


        for (gy in 0 until FEATURE_GRID) {

            val y =
                (
                        top +
                                (
                                        gy +
                                                0.5
                                        ) *
                                (
                                        bottom -
                                                top
                                        ) /
                                FEATURE_GRID
                        )
                    .toInt()
                    .coerceIn(
                        0,
                        height - 1
                    )


            for (gx in 0 until FEATURE_GRID) {

                val x =
                    (
                            left +
                                    (
                                            gx +
                                                    0.5
                                            ) *
                                    (
                                            right -
                                                    left
                                            ) /
                                    FEATURE_GRID
                            )
                        .toInt()
                        .coerceIn(
                            0,
                            width - 1
                        )


                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )


                val r =
                    (pixel shr 16) and
                            0xFF

                val g =
                    (pixel shr 8) and
                            0xFF

                val b =
                    pixel and
                            0xFF


                feature[out++] =
                    r -
                            background[0]

                feature[out++] =
                    g -
                            background[1]

                feature[out++] =
                    b -
                            background[2]
            }
        }


        return feature
    }


    private fun estimateBackground(
        bitmap: Bitmap
    ): DoubleArray {

        val width =
            bitmap.width

        val height =
            bitmap.height


        val patchW =
            maxOf(
                2,
                (width * 0.12).toInt()
            )

        val patchH =
            maxOf(
                2,
                (height * 0.12).toInt()
            )


        var rSum =
            0.0

        var gSum =
            0.0

        var bSum =
            0.0

        var count =
            0


        fun samplePatch(
            startX: Int,
            startY: Int
        ) {

            val endX =
                minOf(
                    width,
                    startX +
                            patchW
                )

            val endY =
                minOf(
                    height,
                    startY +
                            patchH
                )


            for (
            y in startY until endY step 2
            ) {

                for (
                x in startX until endX step 2
                ) {

                    val pixel =
                        bitmap.getPixel(
                            x,
                            y
                        )

                    rSum +=
                        (
                                pixel shr 16
                                ) and
                                0xFF

                    gSum +=
                        (
                                pixel shr 8
                                ) and
                                0xFF

                    bSum +=
                        pixel and
                                0xFF

                    count++
                }
            }
        }


        samplePatch(
            0,
            0
        )

        samplePatch(
            width -
                    patchW,
            0
        )

        samplePatch(
            0,
            height -
                    patchH
        )

        samplePatch(
            width -
                    patchW,
            height -
                    patchH
        )


        if (count <= 0) {
            return doubleArrayOf(
                0.0,
                0.0,
                0.0
            )
        }


        return doubleArrayOf(
            rSum / count,
            gSum / count,
            bSum / count
        )
    }


    private fun featureDistance(
        a: DoubleArray,
        b: DoubleArray
    ): Double {

        val size =
            minOf(
                a.size,
                b.size
            )

        if (size <= 0) {
            return Double.POSITIVE_INFINITY
        }


        var sum =
            0.0


        for (i in 0 until size) {

            sum +=
                abs(
                    a[i] -
                            b[i]
                )
        }


        return sum /
                size
    }


    private fun format(
        value: Double
    ): String =
        String.format(
            "%.2f",
            value
        )
}
