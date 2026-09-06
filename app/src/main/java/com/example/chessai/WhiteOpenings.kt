package com.example.chessai

object WhiteOpenings {
    fun getMoveIndices(pieceType: String, side: String, specificPiece: String, distance: Int = 0): Pair<Int, Int>? {
        return if (pieceType == "knight") {
            if (side == "kingside") {
                Pair(62, 45) // White Knight g1 to f3
            } else {
                Pair(57, 42) // White Knight b1 to c3
            }
        } else {
            // Pawn movements for White (starting rows depend on board indexing, assuming standard 0-63 top-to-bottom)
            // Row 6 is White's starting pawn row in standard top-down array indexing (a2-h2)
            val col = when (specificPiece) {
                "rook" -> if (side == "kingside") 7 else 0   // h-pawn or a-pawn
                "knight" -> if (side == "kingside") 6 else 1 // g-pawn or b-pawn
                "bishop" -> if (side == "kingside") 5 else 2 // f-pawn or c-pawn
                "center" -> if (side == "kingside") 4 else 3 // e-pawn or d-pawn
                else -> 4
            }

            val startRow = 6 // rank 2
            val fromIndex = startRow * 8 + col
            val targetRow = startRow - distance
            val toIndex = targetRow * 8 + col

            Pair(fromIndex, toIndex)
        }
    }
}