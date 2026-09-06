
package com.example.chessai
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kotlin.math.abs
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc


data class DetectedMove(
    val fromIdx: Int,
    val toIdx: Int,
    val isUserMove: Boolean
)


object ChessBoardUtils {

    enum class DetectionOutcome {
        MOVE_FOUND,
        NO_CHANGE,
        UNRESOLVED
    }

    @Volatile
    var lastDetectionOutcome: DetectionOutcome =
        DetectionOutcome.NO_CHANGE
        private set


    @Volatile
    var lastMeaningfulChangedIndices: Set<Int> =
        emptySet()
        private set


    private const val TAG = "ChessAI_MoveDetection"

    /*
     * A normal chess move needs at least two visual square transitions:
     * source + destination.
     *
     * A single changed square is treated as transient UI / incomplete
     * animation evidence and is NEVER enough to alter the FEN.
     */
    private const val MIN_CREDIBLE_MOVE_SQUARES = 2

    private const val OPENING_TAG = "ChessAI_Opening"

    private const val SIGNIFICANT_CHANGE_THRESHOLD = 2200.0
    private const val MIN_CENTER_CHANGE = 900.0
    private const val MIN_MOVE_SCORE = 2200.0
    private const val AMBIGUITY_RATIO = 0.08

    /*
     * When two candidates move the SAME virtual piece to different
     * destinations, be much more conservative than the generic ambiguity
     * test.
     *
     * This specifically protects positions such as:
     *
     *   king f8
     *   candidate f8->e7 (real capture)
     *   candidate f8->f7 (ghost/UI change on another occupied square)
     *
     * Both can be pseudo-legal and both destinations can still look
     * "occupied", so the source-empty occupancy test alone cannot choose.
     *
     * The accepted destination must therefore have clearly stronger visual
     * replacement evidence than every other destination for that SAME piece.
     */
    private const val SAME_SOURCE_MIN_DESTINATION_LEAD = 0.22

    /*
     * Kings get an even stricter requirement because several one-square
     * captures can be geometrically legal at once and a wrong king move
     * immediately corrupts the FEN.
     */
    private const val KING_SAME_SOURCE_MIN_DESTINATION_LEAD = 0.35

    private const val RESIDUE_SOURCE_DEPARTURE_BONUS = 5000.0

    /*
     * FAST-NEXT-PLY CAPTURE FALLBACK
     *
     * A very fast reply can overlap the previous accepted frame.
     *
     * Example:
     *
     *   ...Bxf3
     *   exf3 immediately
     *
     * The previous move may patch f3 after White's pawn is already on f3.
     * Then the next comparison sees:
     *
     *   e2 -> EMPTY   (strong evidence)
     *   f3 -> still OCCUPIED, but little/no old-vs-new difference
     *
     * Normal changed-square detection can therefore miss the capture
     * destination entirely.
     *
     * This fallback is intentionally narrow:
     *   - normal detection must have produced ZERO candidates
     *   - source must have strong visual change
     *   - source must visually be EMPTY now
     *   - destination must already contain an ENEMY piece virtually
     *   - move trajectory must be legal
     *   - destination must visually still be OCCUPIED
     *
     * It advances exactly one virtual ply and does not do catch-up.
     */
    private const val SOURCE_DRIVEN_CAPTURE_BONUS = 14000.0
    /*
     * UI hint classification.
     *
     * Chess sites often draw legal-move dots/circles on empty squares.
     * Those can create a strong CENTER difference even though no piece
     * moved there.
     *
     * We do NOT discard those squares outright. Instead, we classify
     * likely UI-only changes and give them almost no "unexplained move"
     * penalty. This keeps real move detection strict while preventing
     * legal-move circles from poisoning a correct candidate.
     */
    private const val PIXEL_CHANGE_DELTA = 55
    private const val UI_HINT_MAX_CHANGED_RATIO = 0.16
    private const val UI_HINT_CENTER_EDGE_RATIO = 1.35
    private const val UI_HINT_EXTRA_PENALTY_SCALE = 0.12

    /*
     * Recovery mode is intentionally more tolerant than normal detection.
     * It is only called after normal detection has already rejected a
     * stable board and ScreenRecordService has waited about one second.
     */

    /*
     * Recovery is allowed to tolerate a few noisy PIECE_LIKE squares,
     * but not an entire later board position. If a candidate leaves more
     * than this many PIECE_LIKE changed squares unexplained, recovery
     * rejects it instead of silently skipping plies.
     */

    /*
     * HYBRID OCCUPANCY CHECK
     *
     * Pixel difference only says "this square changed".
     * It does not prove that a piece actually left or arrived.
     *
     * We therefore compare candidate source/destination squares with
     * CURRENT empty squares of the same board color. This adapts to the
     * active Chess.com board theme and is resistant to a piece merely
     * wiggling/scaling when clicked.
     */
    private const val OCCUPANCY_EMPTY_TOLERANCE = 10.0
    private const val OCCUPANCY_PRESENT_MARGIN = 4.0
    private const val MIN_EMPTY_REFERENCES = 2


    // =====================================================================
    // ORIENTATION
    // =====================================================================

    /**
     * VirtualChessBoard always uses:
     *
     * 0  = a8
     * ...
     * 63 = h1
     *
     * If Black is at the bottom of the screen, the physical board is
     * rotated 180 degrees. Rotate the bitmap so ChessBoardUtils always
     * works in standard chess coordinates.
     */
    fun normalizeBoardOrientation(
        boardBitmap: Bitmap,
        userIsWhite: Boolean
    ): Bitmap {

        if (userIsWhite) {
            return boardBitmap.copy(
                Bitmap.Config.ARGB_8888,
                false
            )
        }

        val matrix = Matrix().apply {
            postRotate(180f)
        }

        return Bitmap.createBitmap(
            boardBitmap,
            0,
            0,
            boardBitmap.width,
            boardBitmap.height,
            matrix,
            true
        )
    }


    // =====================================================================
    // BASIC BOARD UTILITIES
    // =====================================================================

    fun indexToSquareName(index: Int): String {

        if (index !in 0..63) {
            return "??"
        }

        val file = 'a' + (index % 8)
        val rank = 8 - (index / 8)

        return "$file$rank"
    }


    fun sliceBoardIntoSquares(
        boardBitmap: Bitmap
    ): ArrayList<Bitmap> {

        val squares = ArrayList<Bitmap>(64)

        val squareWidth =
            boardBitmap.width / 8

        val squareHeight =
            boardBitmap.height / 8

        for (row in 0 until 8) {

            for (col in 0 until 8) {

                squares.add(
                    Bitmap.createBitmap(
                        boardBitmap,
                        col * squareWidth,
                        row * squareHeight,
                        squareWidth,
                        squareHeight
                    )
                )
            }
        }

        return squares
    }



    // =====================================================================
    // SINGLE-PLY SETTLED-BOARD RECOVERY
    // =====================================================================
    //
    // This is deliberately NOT a second move detector running every frame.
    // ScreenRecordService calls it only after the normal 3-frame-stable
    // detector returns UNRESOLVED.
    //
    // Purpose:
    // If a very fast move transition was never sampled, compare the final
    // settled board against the last accepted official baseline and ask:
    // "Which ONE currently legal move best explains what is on screen now?"
    //
    // Safety:
    // - exactly one ply only
    // - legal moves come from VirtualChessBoard
    // - source must now look empty
    // - destination must now look occupied
    // - source/destination still need meaningful visual evidence
    // - ambiguous results are rejected
    // - live VirtualChessBoard is never modified here
    //
    fun recoverSingleLegalMoveFromSettledBoard(
        oldBoard: Bitmap,
        newBoard: Bitmap,
        virtualBoard: VirtualChessBoard
    ): DetectedMove? {

        val oldSquares =
            sliceBoardIntoSquares(oldBoard)

        val newSquares =
            sliceBoardIntoSquares(newBoard)

        data class RecoveryCandidate(
            val fromIdx: Int,
            val toIdx: Int,
            val score: Double,
            val isUserMove: Boolean
        )

        try {
            val legalMoves =
                virtualBoard
                    .getAllLegalMovesUci()
                    .mapNotNull { uci ->
                        if (uci.length < 4) {
                            null
                        } else {
                            val from =
                                squareNameToIndexForRecovery(
                                    uci.substring(0, 2)
                                )
                            val to =
                                squareNameToIndexForRecovery(
                                    uci.substring(2, 4)
                                )
                            if (from == null || to == null) null
                            else from to to
                        }
                    }
                    .distinct()

            if (legalMoves.isEmpty()) {
                return null
            }

            val candidates =
                mutableListOf<RecoveryCandidate>()

            for ((fromIdx, toIdx) in legalMoves) {

                val movedPiece =
                    virtualBoard.getPieceAt(fromIdx)
                        ?: continue

                /*
                 * The completed move must leave its source empty and its
                 * destination occupied in the CURRENT settled screenshot.
                 */
                /*
                 * Recovery-only source-empty check.
                 *
                 * The overlay inpainting can leave a very small amount of
                 * visual residue on a genuinely vacated source square.
                 * Normal detection remains strict; ONLY this settled-board
                 * fallback gets a small +8.0 tolerance.
                 *
                 * Example from the failing phone log:
                 *   real c5 source: 16.1 vs empty threshold 12.0 -> accept
                 *   occupied c6:   291.9 vs empty threshold 10.8 -> reject
                 */
                val recoveryExclude =
                    setOf(
                        fromIdx,
                        toIdx
                    )

                val recoverySourceThreshold =
                    emptyPresenceThreshold(
                        targetIndex = fromIdx,
                        squares = newSquares,
                        virtualBoard = virtualBoard,
                        exclude = recoveryExclude
                    )
                        ?: continue

                val recoverySourcePresence =
                    piecePresenceScore(
                        newSquares[fromIdx]
                    )

                val sourceLooksEmpty =
                    recoverySourcePresence <=
                            recoverySourceThreshold + 8.0

                if (!sourceLooksEmpty) {
                    continue
                }

                val destinationLooksOccupied =
                    isSquareVisuallyOccupied(
                        index = toIdx,
                        squares = newSquares,
                        virtualBoard = virtualBoard,
                        exclude = setOf(fromIdx, toIdx)
                    )

                if (!destinationLooksOccupied) {
                    continue
                }

                val sourceChange =
                    getSquareChange(
                        oldSquares[fromIdx],
                        newSquares[fromIdx]
                    )

                val destinationChange =
                    getSquareChange(
                        oldSquares[toIdx],
                        newSquares[toIdx]
                    )

                /*
                 * A missed transition can weaken one side of the evidence,
                 * especially after overlay inpainting, but a real completed
                 * move should still leave meaningful evidence at source or
                 * destination. Requiring both to be totally unchanged would
                 * make this a guess, so reject that.
                 */
                val sourceMeaningful =
                    sourceChange.total >=
                            SIGNIFICANT_CHANGE_THRESHOLD * 0.55 ||
                            sourceChange.center >=
                            MIN_CENTER_CHANGE * 0.70

                val destinationMeaningful =
                    destinationChange.total >=
                            SIGNIFICANT_CHANGE_THRESHOLD * 0.55 ||
                            destinationChange.center >=
                            MIN_CENTER_CHANGE * 0.70

                if (
                    !sourceMeaningful &&
                    !destinationMeaningful
                ) {
                    continue
                }

                /*
                 * IMPORTANT WATCHDOG RULE:
                 *
                 * Do NOT require old-vs-new destination difference for a quiet
                 * move to a virtually empty square.
                 *
                 * The overlay/inpainting path can make the destination's raw
                 * difference weak even when a piece is clearly present there.
                 * That is exactly what happened in the real Qd8->c7 miss:
                 * d8 strongly changed, but c7 did not cross the generic visual
                 * change threshold.
                 *
                 * We already proved:
                 *   - this move is legal for the CURRENT side-to-move
                 *   - the source now looks empty
                 *   - the destination now looks occupied
                 *
                 * Therefore, for recovery only, a strong source disappearance
                 * may carry the move even when destination delta is weak.
                 *
                 * We still reject if BOTH source and destination evidence are
                 * weak, and ambiguity handling below prevents guessing between
                 * multiple legal occupied destinations.
                 */
                val targetBefore =
                    virtualBoard.getPieceAt(toIdx)

                val sourceStrongEnoughForQuietRecovery =
                    sourceChange.total >=
                            SIGNIFICANT_CHANGE_THRESHOLD * 0.85 ||
                            sourceChange.center >=
                            MIN_CENTER_CHANGE * 0.90

                if (
                    targetBefore == null &&
                    !destinationMeaningful &&
                    !sourceStrongEnoughForQuietRecovery
                ) {
                    continue
                }

                val score =
                    calculatePieceEvidence(sourceChange) * 1.55 +
                            sourceChange.total * 0.45 +
                            calculateDestinationVisualStrength(destinationChange) * 0.80 +
                            destinationChange.total * 0.15 +
                            if (
                                targetBefore == null &&
                                !destinationMeaningful &&
                                sourceStrongEnoughForQuietRecovery
                            ) {
                                5000.0
                            } else {
                                0.0
                            }

                Log.d(
                    TAG,
                    "SETTLED_RECOVERY_CANDIDATE " +
                            "${indexToSquareName(fromIdx)}->" +
                            "${indexToSquareName(toIdx)} " +
                            "srcTotal=${format(sourceChange.total)} " +
                            "dstTotal=${format(destinationChange.total)} " +
                            "dstMeaningful=$destinationMeaningful " +
                            "targetBefore=${targetBefore ?: '.'} " +
                            "score=${format(score)}"
                )

                val isUserMove =
                    if (virtualBoard.isUserWhite) {
                        movedPiece.isUpperCase()
                    } else {
                        !movedPiece.isUpperCase()
                    }

                candidates.add(
                    RecoveryCandidate(
                        fromIdx = fromIdx,
                        toIdx = toIdx,
                        score = score,
                        isUserMove = isUserMove
                    )
                )
            }

            if (candidates.isEmpty()) {
                Log.d(
                    TAG,
                    "SETTLED_RECOVERY_REJECT reason=NO_LEGAL_OCCUPANCY_MATCH"
                )
                return null
            }

            val sorted =
                candidates.sortedByDescending {
                    it.score
                }

            val best =
                sorted.first()

            /*
             * Unique candidate is safest and is the common fast-move case.
             */
            if (sorted.size == 1) {
                lastDetectionOutcome =
                    DetectionOutcome.MOVE_FOUND

                Log.d(
                    TAG,
                    "SETTLED_RECOVERY_ACCEPT " +
                            "${indexToSquareName(best.fromIdx)}->" +
                            "${indexToSquareName(best.toIdx)} " +
                            "reason=UNIQUE_LEGAL_SETTLED_MATCH " +
                            "score=${format(best.score)}"
                )

                return DetectedMove(
                    fromIdx = best.fromIdx,
                    toIdx = best.toIdx,
                    isUserMove = best.isUserMove
                )
            }

            val second =
                sorted[1]

            val lead =
                if (best.score > 0.0) {
                    (best.score - second.score) /
                            best.score
                } else {
                    0.0
                }

            /*
             * Do not guess between close legal alternatives. A 30% lead is
             * intentionally stricter than normal detection because this path
             * exists only as a fallback.
             */
            if (lead < 0.30) {
                Log.d(
                    TAG,
                    "SETTLED_RECOVERY_REJECT reason=AMBIGUOUS " +
                            "best=${indexToSquareName(best.fromIdx)}->" +
                            "${indexToSquareName(best.toIdx)}:${format(best.score)} " +
                            "second=${indexToSquareName(second.fromIdx)}->" +
                            "${indexToSquareName(second.toIdx)}:${format(second.score)} " +
                            "lead=${format(lead * 100.0)}%"
                )
                return null
            }

            lastDetectionOutcome =
                DetectionOutcome.MOVE_FOUND

            Log.d(
                TAG,
                "SETTLED_RECOVERY_ACCEPT " +
                        "${indexToSquareName(best.fromIdx)}->" +
                        "${indexToSquareName(best.toIdx)} " +
                        "reason=CLEAR_LEGAL_SETTLED_LEAD " +
                        "lead=${format(lead * 100.0)}%"
            )

            return DetectedMove(
                fromIdx = best.fromIdx,
                toIdx = best.toIdx,
                isUserMove = best.isUserMove
            )

        } finally {
            oldSquares.forEach {
                if (!it.isRecycled) it.recycle()
            }
            newSquares.forEach {
                if (!it.isRecycled) it.recycle()
            }
        }
    }


    private fun squareNameToIndexForRecovery(
        square: String
    ): Int? {

        if (square.length != 2) {
            return null
        }

        val file =
            square[0]

        val rank =
            square[1]

        if (
            file !in 'a'..'h' ||
            rank !in '1'..'8'
        ) {
            return null
        }

        val col =
            file - 'a'

        val row =
            8 - rank.digitToInt()

        return row * 8 + col
    }


    // =====================================================================
    // OPENING
    // =====================================================================

    fun detectOpeningMoveIfAlreadyMade(
        standardBitmap: Bitmap,
        currentBitmap: Bitmap,
        virtualBoard: VirtualChessBoard
    ): DetectedMove? {

        return try {

            val move =
                detectMoveFromFrames(
                    oldBoard = standardBitmap,
                    newBoard = currentBitmap,
                    virtualBoard = virtualBoard
                )

            if (move == null) {
                return null
            }

            val isWhiteOpening =
                move.fromIdx in 48..63 &&
                        virtualBoard
                            .getPieceAt(move.fromIdx)
                            ?.isUpperCase() == true

            if (!isWhiteOpening) {
                return null
            }

            Log.d(
                OPENING_TAG,
                "Detected opening move: " +
                        "${indexToSquareName(move.fromIdx)} -> " +
                        indexToSquareName(move.toIdx)
            )

            DetectedMove(
                fromIdx = move.fromIdx,
                toIdx = move.toIdx,
                isUserMove = false
            )

        } catch (e: Exception) {

            Log.e(
                OPENING_TAG,
                "Opening move detection failed",
                e
            )

            null
        }
    }


    // =====================================================================
    // MAIN MOVE DETECTION
    // =====================================================================

    fun detectMoveFromFrames(
        oldBoard: Bitmap,
        newBoard: Bitmap,
        virtualBoard: VirtualChessBoard,
        persistentResidueSquares: Set<Int> = emptySet()
    ): DetectedMove? {

        /*
         * Default to unresolved for this analysis pass. Specific early
         * returns below overwrite this with NO_CHANGE when appropriate.
         */
        lastDetectionOutcome =
            DetectionOutcome.UNRESOLVED

        lastMeaningfulChangedIndices =
            emptySet()

        val oldSquares =
            sliceBoardIntoSquares(oldBoard)

        val newSquares =
            sliceBoardIntoSquares(newBoard)

        try {

            val changes =
                HashMap<Int, SquareChange>()


            // -------------------------------------------------------------
            // CALCULATE ALL 64 SQUARE DIFFERENCES
            // -------------------------------------------------------------

            for (i in 0 until 64) {

                changes[i] =
                    getSquareChange(
                        oldSquares[i],
                        newSquares[i]
                    )
            }


            val changedIndices =
                changes
                    .filter { (_, change) ->

                        change.total >=
                                SIGNIFICANT_CHANGE_THRESHOLD
                    }
                    .keys
                    .sorted()
                    .toMutableList()


            lastMeaningfulChangedIndices =
                changedIndices.toSet()


            /* Compact diagnostic summary. */
            val changedSummary =
                changedIndices.joinToString(",") { index ->
                    val total = changes[index]?.total ?: 0.0
                    val kind = changes[index]?.kind ?: ChangeKind.WEAK
                    "${indexToSquareName(index)}:${format(total)}:${kind.name}"
                }

            if (changedIndices.isEmpty()) {

                lastMeaningfulChangedIndices =
                    emptySet()

                lastDetectionOutcome =
                    DetectionOutcome.NO_CHANGE

                val strongest = changes.maxByOrNull { it.value.total }

                if (strongest != null && strongest.value.total > 250.0) {
                    Log.d(
                        TAG,
                        "REJECT active=${virtualBoard.getActiveColor()} " +
                                "reason=NO_CHANGE_ABOVE_THRESHOLD " +
                                "top=${indexToSquareName(strongest.key)}:${format(strongest.value.total)}"
                    )
                }

                return null
            }

            /*
             * One changed square cannot prove a chess move.
             *
             * This specifically handles:
             * - a clicked/wiggling piece
             * - source-only animation
             * - a residual rook square after castling
             *
             * Keep the old baseline and simply inspect the next settled
             * frame. The FEN remains live and authoritative.
             */
            if (
                changedIndices.size <
                MIN_CREDIBLE_MOVE_SQUARES
            ) {

                lastDetectionOutcome =
                    DetectionOutcome.UNRESOLVED

                val only =
                    changedIndices.joinToString(",") { index ->
                        val change = changes[index]
                        "${indexToSquareName(index)}:" +
                                "${change?.let { format(it.total) } ?: "0.0"}:" +
                                "${change?.kind?.name ?: "UNKNOWN"}"
                    }

                Log.d(
                    TAG,
                    "REJECT active=${virtualBoard.getActiveColor()} " +
                            "reason=SINGLE_SQUARE_TRANSIENT changed=[$only]"
                )

                return null
            }


            Log.d(
                TAG,
                "ANALYZE active=${virtualBoard.getActiveColor()} changed=[$changedSummary]"
            )


            // =============================================================
            // CASTLING
            // =============================================================

            val castlingMove =
                detectCastlingMove(
                    changedIndices = changedIndices,
                    changes = changes,
                    newSquares = newSquares,
                    virtualBoard = virtualBoard,
                    persistentResidueSquares =
                        persistentResidueSquares
                )


            if (castlingMove != null) {

                lastDetectionOutcome =
                    DetectionOutcome.MOVE_FOUND

                Log.d(
                    TAG,
                    "CASTLE ${indexToSquareName(castlingMove.fromIdx)}->${indexToSquareName(castlingMove.toIdx)}"
                )
                return castlingMove
            }


            val activeColor =
                virtualBoard.getActiveColor()


            val candidates =
                mutableListOf<MoveCandidate>()


            // =============================================================
            // FIND ACTIVE-SIDE SOURCE PIECES
            // =============================================================

            val possibleSources =
                (0 until 64).filter { index ->

                    val piece =
                        virtualBoard.getPieceAt(index)
                            ?: return@filter false

                    virtualBoard.isPieceOnActiveTurn(piece)
                }


            // =============================================================
            // TEST CANDIDATES
            // =============================================================

            for (fromIdx in possibleSources) {

                val piece =
                    virtualBoard.getPieceAt(fromIdx)
                        ?: continue


                val sourceChange =
                    changes[fromIdx]
                        ?: continue

                val sourceHasNormalVisualChange =
                    sourceChange.center >=
                            MIN_CENTER_CHANGE

                /*
                 * Persistent residue can make a later REAL departure from
                 * this source look weak relative to the old official
                 * baseline. If the virtual board still has the active piece
                 * here but the current square now visually looks empty,
                 * occupancy supplies the missing source evidence.
                 */
                val residueSourceNowLooksEmpty =
                    fromIdx in
                            persistentResidueSquares &&
                            isSquareVisuallyEmpty(
                                index = fromIdx,
                                squares = newSquares,
                                virtualBoard = virtualBoard,
                                exclude = setOf(fromIdx)
                            )

                if (
                    !sourceHasNormalVisualChange &&
                    !residueSourceNowLooksEmpty
                ) {
                    continue
                }



                for (toIdx in changedIndices) {

                    if (fromIdx == toIdx) {
                        continue
                    }


                    val destinationChange =
                        changes[toIdx]
                            ?: continue


                    // -----------------------------------------------------
                    // DESTINATION CENTER CHECK
                    // -----------------------------------------------------

                    if (
                        destinationChange.center <
                        MIN_CENTER_CHANGE
                    ) {
                        continue
                    }


                    // -----------------------------------------------------
                    // TRAJECTORY
                    // -----------------------------------------------------

                    if (
                        !isValidPieceTrajectory(
                            piece = piece,
                            fromIdx = fromIdx,
                            toIdx = toIdx,
                            virtualBoard = virtualBoard
                        )
                    ) {
                        continue
                    }


                    // -----------------------------------------------------
                    // HYBRID OCCUPANCY CHECK
                    // -----------------------------------------------------
                    //
                    // A click animation can make a piece square look very
                    // different even though the piece never left it.
                    //
                    // For a real move the source should visually become
                    // empty. For a move to an empty destination, that
                    // destination should change from empty -> occupied.
                    //
                    // Captures are handled as occupied -> occupied on the
                    // destination, while still requiring source -> empty.
                    val occupancy =
                        evaluateCandidateOccupancy(
                            fromIdx = fromIdx,
                            toIdx = toIdx,
                            oldSquares = oldSquares,
                            newSquares = newSquares,
                            virtualBoard = virtualBoard
                        )

                    if (!occupancy.passes) {

                        Log.d(
                            TAG,
                            "OCCUPANCY_REJECT " +
                                    "${indexToSquareName(fromIdx)}->${indexToSquareName(toIdx)} " +
                                    occupancy.reason
                        )

                        continue
                    }



                    // -----------------------------------------------------
                    // SCORE
                    // -----------------------------------------------------

                    var score =
                        scoreCandidateMove(
                            fromIdx = fromIdx,
                            toIdx = toIdx,
                            changes = changes,
                            significantIndices = changedIndices,
                            virtualBoard = virtualBoard,
                            persistentResidueSquares =
                                persistentResidueSquares
                        )

                    if (residueSourceNowLooksEmpty) {
                        score +=
                            RESIDUE_SOURCE_DEPARTURE_BONUS
                    }


                    if (score < MIN_MOVE_SCORE) {
                        continue
                    }


                    // -----------------------------------------------------
                    // UNEXPLAINED CHANGES
                    // -----------------------------------------------------
                    //
                    // IMPORTANT:
                    //
                    // Extra changed squares no longer HARD-REJECT a move.
                    //
                    // At this point the candidate has already passed:
                    //   - active-side piece check
                    //   - legal chess trajectory
                    //   - hybrid occupancy verification
                    //   - minimum visual score
                    //
                    // Therefore a real move such as c1->f4 must not be
                    // discarded just because e7/e6 or an old visual residue
                    // is also visible in the same settled screenshot.
                    //
                    // Extras are still useful:
                    //   - scoreCandidateMove() already penalizes them
                    //   - we keep their count for logging
                    //   - fewer extras remain a secondary sort preference
                    //
                    // The FEN still advances by EXACTLY ONE detected ply.
                    // ScreenRecordService patches only that accepted move's
                    // squares into the official baseline, so unrelated extras
                    // remain available to be detected on the next scan.
                    val unexplained =
                        countUnexplainedSquares(
                            fromIdx = fromIdx,
                            toIdx = toIdx,
                            changedIndices = changedIndices,
                            changes = changes,
                            persistentResidueSquares =
                                persistentResidueSquares
                        )


                    val isPieceWhite =
                        piece.isUpperCase()


                    val isUserMove =
                        if (virtualBoard.isUserWhite) {

                            isPieceWhite

                        } else {

                            !isPieceWhite
                        }


                    val destinationVisualStrength =
                        calculateDestinationVisualStrength(
                            changes[toIdx]
                                ?: continue
                        )


                    val candidate =
                        MoveCandidate(
                            fromIdx = fromIdx,
                            toIdx = toIdx,
                            isUserMove = isUserMove,
                            score = score,
                            unexplainedSignificantSquares = unexplained,
                            destinationVisualStrength = destinationVisualStrength
                        )


                    candidates.add(candidate)
                }
            }


            // =============================================================
            // SOURCE-DRIVEN OCCUPIED->OCCUPIED CAPTURE FALLBACK
            // =============================================================
            //
            // Run ONLY when normal changed-destination detection found
            // nothing.
            //
            // This solves the fast-overlap case where the previous accepted
            // move patched a square after the NEXT capture had already placed
            // a new piece on that same square.
            //
            // We do NOT require the destination to be inside changedIndices.
            // Instead, the source disappearance + virtual legality + current
            // destination occupancy must jointly prove the capture.
            if (candidates.isEmpty()) {

                val fallbackCaptures =
                    mutableListOf<MoveCandidate>()


                for (fromIdx in possibleSources) {

                    val piece =
                        virtualBoard.getPieceAt(fromIdx)
                            ?: continue


                    val sourceChange =
                        changes[fromIdx]
                            ?: continue


                    /*
                     * Do not infer a move from a weak/unchanged source.
                     * We need strong evidence that THIS virtual piece actually
                     * disappeared from its square.
                     */
                    if (
                        sourceChange.center <
                        MIN_CENTER_CHANGE
                    ) {
                        continue
                    }


                    val sourceLooksEmpty =
                        isSquareVisuallyEmpty(
                            index = fromIdx,
                            squares = newSquares,
                            virtualBoard = virtualBoard,
                            exclude = setOf(fromIdx)
                        )


                    if (!sourceLooksEmpty) {
                        continue
                    }


                    for (toIdx in 0 until 64) {

                        if (toIdx == fromIdx) {
                            continue
                        }

                        /*
                         * Normal detection already examined changed
                         * destinations. This fallback exists specifically for
                         * a destination that was visually absorbed into the
                         * previous baseline.
                         */
                        if (toIdx in changedIndices) {
                            continue
                        }


                        val targetPiece =
                            virtualBoard.getPieceAt(toIdx)
                                ?: continue


                        /*
                         * This fallback is capture-only.
                         */
                        if (
                            targetPiece.isUpperCase() ==
                            piece.isUpperCase()
                        ) {
                            continue
                        }


                        if (
                            !isValidPieceTrajectory(
                                piece = piece,
                                fromIdx = fromIdx,
                                toIdx = toIdx,
                                virtualBoard = virtualBoard
                            )
                        ) {
                            continue
                        }


                        /*
                         * Reuse the same occupancy gate.
                         *
                         * For a capture this requires:
                         *   source AFTER = visually empty
                         *   destination AFTER = visually occupied
                         *
                         * It intentionally does NOT require destination
                         * old-vs-new change, because that is the evidence that
                         * can be lost in the fast next-ply overlap case.
                         */
                        val occupancy =
                            evaluateCandidateOccupancy(
                                fromIdx = fromIdx,
                                toIdx = toIdx,
                                oldSquares = oldSquares,
                                newSquares = newSquares,
                                virtualBoard = virtualBoard
                            )


                        if (!occupancy.passes) {
                            continue
                        }


                        val destinationChange =
                            changes[toIdx]
                                ?: continue


                        val sourceEvidence =
                            calculatePieceEvidence(
                                sourceChange
                            )


                        val destinationEvidence =
                            calculateDestinationVisualStrength(
                                destinationChange
                            )


                        /*
                         * Source disappearance carries most of the score.
                         * Destination difference can legitimately be tiny.
                         */
                        val fallbackScore =
                            SOURCE_DRIVEN_CAPTURE_BONUS +
                                    sourceEvidence * 1.60 +
                                    sourceChange.total * 0.30 +
                                    destinationEvidence * 0.20


                        val isPieceWhite =
                            piece.isUpperCase()


                        val isUserMove =
                            if (virtualBoard.isUserWhite) {
                                isPieceWhite
                            } else {
                                !isPieceWhite
                            }


                        val fallbackCandidate =
                            MoveCandidate(
                                fromIdx = fromIdx,
                                toIdx = toIdx,
                                isUserMove = isUserMove,
                                score = fallbackScore,
                                unexplainedSignificantSquares =
                                    countUnexplainedSquares(
                                        fromIdx = fromIdx,
                                        toIdx = toIdx,
                                        changedIndices = changedIndices,
                                        changes = changes,
                                        persistentResidueSquares =
                                            persistentResidueSquares
                                    ),
                                destinationVisualStrength =
                                    destinationEvidence
                            )


                        fallbackCaptures.add(
                            fallbackCandidate
                        )


                        Log.d(
                            TAG,
                            "SOURCE_DRIVEN_CAPTURE_CANDIDATE " +
                                    "${indexToSquareName(fromIdx)}->" +
                                    "${indexToSquareName(toIdx)} " +
                                    "srcCenter=${format(sourceChange.center)} " +
                                    "dstTotal=${format(destinationChange.total)} " +
                                    "score=${format(fallbackScore)}"
                        )
                    }
                }


                if (fallbackCaptures.size == 1) {

                    val only =
                        fallbackCaptures.first()

                    lastDetectionOutcome =
                        DetectionOutcome.MOVE_FOUND

                    Log.d(
                        TAG,
                        "SOURCE_DRIVEN_CAPTURE_ACCEPT " +
                                "${indexToSquareName(only.fromIdx)}->" +
                                "${indexToSquareName(only.toIdx)} " +
                                "reason=UNIQUE_LEGAL_OCCUPIED_DESTINATION " +
                                "score=${format(only.score)}"
                    )

                    return DetectedMove(
                        fromIdx = only.fromIdx,
                        toIdx = only.toIdx,
                        isUserMove = only.isUserMove
                    )
                }


                if (fallbackCaptures.size > 1) {

                    val sortedFallback =
                        fallbackCaptures.sortedByDescending {
                            it.score
                        }


                    val best =
                        sortedFallback[0]

                    val second =
                        sortedFallback[1]


                    val lead =
                        if (best.score > 0.0) {
                            (best.score - second.score) /
                                    best.score
                        } else {
                            0.0
                        }


                    /*
                     * If several captures remain possible, stay conservative.
                     *
                     * Only accept when one candidate is clearly stronger.
                     * Otherwise keep the FEN unchanged and wait for more
                     * evidence.
                     */
                    if (lead >= SAME_SOURCE_MIN_DESTINATION_LEAD) {

                        lastDetectionOutcome =
                            DetectionOutcome.MOVE_FOUND

                        Log.d(
                            TAG,
                            "SOURCE_DRIVEN_CAPTURE_ACCEPT " +
                                    "${indexToSquareName(best.fromIdx)}->" +
                                    "${indexToSquareName(best.toIdx)} " +
                                    "reason=CLEAR_LEAD " +
                                    "lead=${format(lead * 100.0)}%"
                        )

                        return DetectedMove(
                            fromIdx = best.fromIdx,
                            toIdx = best.toIdx,
                            isUserMove = best.isUserMove
                        )

                    } else {

                        Log.d(
                            TAG,
                            "SOURCE_DRIVEN_CAPTURE_REJECT " +
                                    "reason=AMBIGUOUS " +
                                    "best=${indexToSquareName(best.fromIdx)}->" +
                                    "${indexToSquareName(best.toIdx)} " +
                                    "second=${indexToSquareName(second.fromIdx)}->" +
                                    "${indexToSquareName(second.toIdx)} " +
                                    "lead=${format(lead * 100.0)}%"
                        )
                    }
                }
            }


            // =============================================================
            // NOTHING SURVIVED
            // =============================================================

            if (candidates.isEmpty()) {
                Log.d(
                    TAG,
                    "REJECT active=$activeColor reason=NO_LEGAL_CANDIDATE changed=[$changedSummary]"
                )
                return null
            }


            // =============================================================
            // SORT
            // =============================================================

            val validCandidates =
                candidates.sortedWith(

                    compareByDescending<MoveCandidate> {
                        it.score
                    }.thenBy {
                        it.unexplainedSignificantSquares
                    }
                )


            val bestMove =
                validCandidates.first()


            // =============================================================
            // SMART SAME-SOURCE AMBIGUITY
            // =============================================================
            //
            // Generic score ambiguity is not enough when the SAME piece can
            // apparently move to multiple changed destinations.
            //
            // Example from the long human test:
            //
            //   Black king on f8
            //   real move:  f8->e7
            //   false move: f8->f7
            //
            // Both destinations were occupied and visually changed, so both
            // passed simple capture occupancy. We now demand that the chosen
            // destination itself has a clear visual lead.
            val sameSourceAlternatives =
                validCandidates.filter { candidate ->

                    val sameSource =
                        candidate.fromIdx ==
                                bestMove.fromIdx &&
                                candidate.toIdx !=
                                bestMove.toIdx

                    if (!sameSource) {
                        false
                    } else {
                        !(
                                bestMove.toIdx !in
                                        persistentResidueSquares &&
                                        candidate.toIdx in
                                        persistentResidueSquares
                                )
                    }
                }


            if (sameSourceAlternatives.isNotEmpty()) {

                val strongestAlternative =
                    sameSourceAlternatives.maxByOrNull {
                        it.destinationVisualStrength
                    }


                if (strongestAlternative != null) {

                    val movedPiece =
                        virtualBoard.getPieceAt(
                            bestMove.fromIdx
                        )


                    val requiredLead =
                        if (
                            movedPiece?.lowercaseChar() ==
                            'k'
                        ) {
                            KING_SAME_SOURCE_MIN_DESTINATION_LEAD
                        } else {
                            SAME_SOURCE_MIN_DESTINATION_LEAD
                        }


                    val bestDestination =
                        maxOf(
                            bestMove.destinationVisualStrength,
                            1.0
                        )


                    val altDestination =
                        maxOf(
                            strongestAlternative.destinationVisualStrength,
                            1.0
                        )


                    /*
                     * Positive value means the chosen destination is
                     * visually stronger.
                     */
                    val destinationLead =
                        (
                                bestDestination -
                                        altDestination
                                ) /
                                maxOf(
                                    bestDestination,
                                    altDestination
                                )


                    /*
                     * If another destination is visually stronger, OR the
                     * chosen destination is not ahead by the required margin,
                     * do not guess. Keep the FEN unchanged and inspect a later
                     * settled frame.
                     */
                    if (
                        destinationLead <
                        requiredLead
                    ) {

                        Log.d(
                            TAG,
                            "REJECT reason=SAME_SOURCE_AMBIGUOUS " +
                                    "piece=${movedPiece ?: '?'} " +
                                    "source=${indexToSquareName(bestMove.fromIdx)} " +
                                    "best=${indexToSquareName(bestMove.toIdx)}:" +
                                    "score=${format(bestMove.score)}:" +
                                    "dst=${format(bestMove.destinationVisualStrength)} " +
                                    "alt=${indexToSquareName(strongestAlternative.toIdx)}:" +
                                    "score=${format(strongestAlternative.score)}:" +
                                    "dst=${format(strongestAlternative.destinationVisualStrength)} " +
                                    "lead=${format(destinationLead * 100.0)}% " +
                                    "required=${format(requiredLead * 100.0)}%"
                        )

                        lastDetectionOutcome =
                            DetectionOutcome.UNRESOLVED

                        return null
                    }
                }
            }


            val suppressedResidueAlternatives =
                validCandidates
                    .drop(1)
                    .filter { candidate ->
                        bestMove.toIdx !in
                                persistentResidueSquares &&
                                candidate.toIdx in
                                persistentResidueSquares
                    }

            if (suppressedResidueAlternatives.isNotEmpty()) {
                Log.d(
                    TAG,
                    "RESIDUE_DESTINATION_SUPPRESSED best=" +
                            "${indexToSquareName(bestMove.fromIdx)}->" +
                            "${indexToSquareName(bestMove.toIdx)} ignored=" +
                            suppressedResidueAlternatives.joinToString(
                                prefix = "[",
                                postfix = "]"
                            ) {
                                "${indexToSquareName(it.fromIdx)}->" +
                                        indexToSquareName(it.toIdx)
                            }
                )
            }


            // =============================================================
            // GENERIC AMBIGUITY
            // =============================================================

            val genericAmbiguityCompetitors =
                validCandidates
                    .drop(1)
                    .filter { candidate ->
                        !(
                                bestMove.toIdx !in
                                        persistentResidueSquares &&
                                        candidate.toIdx in
                                        persistentResidueSquares
                                )
                    }

            if (genericAmbiguityCompetitors.isNotEmpty()) {

                val secondMove =
                    genericAmbiguityCompetitors.first()


                val difference =
                    abs(
                        bestMove.score -
                                secondMove.score
                    )


                val reference =
                    maxOf(
                        abs(bestMove.score),
                        1.0
                    )


                val ratio =
                    difference / reference


                if (
                    ratio <
                    AMBIGUITY_RATIO
                ) {
                    Log.d(
                        TAG,
                        "REJECT reason=AMBIGUOUS " +
                                "best=${indexToSquareName(bestMove.fromIdx)}->${indexToSquareName(bestMove.toIdx)}:${format(bestMove.score)} " +
                                "second=${indexToSquareName(secondMove.fromIdx)}->${indexToSquareName(secondMove.toIdx)}:${format(secondMove.score)}"
                    )

                    return null
                }
            }


            // =============================================================
            // ACCEPT
            // =============================================================

            lastDetectionOutcome =
                DetectionOutcome.MOVE_FOUND

            Log.d(
                TAG,
                "ACCEPT ${indexToSquareName(bestMove.fromIdx)}->${indexToSquareName(bestMove.toIdx)} " +
                        "score=${format(bestMove.score)} " +
                        "extra=${bestMove.unexplainedSignificantSquares} " +
                        "dstStrength=${format(bestMove.destinationVisualStrength)} " +
                        "src=${changes[bestMove.fromIdx]?.kind ?: ChangeKind.WEAK} " +
                        "dst=${changes[bestMove.toIdx]?.kind ?: ChangeKind.WEAK}"
            )


            return DetectedMove(
                fromIdx = bestMove.fromIdx,
                toIdx = bestMove.toIdx,
                isUserMove = bestMove.isUserMove
            )

        } catch (e: Exception) {

            lastDetectionOutcome =
                DetectionOutcome.UNRESOLVED

            Log.e(
                TAG,
                "Move detection exception",
                e
            )

            return null

        } finally {

            oldSquares.forEach {

                try {
                    it.recycle()
                } catch (_: Exception) {
                }
            }


            newSquares.forEach {

                try {
                    it.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }


    // =====================================================================
    // HYBRID OCCUPANCY VERIFICATION
    // =====================================================================

    private data class OccupancyResult(
        val passes: Boolean,
        val reason: String
    )


    /**
     * Visual "piece presence" score for one square.
     *
     * The outer ring is used as an estimate of the square background.
     * We then measure how different the central piece region is from that
     * background.
     *
     * Empty square:
     *     center ~= background -> low score
     *
     * Piece present:
     *     center differs from background -> higher score
     *
     * This is deliberately theme-adaptive; we do not hard-code white or
     * black square colors.
     */
    private fun piecePresenceScore(
        bmp: Bitmap
    ): Double {

        val width =
            bmp.width

        val height =
            bmp.height

        if (
            width <= 4 ||
            height <= 4
        ) {
            return 0.0
        }

        val pixels =
            IntArray(
                width * height
            )

        bmp.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        /*
         * OVERLAY-ROBUST BACKGROUND ESTIMATE
         *
         * RecommendationOverlayService draws colored source/destination square
         * outlines along the OUTER edge of a square. The old occupancy score
         * sampled that outer ring as the square background. That meant an EMPTY
         * source square with a yellow/green/red recommendation outline could look
         * like it still contained a piece.
         *
         * Instead, sample four small INNER-corner patches. They are:
         * - far enough inward to avoid the colored outline stroke
         * - far enough away from the middle to avoid normal piece silhouettes
         * - still representative of the square's actual background/highlight
         *
         * This changes only the visual occupancy measurement. Chess legality,
         * 3-frame stability, cooldown, move scoring, and FEN logic are untouched.
         */
        val innerStartX =
            maxOf(
                1,
                (width * 0.16).toInt()
            )

        val innerEndX =
            maxOf(
                innerStartX + 1,
                (width * 0.30).toInt()
            )

        val innerStartY =
            maxOf(
                1,
                (height * 0.16).toInt()
            )

        val innerEndY =
            maxOf(
                innerStartY + 1,
                (height * 0.30).toInt()
            )

        var bgR = 0.0
        var bgG = 0.0
        var bgB = 0.0
        var bgCount = 0

        fun samplePatch(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ) {

            for (y in top until bottom step 2) {

                for (x in left until right step 2) {

                    if (
                        x !in 0 until width ||
                        y !in 0 until height
                    ) {
                        continue
                    }

                    val p =
                        pixels[
                            y * width + x
                        ]

                    bgR +=
                        (p shr 16) and 0xFF

                    bgG +=
                        (p shr 8) and 0xFF

                    bgB +=
                        p and 0xFF

                    bgCount++
                }
            }
        }

        samplePatch(
            left = innerStartX,
            top = innerStartY,
            right = innerEndX,
            bottom = innerEndY
        )

        samplePatch(
            left = width - innerEndX,
            top = innerStartY,
            right = width - innerStartX,
            bottom = innerEndY
        )

        samplePatch(
            left = innerStartX,
            top = height - innerEndY,
            right = innerEndX,
            bottom = height - innerStartY
        )

        samplePatch(
            left = width - innerEndX,
            top = height - innerEndY,
            right = width - innerStartX,
            bottom = height - innerStartY
        )

        if (bgCount == 0) {
            return 0.0
        }

        bgR /= bgCount
        bgG /= bgCount
        bgB /= bgCount

        val left =
            (width * 0.18).toInt()

        val right =
            (width * 0.82).toInt()

        val top =
            (height * 0.14).toInt()

        val bottom =
            (height * 0.86).toInt()

        var centerDistance = 0.0
        var centerCount = 0

        for (y in top until bottom step 2) {

            for (x in left until right step 2) {

                val p =
                    pixels[
                        y * width + x
                    ]

                val r =
                    (p shr 16) and 0xFF

                val g =
                    (p shr 8) and 0xFF

                val b =
                    p and 0xFF

                centerDistance +=
                    abs(r - bgR) +
                            abs(g - bgG) +
                            abs(b - bgB)

                centerCount++
            }
        }

        return if (centerCount > 0) {
            centerDistance /
                    centerCount.toDouble()
        } else {
            0.0
        }
    }


    /**
     * Builds an EMPTY-square threshold for the requested square color using
     * several squares that VirtualChessBoard currently knows are empty.
     *
     * Same-color squares are used because light and dark square backgrounds
     * naturally have different RGB values.
     *
     * Median makes the reference resistant to one noisy/animated empty
     * square.
     */
    private fun emptyPresenceThreshold(
        targetIndex: Int,
        squares: List<Bitmap>,
        virtualBoard: VirtualChessBoard,
        exclude: Set<Int>
    ): Double? {

        val targetRow =
            targetIndex / 8

        val targetCol =
            targetIndex % 8

        val targetParity =
            (targetRow + targetCol) % 2

        val references =
            mutableListOf<Double>()

        for (index in 0 until 64) {

            if (index in exclude) {
                continue
            }

            val row =
                index / 8

            val col =
                index % 8

            if (
                (row + col) % 2 !=
                targetParity
            ) {
                continue
            }

            /*
             * Use only squares known to be empty BEFORE the candidate move.
             */
            if (
                virtualBoard.getPieceAt(index) !=
                null
            ) {
                continue
            }

            references.add(
                piecePresenceScore(
                    squares[index]
                )
            )
        }

        if (
            references.size <
            MIN_EMPTY_REFERENCES
        ) {
            return null
        }

        references.sort()

        val median =
            references[
                references.size / 2
            ]

        return median +
                maxOf(
                    OCCUPANCY_EMPTY_TOLERANCE,
                    median * 0.45
                )
    }



    private fun isSquareVisuallyEmpty(
        index: Int,
        squares: List<Bitmap>,
        virtualBoard: VirtualChessBoard,
        exclude: Set<Int>
    ): Boolean {

        if (index !in 0..63) {
            return false
        }

        val threshold =
            emptyPresenceThreshold(
                targetIndex = index,
                squares = squares,
                virtualBoard = virtualBoard,
                exclude = exclude
            )
                ?: return false

        val presence =
            piecePresenceScore(
                squares[index]
            )

        return presence <= threshold
    }


    private fun isSquareVisuallyOccupied(
        index: Int,
        squares: List<Bitmap>,
        virtualBoard: VirtualChessBoard,
        exclude: Set<Int>
    ): Boolean {

        if (index !in 0..63) {
            return false
        }

        val threshold =
            emptyPresenceThreshold(
                targetIndex = index,
                squares = squares,
                virtualBoard = virtualBoard,
                exclude = exclude
            )
                ?: return false

        val presence =
            piecePresenceScore(
                squares[index]
            )

        return presence >
                threshold +
                OCCUPANCY_PRESENT_MARGIN
    }


    /**
     * Hybrid occupancy gate for a candidate move.
     *
     * This is the key protection against a clicked piece that only
     * wiggles/scales in place.
     *
     * Mandatory:
     *   source AFTER candidate must look empty.
     *
     * If destination was empty in VirtualChessBoard:
     *   destination BEFORE must look empty
     *   destination AFTER must look occupied
     *
     * If destination already held an opponent piece (capture):
     *   destination AFTER should still look occupied.
     *
     * If there are not enough reliable empty reference squares, this gate
     * fails open rather than destroying otherwise-good chess detection.
     */
    private fun evaluateCandidateOccupancy(
        fromIdx: Int,
        toIdx: Int,
        oldSquares: List<Bitmap>,
        newSquares: List<Bitmap>,
        virtualBoard: VirtualChessBoard
    ): OccupancyResult {

        val exclude =
            setOf(
                fromIdx,
                toIdx
            )

        val sourceThreshold =
            emptyPresenceThreshold(
                targetIndex = fromIdx,
                squares = newSquares,
                virtualBoard = virtualBoard,
                exclude = exclude
            )

        /*
         * Not enough empty references: keep the existing detector behavior.
         */
        if (sourceThreshold == null) {
            return OccupancyResult(
                passes = true,
                reason = "occupancy=NO_REFERENCE"
            )
        }

        val sourceAfter =
            piecePresenceScore(
                newSquares[fromIdx]
            )

        /*
         * The piece MUST actually disappear from its source.
         *
         * A click animation typically fails here because the same piece is
         * still visibly present after the animation settles.
         */
        if (
            sourceAfter >
            sourceThreshold
        ) {
            return OccupancyResult(
                passes = false,
                reason =
                    "sourceStillOccupied " +
                            "srcAfter=${format(sourceAfter)} " +
                            "empty<=${format(sourceThreshold)}"
            )
        }

        val targetPieceBefore =
            virtualBoard.getPieceAt(
                toIdx
            )

        val oldDestinationThreshold =
            emptyPresenceThreshold(
                targetIndex = toIdx,
                squares = oldSquares,
                virtualBoard = virtualBoard,
                exclude = exclude
            )

        val newDestinationThreshold =
            emptyPresenceThreshold(
                targetIndex = toIdx,
                squares = newSquares,
                virtualBoard = virtualBoard,
                exclude = exclude
            )

        /*
         * If threshold construction becomes impossible late in a sparse
         * game, source->empty is still valuable enough to keep the candidate.
         */
        if (
            oldDestinationThreshold == null ||
            newDestinationThreshold == null
        ) {
            return OccupancyResult(
                passes = true,
                reason = "sourceEmpty destinationReferenceUnavailable"
            )
        }

        val destinationBefore =
            piecePresenceScore(
                oldSquares[toIdx]
            )

        val destinationAfter =
            piecePresenceScore(
                newSquares[toIdx]
            )

        if (targetPieceBefore == null) {

            /*
             * Normal non-capture / en-passant destination:
             * empty -> occupied
             */
            if (
                destinationBefore >
                oldDestinationThreshold
            ) {
                return OccupancyResult(
                    passes = false,
                    reason =
                        "destinationWasNotEmpty " +
                                "before=${format(destinationBefore)} " +
                                "empty<=${format(oldDestinationThreshold)}"
                )
            }

            if (
                destinationAfter <=
                newDestinationThreshold +
                OCCUPANCY_PRESENT_MARGIN
            ) {
                return OccupancyResult(
                    passes = false,
                    reason =
                        "destinationStillLooksEmpty " +
                                "after=${format(destinationAfter)} " +
                                "occupied>${format(newDestinationThreshold + OCCUPANCY_PRESENT_MARGIN)}"
                )
            }

        } else {

            /*
             * Capture:
             * occupied -> occupied, but with changed occupant.
             *
             * Source->empty plus existing pixel-change/trajectory checks do
             * most of the work; this only rejects a destination that became
             * visually empty.
             */
            if (
                destinationAfter <=
                newDestinationThreshold
            ) {
                return OccupancyResult(
                    passes = false,
                    reason =
                        "captureDestinationLooksEmpty " +
                                "after=${format(destinationAfter)} " +
                                "empty<=${format(newDestinationThreshold)}"
                )
            }
        }

        return OccupancyResult(
            passes = true,
            reason =
                "occupancyOK " +
                        "srcAfter=${format(sourceAfter)} " +
                        "dstBefore=${format(destinationBefore)} " +
                        "dstAfter=${format(destinationAfter)}"
        )
    }



    // =====================================================================
    // DATA CLASSES
    // =====================================================================

    private data class MoveCandidate(
        val fromIdx: Int,
        val toIdx: Int,
        val isUserMove: Boolean,
        val score: Double,
        val unexplainedSignificantSquares: Int,

        /*
         * Destination-only visual evidence.
         *
         * This is intentionally stored separately from total candidate
         * score so unrelated extra-square penalties cannot make a weak
         * destination beat the square that visibly changed most.
         */
        val destinationVisualStrength: Double
    )


    private enum class ChangeKind {
        PIECE_LIKE,
        UI_HINT,
        BACKGROUND_UI,
        WEAK
    }

    private data class SquareChange(
        val total: Double,
        val center: Double,
        val edge: Double,
        val changedRatio: Double,
        val kind: ChangeKind
    )


    // =====================================================================
    // DIFFERENCE CALCULATION
    // =====================================================================

    private fun getSquareChange(
        bmp1: Bitmap,
        bmp2: Bitmap
    ): SquareChange {

        val width =
            minOf(
                bmp1.width,
                bmp2.width
            )

        val height =
            minOf(
                bmp1.height,
                bmp2.height
            )

        if (
            width <= 0 ||
            height <= 0
        ) {

            return SquareChange(
                total = 0.0,
                center = 0.0,
                edge = 0.0,
                changedRatio = 0.0,
                kind = ChangeKind.WEAK
            )
        }

        val pixels1 =
            IntArray(width * height)

        val pixels2 =
            IntArray(width * height)

        bmp1.getPixels(
            pixels1,
            0,
            width,
            0,
            0,
            width,
            height
        )

        bmp2.getPixels(
            pixels2,
            0,
            width,
            0,
            0,
            width,
            height
        )

        var totalDiff = 0.0
        var centerDiff = 0.0
        var edgeDiff = 0.0

        var totalSamples = 0
        var centerSamples = 0
        var edgeSamples = 0
        var changedSamples = 0

        /*
         * ============================================================
         * OVERLAY-SAFE DIFFERENCE REGION
         * ============================================================
         *
         * RecommendationOverlayService draws the Stockfish move hints as
         * thin colored outlines around the perimeter of recommended squares.
         *
         * MediaProjection captures those outlines too.
         *
         * If the move detector compares those perimeter pixels, its own
         * recommendation overlay can look like a chessboard change and create
         * ghost move evidence.
         *
         * Therefore the OUTER 12% of every square is ignored completely.
         *
         * The remaining 76% still contains the piece body and is more than
         * enough for source/destination detection.
         */
        val ignoreLeft =
            (width * 0.12).toInt()

        val ignoreRight =
            (width * 0.88).toInt()

        val ignoreTop =
            (height * 0.12).toInt()

        val ignoreBottom =
            (height * 0.88).toInt()

        /*
         * Strong piece-motion evidence comes from the central portion.
         *
         * The region between the 12% ignored perimeter and this center box
         * remains our INNER edge region for UI/background classification.
         */
        val centerLeft =
            (width * 0.22).toInt()

        val centerRight =
            (width * 0.78).toInt()

        val centerTop =
            (height * 0.22).toInt()

        val centerBottom =
            (height * 0.78).toInt()

        for (
        y in
        ignoreTop until ignoreBottom step 2
        ) {

            for (
            x in
            ignoreLeft until ignoreRight step 2
            ) {

                val index =
                    y * width + x

                val p1 =
                    pixels1[index]

                val p2 =
                    pixels2[index]

                val r1 =
                    (p1 shr 16) and 0xFF

                val g1 =
                    (p1 shr 8) and 0xFF

                val b1 =
                    p1 and 0xFF

                val r2 =
                    (p2 shr 16) and 0xFF

                val g2 =
                    (p2 shr 8) and 0xFF

                val b2 =
                    p2 and 0xFF

                val difference =
                    abs(r1 - r2) +
                            abs(g1 - g2) +
                            abs(b1 - b2)

                /*
                 * "total" now means total change inside the SAFE analysis
                 * region only. The physical square border contributes nothing.
                 */
                totalDiff +=
                    difference

                totalSamples++

                if (
                    difference >=
                    PIXEL_CHANGE_DELTA
                ) {
                    changedSamples++
                }

                val isCenter =
                    x >= centerLeft &&
                            x < centerRight &&
                            y >= centerTop &&
                            y < centerBottom

                if (isCenter) {

                    centerDiff +=
                        difference

                    centerSamples++

                } else {

                    /*
                     * This is the INNER edge region, not the actual perimeter
                     * where the Stockfish colored outline is drawn.
                     */
                    edgeDiff +=
                        difference

                    edgeSamples++
                }
            }
        }

        val normalizedTotal =
            if (totalSamples > 0) {

                totalDiff /
                        totalSamples *
                        100.0

            } else {

                0.0
            }

        val normalizedCenter =
            if (centerSamples > 0) {

                centerDiff /
                        centerSamples *
                        100.0

            } else {

                0.0
            }

        val normalizedEdge =
            if (edgeSamples > 0) {

                edgeDiff /
                        edgeSamples *
                        100.0

            } else {

                0.0
            }

        val changedRatio =
            if (totalSamples > 0) {

                changedSamples.toDouble() /
                        totalSamples.toDouble()

            } else {

                0.0
            }

        val kind =
            classifySquareChange(
                total = normalizedTotal,
                center = normalizedCenter,
                edge = normalizedEdge,
                changedRatio = changedRatio
            )

        return SquareChange(
            total = normalizedTotal,
            center = normalizedCenter,
            edge = normalizedEdge,
            changedRatio = changedRatio,
            kind = kind
        )
    }


    private fun classifySquareChange(
        total: Double,
        center: Double,
        edge: Double,
        changedRatio: Double
    ): ChangeKind {

        if (total < 500.0) {
            return ChangeKind.WEAK
        }

        /*
         * A legal-move dot/circle often changes a relatively small
         * fraction of the square while being concentrated near the center.
         */
        val centerDominates =
            center >= edge * UI_HINT_CENTER_EDGE_RATIO

        if (
            changedRatio <= UI_HINT_MAX_CHANGED_RATIO &&
            centerDominates
        ) {
            return ChangeKind.UI_HINT
        }

        /*
         * Broad changes concentrated around the square background are
         * usually selection/hover/board UI rather than piece motion.
         */
        if (
            edge > center * 1.45 &&
            changedRatio < 0.30
        ) {
            return ChangeKind.BACKGROUND_UI
        }

        return ChangeKind.PIECE_LIKE
    }


    // =====================================================================
    // SCORING
    // =====================================================================

    private fun scoreCandidateMove(
        fromIdx: Int,
        toIdx: Int,
        changes: Map<Int, SquareChange>,
        significantIndices: List<Int>,
        virtualBoard: VirtualChessBoard,
        persistentResidueSquares: Set<Int>
    ): Double {

        val source =
            changes[fromIdx]
                ?: return Double.NEGATIVE_INFINITY


        val destination =
            changes[toIdx]
                ?: return Double.NEGATIVE_INFINITY


        val sourcePieceEvidence =
            calculatePieceEvidence(source)


        val destinationPieceEvidence =
            calculatePieceEvidence(destination)


        var score =
            sourcePieceEvidence * 1.35 +
                    destinationPieceEvidence * 1.35


        score +=
            source.total * 0.20


        score +=
            destination.total * 0.20


        for (index in significantIndices) {

            if (
                index != fromIdx &&
                index != toIdx
            ) {

                if (
                    index in
                    persistentResidueSquares
                ) {
                    continue
                }

                val extra =
                    changes[index]
                        ?: continue


                val basePenalty =
                    extra.center * 0.10 +
                            extra.edge * 0.04

                val penalty =
                    when (extra.kind) {
                        ChangeKind.UI_HINT,
                        ChangeKind.BACKGROUND_UI ->
                            basePenalty * UI_HINT_EXTRA_PENALTY_SCALE

                        ChangeKind.PIECE_LIKE ->
                            basePenalty

                        ChangeKind.WEAK ->
                            0.0
                    }

                score -= penalty
            }
        }


        val piece =
            virtualBoard.getPieceAt(fromIdx)


        if (
            piece == 'P' ||
            piece == 'p'
        ) {

            val fromRow =
                fromIdx / 8


            val toRow =
                toIdx / 8


            if (
                abs(fromRow - toRow) == 2
            ) {

                score += 250.0
            }
        }


        if (
            source.center >= 2500.0 &&
            destination.center >= 2500.0
        ) {

            score += 500.0
        }


        return score
    }


    private fun calculatePieceEvidence(
        change: SquareChange
    ): Double {

        val evidence =
            change.center -
                    change.edge * 0.25


        return maxOf(
            evidence,
            0.0
        )
    }


    private fun calculateDestinationVisualStrength(
        change: SquareChange
    ): Double {

        /*
         * A true destination in a move/capture should show a substantial
         * central replacement, not merely a background/UI difference.
         *
         * Give center/piece evidence most of the weight, with total change
         * as secondary support.
         */
        return calculatePieceEvidence(change) * 1.30 +
                change.total * 0.35
    }


    private fun countUnexplainedSquares(
        fromIdx: Int,
        toIdx: Int,
        changedIndices: List<Int>,
        changes: Map<Int, SquareChange>,
        persistentResidueSquares: Set<Int>
    ): Int {

        var count = 0

        for (index in changedIndices) {

            if (
                index == fromIdx ||
                index == toIdx
            ) {
                continue
            }

            if (
                index in
                persistentResidueSquares
            ) {
                continue
            }

            val kind =
                changes[index]?.kind
                    ?: ChangeKind.WEAK

            /*
             * Do not count likely legal-move dots, circles, or other
             * background UI as unexplained chess-piece changes.
             */
            if (
                kind == ChangeKind.UI_HINT ||
                kind == ChangeKind.BACKGROUND_UI ||
                kind == ChangeKind.WEAK
            ) {
                continue
            }

            count++
        }

        return count
    }


    // =====================================================================
    // CASTLING
    // =====================================================================

    private fun detectCastlingMove(
        changedIndices: List<Int>,
        changes: Map<Int, SquareChange>,
        newSquares: List<Bitmap>,
        virtualBoard: VirtualChessBoard,
        persistentResidueSquares: Set<Int>
    ): DetectedMove? {

        val activeColor =
            virtualBoard.getActiveColor()

        /*
         * =============================================================
         * PREMOVE / ROBOT-SPEED SAFE CASTLING
         * =============================================================
         *
         * DO NOT reject castling merely because many other squares have
         * already changed.
         *
         * On Chess.com a human can premove, so one settled screenshot may
         * already contain:
         *
         *   previous move residue
         *   + the complete castle
         *   + visual evidence from the next ply
         *
         * The old "> 6 PIECE_LIKE squares => reject castle" rule therefore
         * caused a valid Black O-O to be missed.
         *
         * Instead, validate the CURRENT active side's castle using:
         *
         *   1. Virtual king/rook are on the correct source squares.
         *   2. Castling right still exists.
         *   3. The four castle squares show the correct AFTER occupancy:
         *
         *        king source  -> EMPTY
         *        rook source  -> EMPTY
         *        king target  -> OCCUPIED
         *        rook target  -> OCCUPIED
         *
         * Extra changed squares do not veto the castle.
         *
         * This still advances exactly ONE virtual ply. ScreenRecordService
         * patches only the four explained castle squares, leaving any next
         * premove visible for the following detection pass.
         */

        fun tryCastle(
            white: Boolean,
            kingside: Boolean,
            kingFrom: Int,
            kingTo: Int,
            rookFrom: Int,
            rookTo: Int
        ): DetectedMove? {

            val expectedKing =
                if (white) 'K' else 'k'

            val expectedRook =
                if (white) 'R' else 'r'

            if (
                virtualBoard.getPieceAt(kingFrom) !=
                expectedKing
            ) {
                return null
            }

            if (
                virtualBoard.getPieceAt(rookFrom) !=
                expectedRook
            ) {
                return null
            }

            if (
                !virtualBoard.canCastle(
                    white,
                    kingside
                )
            ) {
                return null
            }

            /*
             * Build empty-square thresholds while excluding all four castle
             * squares. The virtual board is still PRE-MOVE here, which is
             * exactly what we want for reference selection.
             */
            val castleSquares =
                setOf(
                    kingFrom,
                    kingTo,
                    rookFrom,
                    rookTo
                )

            val kingFromEmpty =
                isSquareVisuallyEmpty(
                    index = kingFrom,
                    squares = newSquares,
                    virtualBoard = virtualBoard,
                    exclude = castleSquares
                )

            val rookFromEmpty =
                isSquareVisuallyEmpty(
                    index = rookFrom,
                    squares = newSquares,
                    virtualBoard = virtualBoard,
                    exclude = castleSquares
                )

            val kingToOccupied =
                isSquareVisuallyOccupied(
                    index = kingTo,
                    squares = newSquares,
                    virtualBoard = virtualBoard,
                    exclude = castleSquares
                )

            val rookToOccupied =
                isSquareVisuallyOccupied(
                    index = rookTo,
                    squares = newSquares,
                    virtualBoard = virtualBoard,
                    exclude = castleSquares
                )

            if (
                !kingFromEmpty ||
                !rookFromEmpty ||
                !kingToOccupied ||
                !rookToOccupied
            ) {

                return null
            }

            /*
             * Visual-change evidence is still useful diagnostically, but it
             * is no longer a hard "only these squares may have changed" gate.
             */
            val changedCastleSquares =
                castleSquares.count {
                    it in changedIndices
                }

            /*
             * Require at least two castle squares to have meaningful
             * old-vs-new visual change. Occupancy supplies the stronger
             * four-square state proof.
             */
            if (changedCastleSquares < 2) {
                return null
            }

            val extras =
                changedIndices
                    .filter {
                        it !in castleSquares &&
                                it !in persistentResidueSquares
                    }

            Log.d(
                TAG,
                "CASTLE_CONFIRMED " +
                        "${indexToSquareName(kingFrom)}->" +
                        "${indexToSquareName(kingTo)} " +
                        "occupancy=[${indexToSquareName(kingFrom)}=EMPTY," +
                        "${indexToSquareName(rookFrom)}=EMPTY," +
                        "${indexToSquareName(kingTo)}=OCCUPIED," +
                        "${indexToSquareName(rookTo)}=OCCUPIED] " +
                        "changedCastle=$changedCastleSquares/4 " +
                        "extras=" +
                        extras.joinToString(
                            prefix = "[",
                            postfix = "]"
                        ) {
                            indexToSquareName(it)
                        }
            )

            return DetectedMove(
                fromIdx = kingFrom,
                toIdx = kingTo,
                isUserMove =
                    if (white) {
                        virtualBoard.isUserWhite
                    } else {
                        !virtualBoard.isUserWhite
                    }
            )
        }


        if (activeColor == 'w') {

            /*
             * White kingside:
             * e1 -> g1
             * h1 -> f1
             */
            tryCastle(
                white = true,
                kingside = true,
                kingFrom = 60,
                kingTo = 62,
                rookFrom = 63,
                rookTo = 61
            )?.let {
                return it
            }

            /*
             * White queenside:
             * e1 -> c1
             * a1 -> d1
             */
            tryCastle(
                white = true,
                kingside = false,
                kingFrom = 60,
                kingTo = 58,
                rookFrom = 56,
                rookTo = 59
            )?.let {
                return it
            }
        }


        if (activeColor == 'b') {

            /*
             * Black kingside:
             * e8 -> g8
             * h8 -> f8
             */
            tryCastle(
                white = false,
                kingside = true,
                kingFrom = 4,
                kingTo = 6,
                rookFrom = 7,
                rookTo = 5
            )?.let {
                return it
            }

            /*
             * Black queenside:
             * e8 -> c8
             * a8 -> d8
             */
            tryCastle(
                white = false,
                kingside = false,
                kingFrom = 4,
                kingTo = 2,
                rookFrom = 0,
                rookTo = 3
            )?.let {
                return it
            }
        }


        return null
    }


    // =====================================================================
    // MOVEMENT VALIDATION
    // =====================================================================

    private fun isValidPieceTrajectory(
        piece: Char,
        fromIdx: Int,
        toIdx: Int,
        virtualBoard: VirtualChessBoard
    ): Boolean {

        if (
            fromIdx !in 0..63 ||
            toIdx !in 0..63 ||
            fromIdx == toIdx
        ) {

            return false
        }


        val fromRow =
            fromIdx / 8

        val fromCol =
            fromIdx % 8


        val toRow =
            toIdx / 8

        val toCol =
            toIdx % 8


        val dRow =
            abs(toRow - fromRow)

        val dCol =
            abs(toCol - fromCol)


        val targetPiece =
            virtualBoard.getPieceAt(toIdx)


        if (
            targetPiece != null &&
            targetPiece.isUpperCase() ==
            piece.isUpperCase()
        ) {

            return false
        }


        return when (piece.lowercaseChar()) {

            'n' -> {

                (dRow == 1 && dCol == 2) ||
                        (dRow == 2 && dCol == 1)
            }


            'b' -> {

                if (
                    dRow == 0 ||
                    dRow != dCol
                ) {

                    false

                } else {

                    isPathClear(
                        fromRow,
                        fromCol,
                        toRow,
                        toCol,
                        virtualBoard
                    )
                }
            }


            'r' -> {

                if (
                    !(
                            (dRow == 0 && dCol > 0) ||
                                    (dCol == 0 && dRow > 0)
                            )
                ) {

                    false

                } else {

                    isPathClear(
                        fromRow,
                        fromCol,
                        toRow,
                        toCol,
                        virtualBoard
                    )
                }
            }


            'q' -> {

                val diagonal =
                    dRow == dCol &&
                            dRow > 0


                val straight =
                    (dRow == 0 && dCol > 0) ||
                            (dCol == 0 && dRow > 0)


                if (
                    !diagonal &&
                    !straight
                ) {

                    false

                } else {

                    isPathClear(
                        fromRow,
                        fromCol,
                        toRow,
                        toCol,
                        virtualBoard
                    )
                }
            }


            'k' -> {

                dRow <= 1 &&
                        dCol <= 1 &&
                        (dRow + dCol > 0)
            }


            'p' -> {

                val isWhite =
                    piece.isUpperCase()


                val direction =
                    if (isWhite) {
                        -1
                    } else {
                        1
                    }


                val startRow =
                    if (isWhite) {
                        6
                    } else {
                        1
                    }


                val rowDiff =
                    toRow - fromRow


                if (dCol == 0) {

                    if (targetPiece != null) {
                        return false
                    }


                    if (
                        rowDiff == direction
                    ) {

                        return true
                    }


                    if (
                        rowDiff ==
                        2 * direction &&
                        fromRow == startRow
                    ) {

                        val middleRow =
                            fromRow + direction


                        val middleIndex =
                            middleRow * 8 +
                                    fromCol


                        if (
                            virtualBoard.getPieceAt(
                                middleIndex
                            ) != null
                        ) {

                            return false
                        }


                        return true
                    }


                    return false
                }


                if (dCol == 1) {

                    if (
                        rowDiff == direction &&
                        targetPiece != null
                    ) {

                        return true
                    }


                    return false
                }


                false
            }


            else -> false
        }
    }


    // =====================================================================
    // PATH
    // =====================================================================

    private fun isPathClear(
        fromRow: Int,
        fromCol: Int,
        toRow: Int,
        toCol: Int,
        virtualBoard: VirtualChessBoard
    ): Boolean {

        val rowStep =
            when {

                toRow > fromRow -> 1

                toRow < fromRow -> -1

                else -> 0
            }


        val colStep =
            when {

                toCol > fromCol -> 1

                toCol < fromCol -> -1

                else -> 0
            }


        var row =
            fromRow + rowStep


        var col =
            fromCol + colStep


        while (
            row != toRow ||
            col != toCol
        ) {

            if (
                row !in 0..7 ||
                col !in 0..7
            ) {

                return false
            }


            val index =
                row * 8 + col


            if (
                virtualBoard.getPieceAt(index) != null
            ) {

                return false
            }


            row += rowStep
            col += colStep
        }


        return true
    }


    // =====================================================================
    // COMPATIBILITY DIFFERENCE FUNCTION
    // =====================================================================

    fun getSquareDifferenceScore(
        bmp1: Bitmap,
        bmp2: Bitmap
    ): Double {

        return getSquareChange(
            bmp1,
            bmp2
        ).total
    }


    // =====================================================================
    // STABILITY
    // =====================================================================

    fun areBitmapsSimilar(
        bmp1: Bitmap?,
        bmp2: Bitmap?
    ): Boolean {

        if (
            bmp1 == null ||
            bmp2 == null
        ) {

            return false
        }


        return try {

            val size = 64


            val scaled1 =
                Bitmap.createScaledBitmap(
                    bmp1,
                    size,
                    size,
                    true
                )


            val scaled2 =
                Bitmap.createScaledBitmap(
                    bmp2,
                    size,
                    size,
                    true
                )


            val mat1 =
                Mat()


            val mat2 =
                Mat()


            Utils.bitmapToMat(
                scaled1,
                mat1
            )


            Utils.bitmapToMat(
                scaled2,
                mat2
            )


            if (
                mat1.channels() > 1
            ) {

                Imgproc.cvtColor(
                    mat1,
                    mat1,
                    Imgproc.COLOR_RGBA2GRAY
                )
            }


            if (
                mat2.channels() > 1
            ) {

                Imgproc.cvtColor(
                    mat2,
                    mat2,
                    Imgproc.COLOR_RGBA2GRAY
                )
            }


            val difference =
                Mat()


            Core.absdiff(
                mat1,
                mat2,
                difference
            )


            val average =
                Core.mean(
                    difference
                ).`val`[0]


            mat1.release()
            mat2.release()
            difference.release()


            if (scaled1 !== bmp1) {
                scaled1.recycle()
            }


            if (scaled2 !== bmp2) {
                scaled2.recycle()
            }


            average <= 7.0

        } catch (e: Exception) {

            Log.e(
                "ChessAI",
                "Bitmap similarity check failed",
                e
            )

            false
        }
    }


    // =====================================================================
    // DEBUG FORMATTING
    // =====================================================================

    private fun format(
        value: Double
    ): String {

        return String.format(
            java.util.Locale.US,
            "%.1f",
            value
        )
    }
}
