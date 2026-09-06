package com.example.chessai

import android.graphics.Bitmap

data class ChessBoardItem(
    val id: String,
    var name: String,
    val timestamp: String,
    val imageResId: Int,
    val boardBitmap: Bitmap? = null, // Stores the full uncropped board
    val squareBitmaps: List<Bitmap> = emptyList()
)