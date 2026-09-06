package com.example.chessai

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transparent, touch-through move-arrow overlay.
 *
 * USER TURN:
 *   Draw only Stockfish's top 3:
 *     GREEN  = best
 *     YELLOW = second
 *     RED    = third
 *
 * OPPONENT TURN:
 *   Draw every legal move as a thin faint neutral arrow.
 *   Then draw Stockfish's top 3 on top using the same strong colors.
 *
 * The overlay never receives touch input.
 */
class RecommendationOverlayService : Service() {

    companion object {

        const val ACTION_UPDATE =
            "com.example.chessai.RECOMMENDATION_UPDATE"

        const val ACTION_HIDE =
            "com.example.chessai.RECOMMENDATION_HIDE"

        const val EXTRA_SIDE =
            "RECOMMENDATION_SIDE"

        const val EXTRA_LINES =
            "RECOMMENDATION_LINES"

        const val EXTRA_UCI_MOVES =
            "RECOMMENDATION_UCI_MOVES"

        const val EXTRA_ALL_LEGAL_MOVES =
            "RECOMMENDATION_ALL_LEGAL_MOVES"

        const val EXTRA_BOARD_X =
            "RECOMMENDATION_BOARD_X"

        const val EXTRA_BOARD_Y =
            "RECOMMENDATION_BOARD_Y"

        const val EXTRA_BOARD_WIDTH =
            "RECOMMENDATION_BOARD_WIDTH"

        const val EXTRA_BOARD_HEIGHT =
            "RECOMMENDATION_BOARD_HEIGHT"

        const val EXTRA_USER_IS_WHITE =
            "RECOMMENDATION_USER_IS_WHITE"
    }


    private var windowManager: WindowManager? =
        null

    private var overlayRoot: FrameLayout? =
        null

    private var boardMoveView: BoardMoveOverlayView? =
        null


    override fun onCreate() {

        super.onCreate()

        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager
    }


    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_HIDE -> {

                removeOverlay()
                stopSelf()
            }


            ACTION_UPDATE -> {

                val topMoves =
                    intent.getStringArrayListExtra(
                        EXTRA_UCI_MOVES
                    ) ?: arrayListOf()

                val allLegalMoves =
                    intent.getStringArrayListExtra(
                        EXTRA_ALL_LEGAL_MOVES
                    ) ?: arrayListOf()

                val boardX =
                    intent.getIntExtra(
                        EXTRA_BOARD_X,
                        0
                    )

                val boardY =
                    intent.getIntExtra(
                        EXTRA_BOARD_Y,
                        0
                    )

                val boardWidth =
                    intent.getIntExtra(
                        EXTRA_BOARD_WIDTH,
                        0
                    )

                val boardHeight =
                    intent.getIntExtra(
                        EXTRA_BOARD_HEIGHT,
                        0
                    )

                val userIsWhite =
                    intent.getBooleanExtra(
                        EXTRA_USER_IS_WHITE,
                        true
                    )


                showOrUpdate(
                    boardX = boardX,
                    boardY = boardY,
                    boardWidth = boardWidth,
                    boardHeight = boardHeight,
                    userIsWhite = userIsWhite,
                    topMoves = topMoves,
                    allLegalMoves = allLegalMoves
                )
            }
        }

        return START_NOT_STICKY
    }


    private fun showOrUpdate(
        boardX: Int,
        boardY: Int,
        boardWidth: Int,
        boardHeight: Int,
        userIsWhite: Boolean,
        topMoves: List<String>,
        allLegalMoves: List<String>
    ) {

        if (overlayRoot == null) {
            createOverlay()
        }

        boardMoveView?.updatePosition(
            boardX = boardX,
            boardY = boardY,
            boardWidth = boardWidth,
            boardHeight = boardHeight,
            userIsWhite = userIsWhite,
            topMoves = topMoves,
            allLegalMoves = allLegalMoves
        )
    }


    private fun createOverlay() {

        val root =
            FrameLayout(this).apply {

                setBackgroundColor(
                    Color.TRANSPARENT
                )
            }


        boardMoveView =
            BoardMoveOverlayView(
                this
            ).also {

                root.addView(
                    it,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }


        val layoutType =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY

            } else {

                @Suppress("DEPRECATION")
                WindowManager.LayoutParams
                    .TYPE_PHONE
            }


        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,

                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,

                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.START

                x = 0
                y = 0

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {

                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.R
                ) {
                    setFitInsetsTypes(0)
                }
            }


        overlayRoot =
            root


        try {

            windowManager?.addView(
                root,
                params
            )

        } catch (e: Exception) {

            overlayRoot = null
            boardMoveView = null

            throw e
        }
    }


    private fun removeOverlay() {

        val root =
            overlayRoot
                ?: return

        try {

            windowManager?.removeView(
                root
            )

        } catch (_: Exception) {
        }

        overlayRoot = null
        boardMoveView = null
    }


    private class BoardMoveOverlayView(
        context: android.content.Context
    ) : View(context) {

        private var boardX = 0
        private var boardY = 0
        private var boardWidth = 0
        private var boardHeight = 0

        private var userIsWhite =
            true

        private var topMoves:
                List<String> =
            emptyList()

        /*
         * Empty on the user's turn.
         * Filled with every legal move on the opponent's turn.
         */
        private var allLegalMoves:
                List<String> =
            emptyList()


        private val density =
            resources.displayMetrics.density


        private val faintPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                style =
                    Paint.Style.STROKE

                strokeWidth =
                    density * 1.6f

                strokeCap =
                    Paint.Cap.ROUND

                strokeJoin =
                    Paint.Join.ROUND

                /*
                 * Other legal opponent moves:
                 * black and a little more visible, while still staying
                 * clearly weaker than the colored Stockfish top-3 arrows.
                 */
                color =
                    Color.argb(
                        135,
                        0,
                        0,
                        0
                    )
            }


        private val strongPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                style =
                    Paint.Style.STROKE

                strokeWidth =
                    density * 4.0f

                strokeCap =
                    Paint.Cap.ROUND

                strokeJoin =
                    Paint.Join.ROUND
            }


        private val outlinePaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                style =
                    Paint.Style.STROKE

                strokeWidth =
                    density * 3.0f

                strokeJoin =
                    Paint.Join.ROUND
            }


        private val arrowHeadPath =
            Path()


        fun updatePosition(
            boardX: Int,
            boardY: Int,
            boardWidth: Int,
            boardHeight: Int,
            userIsWhite: Boolean,
            topMoves: List<String>,
            allLegalMoves: List<String>
        ) {

            this.boardX =
                boardX

            this.boardY =
                boardY

            this.boardWidth =
                boardWidth

            this.boardHeight =
                boardHeight

            this.userIsWhite =
                userIsWhite

            this.topMoves =
                topMoves
                    .take(3)

            this.allLegalMoves =
                allLegalMoves
                    .distinct()

            invalidate()
        }


        override fun onDraw(
            canvas: Canvas
        ) {

            super.onDraw(
                canvas
            )

            if (
                boardWidth <= 0 ||
                boardHeight <= 0
            ) {
                return
            }


            /*
             * ------------------------------------------------------------
             * 1) ALL LEGAL OPPONENT MOVES
             * ------------------------------------------------------------
             *
             * Draw first so the top 3 can sit clearly on top.
             */
            val topBaseMoves =
                topMoves
                    .mapNotNull {
                        normalizeBaseUci(it)
                    }
                    .toSet()


            allLegalMoves
                .mapNotNull {
                    normalizeBaseUci(it)
                }
                .distinct()
                .filterNot {
                    it in topBaseMoves
                }
                .forEach {

                    drawMoveArrow(
                        canvas = canvas,
                        uci = it,
                        paint = faintPaint,
                        strong = false
                    )
                }


            /*
             * ------------------------------------------------------------
             * 2) STOCKFISH TOP 3
             * ------------------------------------------------------------
             */
            val squareColors =
                linkedMapOf<
                        String,
                        MutableList<Int>
                        >()


            topMoves.forEachIndexed {
                    index,
                    rawMove ->

                val uci =
                    normalizeBaseUci(
                        rawMove
                    ) ?: return@forEachIndexed

                val color =
                    when (index) {

                        0 ->
                            Color.rgb(
                                0,
                                230,
                                118
                            )

                        1 ->
                            Color.rgb(
                                255,
                                214,
                                0
                            )

                        else ->
                            Color.rgb(
                                255,
                                82,
                                82
                            )
                    }


                val from =
                    uci.substring(
                        0,
                        2
                    )

                val to =
                    uci.substring(
                        2,
                        4
                    )


                squareColors
                    .getOrPut(from) {
                        mutableListOf()
                    }
                    .add(color)

                squareColors
                    .getOrPut(to) {
                        mutableListOf()
                    }
                    .add(color)


                strongPaint.color =
                    color


                drawMoveArrow(
                    canvas = canvas,
                    uci = uci,
                    paint = strongPaint,
                    strong = true
                )
            }


            /*
             * Draw the source/destination square outlines for the top 3.
             *
             * If several recommended moves share a square, nest the outlines
             * so each recommendation color stays visible.
             */
            squareColors.forEach {
                    square,
                    colors ->

                drawSquareOutlines(
                    canvas = canvas,
                    square = square,
                    colors = colors
                )
            }
        }


        private fun normalizeBaseUci(
            raw: String
        ): String? {

            val uci =
                raw
                    .trim()
                    .lowercase()

            if (uci.length < 4) {
                return null
            }

            val base =
                uci.substring(
                    0,
                    4
                )

            val from =
                base.substring(
                    0,
                    2
                )

            val to =
                base.substring(
                    2,
                    4
                )

            if (
                squareToScreenCell(from) == null ||
                squareToScreenCell(to) == null
            ) {
                return null
            }

            return base
        }


        private fun drawMoveArrow(
            canvas: Canvas,
            uci: String,
            paint: Paint,
            strong: Boolean
        ) {

            if (uci.length < 4) {
                return
            }

            val from =
                uci.substring(
                    0,
                    2
                )

            val to =
                uci.substring(
                    2,
                    4
                )

            val start =
                squareCenter(
                    from
                ) ?: return

            val destination =
                squareCenter(
                    to
                ) ?: return


            val dx =
                destination.first -
                        start.first

            val dy =
                destination.second -
                        start.second

            val length =
                sqrt(
                    dx * dx +
                            dy * dy
                )

            if (length < 1f) {
                return
            }


            /*
             * Keep the arrow tip slightly before the center of the destination
             * piece so the piece remains visible.
             */
            val squareSize =
                minOf(
                    boardWidth / 8f,
                    boardHeight / 8f
                )

            val endInset =
                squareSize *
                        if (strong) {
                            0.18f
                        } else {
                            0.24f
                        }

            val unitX =
                dx / length

            val unitY =
                dy / length

            /*
             * Keep the center of the SOURCE square clean.
             *
             * Screen recording captures this overlay. Starting an arrow exactly
             * at the source-piece center can contaminate the vacated square and
             * make move detection think the piece is still there.
             */
            val startInset =
                squareSize *
                        if (strong) {
                            0.32f
                        } else {
                            0.28f
                        }

            val startX =
                start.first +
                        unitX *
                        startInset

            val startY =
                start.second +
                        unitY *
                        startInset

            val endX =
                destination.first -
                        unitX *
                        endInset

            val endY =
                destination.second -
                        unitY *
                        endInset


            canvas.drawLine(
                startX,
                startY,
                endX,
                endY,
                paint
            )


            val angle =
                atan2(
                    endY - startY,
                    endX - startX
                )


            val headLength =
                squareSize *
                        if (strong) {
                            0.28f
                        } else {
                            0.18f
                        }

            val headHalfAngle =
                if (strong) {
                    Math.toRadians(28.0)
                } else {
                    Math.toRadians(24.0)
                }


            val leftX =
                endX -
                        headLength *
                        cos(
                            angle -
                                    headHalfAngle
                        ).toFloat()

            val leftY =
                endY -
                        headLength *
                        sin(
                            angle -
                                    headHalfAngle
                        ).toFloat()

            val rightX =
                endX -
                        headLength *
                        cos(
                            angle +
                                    headHalfAngle
                        ).toFloat()

            val rightY =
                endY -
                        headLength *
                        sin(
                            angle +
                                    headHalfAngle
                        ).toFloat()


            arrowHeadPath.reset()

            arrowHeadPath.moveTo(
                leftX,
                leftY
            )

            arrowHeadPath.lineTo(
                endX,
                endY
            )

            arrowHeadPath.lineTo(
                rightX,
                rightY
            )


            canvas.drawPath(
                arrowHeadPath,
                paint
            )
        }


        private fun drawSquareOutlines(
            canvas: Canvas,
            square: String,
            colors: List<Int>
        ) {

            val cell =
                squareToScreenCell(
                    square
                ) ?: return


            val squareWidth =
                boardWidth /
                        8f

            val squareHeight =
                boardHeight /
                        8f


            val rawLeft =
                boardX +
                        cell.first *
                        squareWidth

            val rawTop =
                boardY +
                        cell.second *
                        squareHeight

            val rawRight =
                rawLeft +
                        squareWidth

            val rawBottom =
                rawTop +
                        squareHeight


            colors.forEachIndexed {
                    index,
                    color ->

                val baseInset =
                    outlinePaint.strokeWidth /
                            2f +
                            density *
                            1.0f

                val nestedInset =
                    index *
                            density *
                            4.0f

                val inset =
                    baseInset +
                            nestedInset

                val rect =
                    RectF(
                        rawLeft + inset,
                        rawTop + inset,
                        rawRight - inset,
                        rawBottom - inset
                    )

                outlinePaint.color =
                    color

                canvas.drawRect(
                    rect,
                    outlinePaint
                )
            }
        }


        private fun squareCenter(
            square: String
        ): Pair<Float, Float>? {

            val cell =
                squareToScreenCell(
                    square
                ) ?: return null

            val squareWidth =
                boardWidth /
                        8f

            val squareHeight =
                boardHeight /
                        8f

            val centerX =
                boardX +
                        (
                                cell.first +
                                        0.5f
                                ) *
                        squareWidth

            val centerY =
                boardY +
                        (
                                cell.second +
                                        0.5f
                                ) *
                        squareHeight

            return centerX to centerY
        }


        /**
         * Convert a UCI square to the square's visible screen cell.
         *
         * White at bottom:
         *   top-left = a8
         *
         * Black at bottom:
         *   top-left = h1
         */
        private fun squareToScreenCell(
            square: String
        ): Pair<Int, Int>? {

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

            val fileIndex =
                file - 'a'

            val rankNumber =
                rank - '0'


            return if (userIsWhite) {

                val col =
                    fileIndex

                val row =
                    8 -
                            rankNumber

                col to row

            } else {

                val col =
                    7 -
                            fileIndex

                val row =
                    rankNumber -
                            1

                col to row
            }
        }
    }


    override fun onDestroy() {

        removeOverlay()

        super.onDestroy()
    }


    override fun onBind(
        intent: Intent?
    ): IBinder? =
        null
}
