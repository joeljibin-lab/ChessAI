package com.example.chessai

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ChessBoardRepository {
    var savedBoards by mutableStateOf(
        mutableListOf(
            ChessBoardItem(
                id = "1",
                name = "Chess Board Demo",
                timestamp = "Aug 21, 2026 - 10:00 AM",
                imageResId = R.drawable.chessboard_demo,
                boardBitmap = null,
                squareBitmaps = emptyList()
            )
        )
    )

    fun addBoard(name: String, fullBoardResId: Int, boardBmp: Bitmap?, squares: List<Bitmap>) {
        val newBoard = ChessBoardItem(
            id = System.currentTimeMillis().toString(),
            name = name,
            timestamp = "Aug 21, 2026 - 11:21 AM",
            imageResId = fullBoardResId,
            boardBitmap = boardBmp,
            squareBitmaps = squares
        )
        savedBoards.add(0, newBoard)
    }
}