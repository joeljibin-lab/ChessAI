package com.example.chessai

class VirtualChessBoard {

    private val board = Array(64) { '.' }
    private var activeColor = 'w'
    private var fullMoveNumber = 1
    private var halfMoveClock = 0
    private var enPassantTarget = -1

    private var whiteKingsideCastle = true
    private var whiteQueensideCastle = true
    private var blackKingsideCastle = true
    private var blackQueensideCastle = true

    var isUserWhite: Boolean = true

    init {
        resetToDefault(true)
    }

    data class Snapshot(
        val board: CharArray,
        val activeColor: Char,
        val fullMoveNumber: Int,
        val halfMoveClock: Int,
        val enPassantTarget: Int,
        val whiteKingsideCastle: Boolean,
        val whiteQueensideCastle: Boolean,
        val blackKingsideCastle: Boolean,
        val blackQueensideCastle: Boolean,
        val isUserWhite: Boolean
    )

    fun createSnapshot(): Snapshot {
        return Snapshot(
            board = CharArray(64) { index -> board[index] },
            activeColor = activeColor,
            fullMoveNumber = fullMoveNumber,
            halfMoveClock = halfMoveClock,
            enPassantTarget = enPassantTarget,
            whiteKingsideCastle = whiteKingsideCastle,
            whiteQueensideCastle = whiteQueensideCastle,
            blackKingsideCastle = blackKingsideCastle,
            blackQueensideCastle = blackQueensideCastle,
            isUserWhite = isUserWhite
        )
    }

    fun restoreSnapshot(snapshot: Snapshot) {
        for (i in 0 until 64) {
            board[i] = snapshot.board[i]
        }

        activeColor = snapshot.activeColor
        fullMoveNumber = snapshot.fullMoveNumber
        halfMoveClock = snapshot.halfMoveClock
        enPassantTarget = snapshot.enPassantTarget

        whiteKingsideCastle = snapshot.whiteKingsideCastle
        whiteQueensideCastle = snapshot.whiteQueensideCastle
        blackKingsideCastle = snapshot.blackKingsideCastle
        blackQueensideCastle = snapshot.blackQueensideCastle

        isUserWhite = snapshot.isUserWhite
    }

    fun copy(): VirtualChessBoard {
        return VirtualChessBoard().also {
            it.restoreSnapshot(
                createSnapshot()
            )
        }
    }

    fun getPieceAt(index: Int): Char? {
        if (index !in 0..63) return null
        val piece = board[index]
        return if (piece == '.' || piece == ' ') null else piece
    }

    fun getActiveColor(): Char = activeColor

    fun isUserTurn(): Boolean =
        if (isUserWhite) {
            activeColor == 'w'
        } else {
            activeColor == 'b'
        }

    fun isPieceOnActiveTurn(piece: Char): Boolean {
        val pieceIsWhite = piece.isUpperCase()
        return if (activeColor == 'w') pieceIsWhite else !pieceIsWhite
    }

    fun isUserPiece(index: Int): Boolean {
        val piece = getPieceAt(index) ?: return false
        val pieceIsWhite = piece.isUpperCase()
        return if (isUserWhite) pieceIsWhite else !pieceIsWhite
    }

    fun canCastle(white: Boolean, kingside: Boolean): Boolean {
        return if (white) {
            if (kingside) whiteKingsideCastle else whiteQueensideCastle
        } else {
            if (kingside) blackKingsideCastle else blackQueensideCastle
        }
    }

    fun isPromotionMove(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in 0..63 || toIndex !in 0..63) return false
        val piece = getPieceAt(fromIndex) ?: return false
        if (piece != 'P' && piece != 'p') return false
        if (!isPieceOnActiveTurn(piece)) return false
        val toRow = toIndex / 8
        return (piece == 'P' && toRow == 0) || (piece == 'p' && toRow == 7)
    }

    fun applyPromotionMove(
        fromIndex: Int,
        toIndex: Int,
        promotionPiece: Char
    ): String {

        if (!isPromotionMove(fromIndex, toIndex)) {
            return generateFen()
        }

        val pawn = board[fromIndex]
        val isWhite = pawn == 'P'
        val capturedPiece = board[toIndex]
        val requested = promotionPiece.lowercaseChar()

        if (requested !in charArrayOf('q', 'r', 'b', 'n')) {
            return generateFen()
        }

        val promoted =
            if (isWhite) {
                requested.uppercaseChar()
            } else {
                requested
            }

        if (capturedPiece == 'R') {
            when (toIndex) {
                56 -> whiteQueensideCastle = false
                63 -> whiteKingsideCastle = false
            }
        }

        if (capturedPiece == 'r') {
            when (toIndex) {
                0 -> blackQueensideCastle = false
                7 -> blackKingsideCastle = false
            }
        }

        board[toIndex] = promoted
        board[fromIndex] = '.'

        halfMoveClock = 0
        enPassantTarget = -1

        finishTurn()

        return generateFen()
    }

    fun applyMove(indexA: Int, indexB: Int): String {

        if (
            indexA !in 0..63 ||
            indexB !in 0..63 ||
            indexA == indexB
        ) {
            return generateFen()
        }

        var fromIndex = indexA
        var toIndex = indexB
        var movedPiece = board[fromIndex]

        if (
            movedPiece == '.' &&
            board[toIndex] != '.'
        ) {

            val candidate = board[toIndex]

            if (isPieceOnActiveTurn(candidate)) {
                fromIndex = indexB
                toIndex = indexA
                movedPiece = board[fromIndex]
            }
        }

        if (movedPiece == '.') {
            return generateFen()
        }

        if (!isPieceOnActiveTurn(movedPiece)) {
            return generateFen()
        }

        if (
            isPromotionMove(
                fromIndex,
                toIndex
            )
        ) {
            return generateFen()
        }

        val capturedPiece = board[toIndex]

        val fromRow = fromIndex / 8
        val fromCol = fromIndex % 8

        val toRow = toIndex / 8
        val toCol = toIndex % 8

        val isWhite = movedPiece.isUpperCase()

        val isKing =
            movedPiece == 'K' ||
                    movedPiece == 'k'

        val isCastling =
            isKing &&
                    fromRow == toRow &&
                    kotlin.math.abs(
                        fromCol - toCol
                    ) == 2

        if (isCastling) {

            if (toCol > fromCol) {

                val rookFrom =
                    fromRow * 8 + 7

                val rookTo =
                    fromRow * 8 + 5

                board[rookTo] =
                    board[rookFrom]

                board[rookFrom] =
                    '.'

            } else {

                val rookFrom =
                    fromRow * 8

                val rookTo =
                    fromRow * 8 + 3

                board[rookTo] =
                    board[rookFrom]

                board[rookFrom] =
                    '.'
            }

            if (isWhite) {
                whiteKingsideCastle = false
                whiteQueensideCastle = false
            } else {
                blackKingsideCastle = false
                blackQueensideCastle = false
            }

            board[toIndex] =
                movedPiece

            board[fromIndex] =
                '.'

            halfMoveClock++
            enPassantTarget = -1

            finishTurn()

            return generateFen()
        }

        val isPawn =
            movedPiece == 'P' ||
                    movedPiece == 'p'

        var wasEnPassantCapture =
            false

        if (
            isPawn &&
            toIndex == enPassantTarget &&
            kotlin.math.abs(
                toCol - fromCol
            ) == 1 &&
            capturedPiece == '.'
        ) {

            val capturedPawnRow =
                if (isWhite) {
                    toRow + 1
                } else {
                    toRow - 1
                }

            val capturedPawnIndex =
                capturedPawnRow * 8 +
                        toCol

            if (
                capturedPawnIndex in
                0..63
            ) {

                board[capturedPawnIndex] =
                    '.'

                wasEnPassantCapture =
                    true
            }
        }

        board[toIndex] =
            movedPiece

        board[fromIndex] =
            '.'

        when (movedPiece) {

            'K' -> {
                whiteKingsideCastle = false
                whiteQueensideCastle = false
            }

            'k' -> {
                blackKingsideCastle = false
                blackQueensideCastle = false
            }

            'R' -> when (fromIndex) {
                56 -> whiteQueensideCastle = false
                63 -> whiteKingsideCastle = false
            }

            'r' -> when (fromIndex) {
                0 -> blackQueensideCastle = false
                7 -> blackKingsideCastle = false
            }
        }

        if (capturedPiece == 'R') {
            when (toIndex) {
                56 -> whiteQueensideCastle = false
                63 -> whiteKingsideCastle = false
            }
        }

        if (capturedPiece == 'r') {
            when (toIndex) {
                0 -> blackQueensideCastle = false
                7 -> blackKingsideCastle = false
            }
        }

        enPassantTarget = -1

        if (
            isPawn &&
            kotlin.math.abs(
                toRow - fromRow
            ) == 2
        ) {

            val middleRow =
                (fromRow + toRow) / 2

            enPassantTarget =
                middleRow * 8 +
                        fromCol
        }

        halfMoveClock =
            if (
                isPawn ||
                capturedPiece != '.' ||
                wasEnPassantCapture
            ) {
                0
            } else {
                halfMoveClock + 1
            }

        finishTurn()

        return generateFen()
    }

    private fun finishTurn() {

        if (activeColor == 'w') {

            activeColor =
                'b'

        } else {

            activeColor =
                'w'

            fullMoveNumber++
        }
    }

    fun generateFen(): String {

        val fenRows =
            mutableListOf<String>()

        for (row in 0 until 8) {

            var emptyCount = 0

            val rowBuilder =
                StringBuilder()

            for (col in 0 until 8) {

                val piece =
                    board[
                        row * 8 +
                                col
                    ]

                if (piece == '.') {

                    emptyCount++

                } else {

                    if (emptyCount > 0) {

                        rowBuilder.append(
                            emptyCount
                        )

                        emptyCount = 0
                    }

                    rowBuilder.append(
                        piece
                    )
                }
            }

            if (emptyCount > 0) {
                rowBuilder.append(
                    emptyCount
                )
            }

            fenRows.add(
                rowBuilder.toString()
            )
        }

        return buildString {

            append(
                fenRows.joinToString("/")
            )

            append(" ")
            append(activeColor)

            append(" ")
            append(generateCastlingFen())

            append(" ")
            append(generateEnPassantFen())

            append(" ")
            append(halfMoveClock)

            append(" ")
            append(fullMoveNumber)
        }
    }

    private fun generateCastlingFen(): String {

        val result =
            StringBuilder()

        if (whiteKingsideCastle) {
            result.append('K')
        }

        if (whiteQueensideCastle) {
            result.append('Q')
        }

        if (blackKingsideCastle) {
            result.append('k')
        }

        if (blackQueensideCastle) {
            result.append('q')
        }

        return if (
            result.isEmpty()
        ) {
            "-"
        } else {
            result.toString()
        }
    }

    private fun generateEnPassantFen(): String {

        if (
            enPassantTarget !in
            0..63
        ) {
            return "-"
        }

        val file =
            'a' +
                    (
                            enPassantTarget %
                                    8
                            )

        val rank =
            8 -
                    (
                            enPassantTarget /
                                    8
                            )

        return "$file$rank"
    }

    /**
     * Replace the complete virtual position from a FEN.
     *
     * HARD RESYNC uses this after rebuilding the board from the CURRENT screen.
     *
     * Unknown castling rights are deliberately omitted from the supplied FEN,
     * so they become false here and Stockfish cannot recommend an uncertain
     * castle.
     */
    fun resetFromFen(
        fen: String,
        userIsWhite: Boolean = isUserWhite
    ): Boolean {

        val fields =
            fen
                .trim()
                .split(
                    Regex("\\s+")
                )

        if (fields.size < 4) {
            return false
        }


        val ranks =
            fields[0]
                .split("/")

        if (ranks.size != 8) {
            return false
        }


        val rebuilt =
            CharArray(64) {
                '.'
            }


        var index =
            0


        for (rank in ranks) {

            var filesInRank =
                0


            for (char in rank) {

                if (char.isDigit()) {

                    val emptyCount =
                        char.digitToInt()

                    if (
                        emptyCount !in
                        1..8
                    ) {
                        return false
                    }


                    repeat(
                        emptyCount
                    ) {

                        if (
                            index !in
                            0..63
                        ) {
                            return false
                        }

                        rebuilt[index++] =
                            '.'

                        filesInRank++
                    }

                } else {

                    if (
                        char !in
                        "prnbqkPRNBQK"
                    ) {
                        return false
                    }

                    if (
                        index !in
                        0..63
                    ) {
                        return false
                    }

                    rebuilt[index++] =
                        char

                    filesInRank++
                }
            }


            if (filesInRank != 8) {
                return false
            }
        }


        if (index != 64) {
            return false
        }


        val newActiveColor =
            when (fields[1]) {
                "w" -> 'w'
                "b" -> 'b'
                else -> return false
            }


        val castling =
            fields[2]


        if (
            castling != "-" &&
            castling.any {
                it !in
                        "KQkq"
            }
        ) {
            return false
        }


        val newEnPassant =
            if (fields[3] == "-") {

                -1

            } else {

                squareNameToIndex(
                    fields[3]
                ) ?: return false
            }


        val newHalfMoveClock =
            fields
                .getOrNull(4)
                ?.toIntOrNull()
                ?: 0


        val newFullMoveNumber =
            fields
                .getOrNull(5)
                ?.toIntOrNull()
                ?: 1


        for (i in 0 until 64) {
            board[i] =
                rebuilt[i]
        }


        activeColor =
            newActiveColor

        whiteKingsideCastle =
            'K' in castling

        whiteQueensideCastle =
            'Q' in castling

        blackKingsideCastle =
            'k' in castling

        blackQueensideCastle =
            'q' in castling

        enPassantTarget =
            newEnPassant

        halfMoveClock =
            maxOf(
                0,
                newHalfMoveClock
            )

        fullMoveNumber =
            maxOf(
                1,
                newFullMoveNumber
            )

        isUserWhite =
            userIsWhite


        return true
    }


    private fun squareNameToIndex(
        name: String
    ): Int? {

        if (name.length != 2) {
            return null
        }

        val file =
            name[0]

        val rank =
            name[1]


        if (
            file !in
            'a'..'h' ||
            rank !in
            '1'..'8'
        ) {
            return null
        }


        val col =
            file -
                    'a'

        val rankNumber =
            rank.digitToInt()

        val row =
            8 -
                    rankNumber


        return row * 8 +
                col
    }



    /*
     * ============================================================
     * LEGAL MOVE GENERATION FOR OPPONENT OVERLAY
     * ============================================================
     *
     * This does NOT call Stockfish and does NOT modify the live position.
     *
     * It is used only to draw the faint "all possible moves" arrows when the
     * opponent is to move. The current side-to-move is taken from activeColor.
     *
     * Returned strings use normal UCI:
     *   e2e4
     *   e7e8q
     */
    fun getAllLegalMovesUci(): List<String> {

        val movingWhite =
            activeColor == 'w'

        val result =
            mutableListOf<String>()

        for (from in 0 until 64) {

            val piece =
                board[from]

            if (
                piece == '.' ||
                piece.isUpperCase() != movingWhite
            ) {
                continue
            }

            val destinations =
                generatePseudoLegalDestinations(
                    from = from,
                    piece = piece
                )

            for (to in destinations) {

                /*
                 * Never generate a move that "captures" the enemy king.
                 * Check/checkmate is represented by attacks, not king capture.
                 */
                val target =
                    board[to]

                if (
                    target != '.' &&
                    target.lowercaseChar() == 'k'
                ) {
                    continue
                }

                val isPromotion =
                    piece.lowercaseChar() == 'p' &&
                            (
                                    (piece == 'P' && to / 8 == 0) ||
                                            (piece == 'p' && to / 8 == 7)
                                    )

                if (isPromotion) {

                    for (promotion in charArrayOf('q', 'r', 'b', 'n')) {

                        if (
                            isLegalMoveForCurrentSide(
                                from = from,
                                to = to,
                                promotion = promotion
                            )
                        ) {

                            result.add(
                                indexToSquareName(from) +
                                        indexToSquareName(to) +
                                        promotion
                            )
                        }
                    }

                } else {

                    if (
                        isLegalMoveForCurrentSide(
                            from = from,
                            to = to,
                            promotion = null
                        )
                    ) {

                        result.add(
                            indexToSquareName(from) +
                                    indexToSquareName(to)
                        )
                    }
                }
            }
        }

        return result
            .distinct()
            .sorted()
    }


    private fun generatePseudoLegalDestinations(
        from: Int,
        piece: Char
    ): List<Int> {

        val result =
            mutableListOf<Int>()

        val row =
            from / 8

        val col =
            from % 8

        val white =
            piece.isUpperCase()

        fun addIfEnemyOrEmpty(
            r: Int,
            c: Int
        ) {

            if (
                r !in 0..7 ||
                c !in 0..7
            ) {
                return
            }

            val idx =
                r * 8 + c

            val target =
                board[idx]

            if (
                target == '.' ||
                target.isUpperCase() != white
            ) {
                result.add(idx)
            }
        }


        fun addSliding(
            dr: Int,
            dc: Int
        ) {

            var r =
                row + dr

            var c =
                col + dc

            while (
                r in 0..7 &&
                c in 0..7
            ) {

                val idx =
                    r * 8 + c

                val target =
                    board[idx]

                if (target == '.') {

                    result.add(idx)

                } else {

                    if (
                        target.isUpperCase() != white
                    ) {
                        result.add(idx)
                    }

                    break
                }

                r += dr
                c += dc
            }
        }


        when (
            piece.lowercaseChar()
        ) {

            'p' -> {

                val direction =
                    if (white) -1 else 1

                val startRow =
                    if (white) 6 else 1

                val oneRow =
                    row + direction

                if (oneRow in 0..7) {

                    val one =
                        oneRow * 8 + col

                    if (board[one] == '.') {

                        result.add(one)

                        val twoRow =
                            row + direction * 2

                        if (
                            row == startRow &&
                            twoRow in 0..7
                        ) {

                            val two =
                                twoRow * 8 + col

                            if (board[two] == '.') {
                                result.add(two)
                            }
                        }
                    }


                    for (dc in intArrayOf(-1, 1)) {

                        val captureCol =
                            col + dc

                        if (captureCol !in 0..7) {
                            continue
                        }

                        val capture =
                            oneRow * 8 + captureCol

                        val target =
                            board[capture]

                        if (
                            target != '.' &&
                            target.isUpperCase() != white
                        ) {
                            result.add(capture)
                        } else if (
                            capture ==
                            enPassantTarget
                        ) {
                            result.add(capture)
                        }
                    }
                }
            }


            'n' -> {

                val jumps =
                    arrayOf(
                        -2 to -1,
                        -2 to 1,
                        -1 to -2,
                        -1 to 2,
                        1 to -2,
                        1 to 2,
                        2 to -1,
                        2 to 1
                    )

                for ((dr, dc) in jumps) {
                    addIfEnemyOrEmpty(
                        row + dr,
                        col + dc
                    )
                }
            }


            'b' -> {

                addSliding(-1, -1)
                addSliding(-1, 1)
                addSliding(1, -1)
                addSliding(1, 1)
            }


            'r' -> {

                addSliding(-1, 0)
                addSliding(1, 0)
                addSliding(0, -1)
                addSliding(0, 1)
            }


            'q' -> {

                addSliding(-1, -1)
                addSliding(-1, 1)
                addSliding(1, -1)
                addSliding(1, 1)

                addSliding(-1, 0)
                addSliding(1, 0)
                addSliding(0, -1)
                addSliding(0, 1)
            }


            'k' -> {

                for (dr in -1..1) {

                    for (dc in -1..1) {

                        if (
                            dr == 0 &&
                            dc == 0
                        ) {
                            continue
                        }

                        addIfEnemyOrEmpty(
                            row + dr,
                            col + dc
                        )
                    }
                }


                /*
                 * Castling.
                 *
                 * We validate:
                 * - stored right
                 * - king and rook are on home squares
                 * - path is empty
                 * - king is not currently in check
                 * - transit and destination squares are not attacked
                 */
                if (white && from == 60 && piece == 'K') {

                    if (
                        whiteKingsideCastle &&
                        board[63] == 'R' &&
                        board[61] == '.' &&
                        board[62] == '.' &&
                        !isSquareAttacked(60, byWhite = false) &&
                        !isSquareAttacked(61, byWhite = false) &&
                        !isSquareAttacked(62, byWhite = false)
                    ) {
                        result.add(62)
                    }

                    if (
                        whiteQueensideCastle &&
                        board[56] == 'R' &&
                        board[57] == '.' &&
                        board[58] == '.' &&
                        board[59] == '.' &&
                        !isSquareAttacked(60, byWhite = false) &&
                        !isSquareAttacked(59, byWhite = false) &&
                        !isSquareAttacked(58, byWhite = false)
                    ) {
                        result.add(58)
                    }

                } else if (
                    !white &&
                    from == 4 &&
                    piece == 'k'
                ) {

                    if (
                        blackKingsideCastle &&
                        board[7] == 'r' &&
                        board[5] == '.' &&
                        board[6] == '.' &&
                        !isSquareAttacked(4, byWhite = true) &&
                        !isSquareAttacked(5, byWhite = true) &&
                        !isSquareAttacked(6, byWhite = true)
                    ) {
                        result.add(6)
                    }

                    if (
                        blackQueensideCastle &&
                        board[0] == 'r' &&
                        board[1] == '.' &&
                        board[2] == '.' &&
                        board[3] == '.' &&
                        !isSquareAttacked(4, byWhite = true) &&
                        !isSquareAttacked(3, byWhite = true) &&
                        !isSquareAttacked(2, byWhite = true)
                    ) {
                        result.add(2)
                    }
                }
            }
        }

        return result
    }


    private fun isLegalMoveForCurrentSide(
        from: Int,
        to: Int,
        promotion: Char?
    ): Boolean {

        val snapshot =
            createSnapshot()

        val movingWhite =
            activeColor == 'w'

        return try {

            applyMoveForLegalityTest(
                from = from,
                to = to,
                promotion = promotion
            )

            val king =
                if (movingWhite) 'K' else 'k'

            val kingIndex =
                board.indexOfFirst {
                    it == king
                }

            kingIndex in 0..63 &&
                    !isSquareAttacked(
                        square = kingIndex,
                        byWhite = !movingWhite
                    )

        } finally {

            restoreSnapshot(
                snapshot
            )
        }
    }


    private fun applyMoveForLegalityTest(
        from: Int,
        to: Int,
        promotion: Char?
    ) {

        val piece =
            board[from]

        val fromRow =
            from / 8

        val fromCol =
            from % 8

        val toRow =
            to / 8

        val toCol =
            to % 8


        /*
         * En-passant capture.
         */
        if (
            piece.lowercaseChar() == 'p' &&
            to == enPassantTarget &&
            board[to] == '.' &&
            kotlin.math.abs(
                toCol - fromCol
            ) == 1
        ) {

            val capturedPawnRow =
                if (piece == 'P') {
                    toRow + 1
                } else {
                    toRow - 1
                }

            val capturedPawn =
                capturedPawnRow * 8 +
                        toCol

            if (capturedPawn in 0..63) {
                board[capturedPawn] = '.'
            }
        }


        /*
         * Castling rook movement.
         */
        if (
            piece.lowercaseChar() == 'k' &&
            fromRow == toRow &&
            kotlin.math.abs(
                toCol - fromCol
            ) == 2
        ) {

            if (toCol > fromCol) {

                val rookFrom =
                    fromRow * 8 + 7

                val rookTo =
                    fromRow * 8 + 5

                board[rookTo] =
                    board[rookFrom]

                board[rookFrom] =
                    '.'

            } else {

                val rookFrom =
                    fromRow * 8

                val rookTo =
                    fromRow * 8 + 3

                board[rookTo] =
                    board[rookFrom]

                board[rookFrom] =
                    '.'
            }
        }


        board[to] =
            if (
                promotion != null &&
                piece.lowercaseChar() == 'p'
            ) {

                if (piece.isUpperCase()) {
                    promotion.uppercaseChar()
                } else {
                    promotion.lowercaseChar()
                }

            } else {

                piece
            }

        board[from] =
            '.'
    }


    private fun isSquareAttacked(
        square: Int,
        byWhite: Boolean
    ): Boolean {

        val targetRow =
            square / 8

        val targetCol =
            square % 8


        /*
         * Pawn attacks.
         */
        val pawn =
            if (byWhite) 'P' else 'p'

        val pawnSourceRow =
            if (byWhite) {
                targetRow + 1
            } else {
                targetRow - 1
            }

        if (pawnSourceRow in 0..7) {

            for (dc in intArrayOf(-1, 1)) {

                val c =
                    targetCol + dc

                if (
                    c in 0..7 &&
                    board[
                        pawnSourceRow * 8 + c
                    ] == pawn
                ) {
                    return true
                }
            }
        }


        /*
         * Knight attacks.
         */
        val knight =
            if (byWhite) 'N' else 'n'

        val knightJumps =
            arrayOf(
                -2 to -1,
                -2 to 1,
                -1 to -2,
                -1 to 2,
                1 to -2,
                1 to 2,
                2 to -1,
                2 to 1
            )

        for ((dr, dc) in knightJumps) {

            val r =
                targetRow + dr

            val c =
                targetCol + dc

            if (
                r in 0..7 &&
                c in 0..7 &&
                board[
                    r * 8 + c
                ] == knight
            ) {
                return true
            }
        }


        /*
         * King attacks.
         */
        val king =
            if (byWhite) 'K' else 'k'

        for (dr in -1..1) {

            for (dc in -1..1) {

                if (
                    dr == 0 &&
                    dc == 0
                ) {
                    continue
                }

                val r =
                    targetRow + dr

                val c =
                    targetCol + dc

                if (
                    r in 0..7 &&
                    c in 0..7 &&
                    board[
                        r * 8 + c
                    ] == king
                ) {
                    return true
                }
            }
        }


        fun attackedBySlider(
            directions: Array<Pair<Int, Int>>,
            attackers: Set<Char>
        ): Boolean {

            for ((dr, dc) in directions) {

                var r =
                    targetRow + dr

                var c =
                    targetCol + dc

                while (
                    r in 0..7 &&
                    c in 0..7
                ) {

                    val piece =
                        board[
                            r * 8 + c
                        ]

                    if (piece != '.') {

                        if (piece in attackers) {
                            return true
                        }

                        break
                    }

                    r += dr
                    c += dc
                }
            }

            return false
        }


        val bishop =
            if (byWhite) 'B' else 'b'

        val rook =
            if (byWhite) 'R' else 'r'

        val queen =
            if (byWhite) 'Q' else 'q'


        if (
            attackedBySlider(
                directions =
                    arrayOf(
                        -1 to -1,
                        -1 to 1,
                        1 to -1,
                        1 to 1
                    ),
                attackers =
                    setOf(
                        bishop,
                        queen
                    )
            )
        ) {
            return true
        }


        if (
            attackedBySlider(
                directions =
                    arrayOf(
                        -1 to 0,
                        1 to 0,
                        0 to -1,
                        0 to 1
                    ),
                attackers =
                    setOf(
                        rook,
                        queen
                    )
            )
        ) {
            return true
        }


        return false
    }


    private fun indexToSquareName(
        index: Int
    ): String {

        val file =
            ('a'.code + index % 8)
                .toChar()

        val rank =
            8 - index / 8

        return "$file$rank"
    }


    fun resetToDefault(
        userIsWhite: Boolean
    ) {

        isUserWhite =
            userIsWhite

        activeColor =
            'w'

        fullMoveNumber =
            1

        halfMoveClock =
            0

        enPassantTarget =
            -1

        whiteKingsideCastle =
            true

        whiteQueensideCastle =
            true

        blackKingsideCastle =
            true

        blackQueensideCastle =
            true

        val defaultBoard =
            arrayOf(
                'r','n','b','q','k','b','n','r',
                'p','p','p','p','p','p','p','p',
                '.','.','.','.','.','.','.','.',
                '.','.','.','.','.','.','.','.',
                '.','.','.','.','.','.','.','.',
                '.','.','.','.','.','.','.','.',
                'P','P','P','P','P','P','P','P',
                'R','N','B','Q','K','B','N','R'
            )

        System.arraycopy(
            defaultBoard,
            0,
            board,
            0,
            64
        )
    }

    fun resetToDefault() =
        resetToDefault(true)
}
