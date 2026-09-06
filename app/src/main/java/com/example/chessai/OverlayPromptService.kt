package com.example.chessai

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

class OverlayPromptService : Service() {

    companion object {
        const val ACTION_SHOW_PROMOTION = "com.example.chessai.ACTION_SHOW_PROMOTION"

        const val ACTION_SHOW_HARD_RESYNC =
            "com.example.chessai.ACTION_SHOW_HARD_RESYNC"

        const val EXTRA_RESYNC_PLACEMENT =
            "RESYNC_PLACEMENT"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var chosenPieceType: String = ""
    private var chosenSide: String = ""
    private var chosenSpecificPiece: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {

            ACTION_SHOW_PROMOTION ->
                showPromotionFallback()

            ACTION_SHOW_HARD_RESYNC ->
                showHardResyncWizard(
                    placement =
                        intent.getStringExtra(
                            EXTRA_RESYNC_PLACEMENT
                        ) ?: ""
                )

            else ->
                showOverlay()
        }

        return START_NOT_STICKY
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val view = View.inflate(this, R.layout.dialog_overlay_prompt, null)
        val titleText = view.findViewById<TextView>(R.id.overlayTitle)
        val btnYes = view.findViewById<Button>(R.id.btnYes)
        val btnNo = view.findViewById<Button>(R.id.btnNo)
        val checkboxPlayAsBlack = view.findViewById<CheckBox>(R.id.checkboxPlayAsBlack)

        titleText?.text = "Is this the starting position?"

        btnYes?.setOnClickListener {
            val isPlayingAsWhite = !(checkboxPlayAsBlack?.isChecked ?: false)
            ScreenRecordService.selectedUserIsWhite = isPlayingAsWhite
            removeOverlay()
            if (!isPlayingAsWhite) showWhiteOpeningWizard() else finalizeBoardSetup()
        }

        btnNo?.setOnClickListener {
            ScreenRecordService.resetSession()
            removeOverlay()
            stopSelf()
        }

        overlayView = view
        try { windowManager?.addView(overlayView, params) } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showWhiteOpeningWizard() {
        val view = View.inflate(this, R.layout.dialog_white_opening_wizard, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        overlayView = view
        try {
            windowManager?.addView(overlayView, params)
            showWizardStep1(view)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun showWizardStep1(view: View) {
        val title = view.findViewById<TextView>(R.id.wizardTitle)
        val container = view.findViewById<LinearLayout>(R.id.wizardButtonContainer)
        val btnBack = view.findViewById<Button>(R.id.btnBack)
        title?.text = "Did a Pawn or a Knight move?"
        container?.removeAllViews()
        btnBack?.visibility = View.GONE

        container?.addView(Button(this).apply {
            text = "Pawn"; isAllCaps = false
            setOnClickListener { chosenPieceType = "pawn"; showWizardStep2Side(view) }
        })
        container?.addView(Button(this).apply {
            text = "Knight"; isAllCaps = false
            setOnClickListener { chosenPieceType = "knight"; showWizardStep2Side(view) }
        })
    }

    private fun showWizardStep2Side(view: View) {
        val title = view.findViewById<TextView>(R.id.wizardTitle)
        val container = view.findViewById<LinearLayout>(R.id.wizardButtonContainer)
        val btnBack = view.findViewById<Button>(R.id.btnBack)
        title?.text = "Which side?"
        container?.removeAllViews()
        btnBack?.visibility = View.VISIBLE
        btnBack?.setOnClickListener { showWizardStep1(view) }

        container?.addView(Button(this).apply {
            text = "Left Side (Queen's Side)"; isAllCaps = false
            setOnClickListener { chosenSide = "queenside"; proceedAfterSide(view) }
        })
        container?.addView(Button(this).apply {
            text = "Right Side (King's Side)"; isAllCaps = false
            setOnClickListener { chosenSide = "kingside"; proceedAfterSide(view) }
        })
    }

    private fun proceedAfterSide(view: View) {
        if (chosenPieceType == "knight") {
            applyOpeningAndFinish(WhiteOpenings.getMoveIndices("knight", chosenSide, ""))
        } else {
            showWizardStep3PawnChoice(view)
        }
    }

    private fun showWizardStep3PawnChoice(view: View) {
        val title = view.findViewById<TextView>(R.id.wizardTitle)
        val container = view.findViewById<LinearLayout>(R.id.wizardButtonContainer)
        val btnBack = view.findViewById<Button>(R.id.btnBack)
        title?.text = "Which pawn on that side?"
        container?.removeAllViews()
        btnBack?.setOnClickListener { showWizardStep2Side(view) }

        val pawnOptions = if (chosenSide == "kingside") {
            listOf("Center Pawn (e-pawn)" to "center", "Bishop's Pawn (f-pawn)" to "bishop", "Knight's Pawn (g-pawn)" to "knight", "Rook's Pawn (h-pawn)" to "rook")
        } else {
            listOf("Center Pawn (d-pawn)" to "center", "Bishop's Pawn (c-pawn)" to "bishop", "Knight's Pawn (b-pawn)" to "knight", "Rook's Pawn (a-pawn)" to "rook")
        }

        for ((label, code) in pawnOptions) {
            container?.addView(Button(this).apply {
                text = label; isAllCaps = false
                setOnClickListener { chosenSpecificPiece = code; showWizardStep4Distance(view) }
            })
        }
    }

    private fun showWizardStep4Distance(view: View) {
        val title = view.findViewById<TextView>(R.id.wizardTitle)
        val container = view.findViewById<LinearLayout>(R.id.wizardButtonContainer)
        val btnBack = view.findViewById<Button>(R.id.btnBack)
        title?.text = "How many squares forward?"
        container?.removeAllViews()
        btnBack?.setOnClickListener { showWizardStep3PawnChoice(view) }

        container?.addView(Button(this).apply {
            text = "2 Squares"; isAllCaps = false
            setOnClickListener { applyOpeningAndFinish(WhiteOpenings.getMoveIndices("pawn", chosenSide, chosenSpecificPiece, 2)) }
        })
        container?.addView(Button(this).apply {
            text = "1 Square"; isAllCaps = false
            setOnClickListener { applyOpeningAndFinish(WhiteOpenings.getMoveIndices("pawn", chosenSide, chosenSpecificPiece, 1)) }
        })
    }

    private fun showPromotionFallback() {
        if (overlayView != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 14, 20, 18)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }
        root.addView(TextView(this).apply {
            text = "Promotion not recognized. What did it become?"
            textSize = 16f
            gravity = Gravity.CENTER
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun addPieceButton(label: String, piece: Char) {
            row.addView(Button(this).apply {
                text = label; isAllCaps = false
                setOnClickListener {
                    startService(
                        Intent(
                            this@OverlayPromptService,
                            ScreenRecordService::class.java
                        ).apply {
                            action = ScreenRecordService.ACTION_PROMOTION_CHOICE
                            putExtra(
                                ScreenRecordService.EXTRA_PROMOTION_PIECE,
                                piece.toString()
                            )
                        }
                    )
                    removeOverlay()
                    stopSelf()
                }
            })
        }

        addPieceButton("Queen", 'q')
        addPieceButton("Rook", 'r')
        addPieceButton("Bishop", 'b')
        addPieceButton("Knight", 'n')
        root.addView(row)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        overlayView = root
        try { windowManager?.addView(overlayView, params) } catch (e: Exception) { e.printStackTrace() }
    }

    /*
     * ============================================================
     * HARD RESYNC METADATA WIZARD
     * ============================================================
     *
     * The board recognizer already supplied piece placement.
     *
     * This wizard asks only for information that a board image cannot prove:
     * - side to move
     * - castling rights
     *
     * En-passant is intentionally reset to "-".
     *
     * UNKNOWN castling is treated conservatively as NO in the FEN supplied to
     * Stockfish, but the UI still lets the user express that uncertainty.
     */

    private enum class CastleAnswer {
        YES,
        NO,
        UNKNOWN
    }


    private data class ResyncWizardState(
        val placement: String,
        var activeColor: Char? = null,
        var whiteKingside: CastleAnswer? = null,
        var whiteQueenside: CastleAnswer? = null,
        var blackKingside: CastleAnswer? = null,
        var blackQueenside: CastleAnswer? = null
    )


    private fun showHardResyncWizard(
        placement: String
    ) {

        /*
         * HARD RESYNC must always get its own metadata wizard.
         *
         * A stale setup/promotion overlay must never cause this request to be
         * silently ignored. Remove any existing overlay first.
         */
        if (placement.isBlank()) {

            sendHardResyncCancel()
            stopSelf()

            return
        }


        if (overlayView != null) {
            removeOverlay()
        }


        val state =
            ResyncWizardState(
                placement =
                    placement
            )


        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity =
                    Gravity.CENTER
            }


        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    28,
                    22,
                    28,
                    22
                )

                setBackgroundResource(
                    android.R.drawable.dialog_holo_light_frame
                )
            }


        val title =
            TextView(this).apply {

                textSize =
                    17f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.BLACK
                )

                setPadding(
                    10,
                    8,
                    10,
                    16
                )
            }


        val buttons =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER
            }


        val cancel =
            Button(this).apply {

                text =
                    "Cancel"

                isAllCaps =
                    false

                setOnClickListener {

                    startService(
                        Intent(
                            this@OverlayPromptService,
                            ScreenRecordService::class.java
                        ).apply {
                            action =
                                ScreenRecordService.ACTION_HARD_RESYNC_CANCEL
                        }
                    )

                    removeOverlay()
                    stopSelf()
                }
            }


        root.addView(
            title
        )

        root.addView(
            buttons
        )

        root.addView(
            cancel
        )


        fun setButtons(
            options: List<Pair<String, () -> Unit>>
        ) {

            buttons.removeAllViews()

            for (
            option in options
            ) {

                buttons.addView(
                    Button(this).apply {

                        text =
                            option.first

                        isAllCaps =
                            false

                        setOnClickListener {
                            option.second.invoke()
                        }
                    }
                )
            }
        }


        fun finishWizard() {

            /*
             * SAFETY RULE:
             * A HARD RESYNC may NEVER finish unless the user explicitly chose
             * whose turn it is and every applicable castling question has been
             * answered.
             *
             * In particular, NEVER default the active color to White.
             */
            val active =
                state.activeColor


            if (
                active != 'w' &&
                active != 'b'
            ) {

                sendHardResyncCancel()
                removeOverlay()
                stopSelf()

                return
            }


            if (
                state.whiteKingside == null ||
                state.whiteQueenside == null ||
                state.blackKingside == null ||
                state.blackQueenside == null
            ) {

                sendHardResyncCancel()
                removeOverlay()
                stopSelf()

                return
            }


            /*
             * UNKNOWN is deliberately NOT included.
             *
             * This means Stockfish will never recommend a castle unless the
             * user explicitly answered YES.
             */
            val castling =
                buildString {

                    if (
                        state.whiteKingside ==
                        CastleAnswer.YES
                    ) {
                        append('K')
                    }

                    if (
                        state.whiteQueenside ==
                        CastleAnswer.YES
                    ) {
                        append('Q')
                    }

                    if (
                        state.blackKingside ==
                        CastleAnswer.YES
                    ) {
                        append('k')
                    }

                    if (
                        state.blackQueenside ==
                        CastleAnswer.YES
                    ) {
                        append('q')
                    }
                }
                    .ifEmpty {
                        "-"
                    }


            val fen =
                "${state.placement} $active $castling - 0 1"


            startService(
                Intent(
                    this@OverlayPromptService,
                    ScreenRecordService::class.java
                ).apply {

                    action =
                        ScreenRecordService.ACTION_HARD_RESYNC_APPLY

                    putExtra(
                        ScreenRecordService.EXTRA_HARD_RESYNC_FEN,
                        fen
                    )

                    putExtra(
                        ScreenRecordService.EXTRA_CASTLE_WK_KNOWLEDGE,
                        state.whiteKingside?.name ?: CastleAnswer.UNKNOWN.name
                    )

                    putExtra(
                        ScreenRecordService.EXTRA_CASTLE_WQ_KNOWLEDGE,
                        state.whiteQueenside?.name ?: CastleAnswer.UNKNOWN.name
                    )

                    putExtra(
                        ScreenRecordService.EXTRA_CASTLE_BK_KNOWLEDGE,
                        state.blackKingside?.name ?: CastleAnswer.UNKNOWN.name
                    )

                    putExtra(
                        ScreenRecordService.EXTRA_CASTLE_BQ_KNOWLEDGE,
                        state.blackQueenside?.name ?: CastleAnswer.UNKNOWN.name
                    )
                }
            )


            removeOverlay()
            stopSelf()
        }


        /*
         * Only ask about rights that are visually still POSSIBLE.
         *
         * If a king or corresponding rook is not on its home square, that
         * right is definitely NO and we skip the question.
         */
        val board =
            expandFenPlacement(
                placement
            )


        if (board == null) {

            sendHardResyncCancel()
            removeOverlay()
            stopSelf()

            return
        }


        val whiteKingHome =
            board[60] == 'K'

        val blackKingHome =
            board[4] == 'k'


        val questions =
            mutableListOf<
                    Pair<
                            String,
                                (CastleAnswer) -> Unit
                            >
                    >()


        if (
            whiteKingHome &&
            board[63] == 'R'
        ) {

            questions.add(
                "Can WHITE still castle kingside?" to {
                        answer ->
                    state.whiteKingside =
                        answer
                }
            )

        } else {

            state.whiteKingside =
                CastleAnswer.NO
        }


        if (
            whiteKingHome &&
            board[56] == 'R'
        ) {

            questions.add(
                "Can WHITE still castle queenside?" to {
                        answer ->
                    state.whiteQueenside =
                        answer
                }
            )

        } else {

            state.whiteQueenside =
                CastleAnswer.NO
        }


        if (
            blackKingHome &&
            board[7] == 'r'
        ) {

            questions.add(
                "Can BLACK still castle kingside?" to {
                        answer ->
                    state.blackKingside =
                        answer
                }
            )

        } else {

            state.blackKingside =
                CastleAnswer.NO
        }


        if (
            blackKingHome &&
            board[0] == 'r'
        ) {

            questions.add(
                "Can BLACK still castle queenside?" to {
                        answer ->
                    state.blackQueenside =
                        answer
                }
            )

        } else {

            state.blackQueenside =
                CastleAnswer.NO
        }


        var questionIndex =
            0


        fun showNextCastleQuestion() {

            if (
                questionIndex >=
                questions.size
            ) {

                finishWizard()

                return
            }


            val question =
                questions[
                    questionIndex
                ]


            title.text =
                question.first


            setButtons(
                listOf(
                    "Yes" to {

                        question.second.invoke(
                            CastleAnswer.YES
                        )

                        questionIndex++

                        showNextCastleQuestion()
                    },

                    "No" to {

                        question.second.invoke(
                            CastleAnswer.NO
                        )

                        questionIndex++

                        showNextCastleQuestion()
                    },

                    "Unknown" to {

                        question.second.invoke(
                            CastleAnswer.UNKNOWN
                        )

                        questionIndex++

                        showNextCastleQuestion()
                    }
                )
            )
        }


        fun showSideToMoveQuestion() {

            title.text =
                "Whose turn is it now?\n" +
                        "Tip: the clock that is running belongs to the side to move."


            setButtons(
                listOf(
                    "White" to {

                        state.activeColor =
                            'w'

                        showNextCastleQuestion()
                    },

                    "Black" to {

                        state.activeColor =
                            'b'

                        showNextCastleQuestion()
                    }
                )
            )
        }


        overlayView =
            root


        try {

            windowManager?.addView(
                root,
                params
            )

            showSideToMoveQuestion()

        } catch (e: Exception) {

            e.printStackTrace()

            overlayView =
                null

            sendHardResyncCancel()
            stopSelf()
        }
    }


    private fun sendHardResyncCancel() {

        try {

            startService(
                Intent(
                    this,
                    ScreenRecordService::class.java
                ).apply {
                    action =
                        ScreenRecordService.ACTION_HARD_RESYNC_CANCEL
                }
            )

        } catch (_: Exception) {
        }
    }


    private fun expandFenPlacement(
        placement: String
    ): CharArray? {

        val ranks =
            placement.split("/")

        if (ranks.size != 8) {
            return null
        }


        val board =
            CharArray(64) {
                '.'
            }


        var index =
            0


        for (rank in ranks) {

            var files =
                0


            for (char in rank) {

                if (char.isDigit()) {

                    val count =
                        char.digitToInt()

                    if (
                        count !in
                        1..8
                    ) {
                        return null
                    }


                    repeat(
                        count
                    ) {

                        if (
                            index !in
                            0..63
                        ) {
                            return null
                        }

                        board[index++] =
                            '.'

                        files++
                    }

                } else {

                    if (
                        char !in
                        "prnbqkPRNBQK"
                    ) {
                        return null
                    }

                    if (
                        index !in
                        0..63
                    ) {
                        return null
                    }

                    board[index++] =
                        char

                    files++
                }
            }


            if (files != 8) {
                return null
            }
        }


        return if (
            index == 64
        ) {
            board
        } else {
            null
        }
    }


    private fun applyOpeningAndFinish(indices: Pair<Int, Int>?) {
        if (indices != null) ScreenRecordService.preAppliedOpeningMove = indices
        finalizeBoardSetup()
    }

    private fun finalizeBoardSetup() {
        val newBoardName = "Chess Board ${ChessBoardRepository.savedBoards.size + 1}"
        ChessBoardRepository.addBoard(
            name = newBoardName,
            fullBoardResId = R.drawable.chessboard_demo,
            boardBmp = ScreenRecordService.pendingBoardBitmap,
            squares = ScreenRecordService.pendingSquareBitmaps
        )
        ScreenRecordService.isBoardSetupConfirmed = true
        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}
