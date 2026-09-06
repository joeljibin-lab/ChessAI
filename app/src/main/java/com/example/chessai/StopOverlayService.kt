package com.example.chessai

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating recording controls:
 *
 * RESYNC | PAUSE/RESUME | NEW | HIDE/SHOW | STOP
 *
 * Also owns the invisible outside-board touch guard.
 */
class StopOverlayService : Service() {

    companion object {

        const val ACTION_SHOW =
            "com.example.chessai.STOP_OVERLAY_SHOW"

        const val ACTION_HIDE =
            "com.example.chessai.STOP_OVERLAY_HIDE"

        const val ACTION_UPDATE_BOARD =
            "com.example.chessai.STOP_OVERLAY_UPDATE_BOARD"

        const val ACTION_SET_RECOMMENDATIONS_VISIBLE =
            "com.example.chessai.SET_RECOMMENDATIONS_VISIBLE"

        const val ACTION_SET_RESYNC_STATE =
            "com.example.chessai.SET_RESYNC_STATE"

        const val ACTION_SET_PAUSE_STATE =
            "com.example.chessai.SET_PAUSE_STATE"

        const val ACTION_SET_MISSED_STATE =
            "com.example.chessai.SET_MISSED_STATE"

        const val EXTRA_MISSED_STATE =
            "MISSED_STATE"

        const val MISSED_IDLE =
            "IDLE"

        const val MISSED_TAP_TO =
            "TAP_TO"

        const val MISSED_TAP_FROM =
            "TAP_FROM"

        const val MISSED_SUCCESS =
            "SUCCESS"

        const val MISSED_FAILED =
            "FAILED"

        const val EXTRA_PAUSED =
            "TRACKING_PAUSED"

        const val EXTRA_BOARD_X =
            "STOP_GUARD_BOARD_X"

        const val EXTRA_BOARD_Y =
            "STOP_GUARD_BOARD_Y"

        const val EXTRA_BOARD_WIDTH =
            "STOP_GUARD_BOARD_WIDTH"

        const val EXTRA_BOARD_HEIGHT =
            "STOP_GUARD_BOARD_HEIGHT"

        const val EXTRA_RECOMMENDATIONS_VISIBLE =
            "RECOMMENDATIONS_VISIBLE"

        const val EXTRA_RESYNC_STATE =
            "RESYNC_STATE"

        const val RESYNC_IDLE =
            "IDLE"

        const val RESYNC_RUNNING =
            "RUNNING"

        const val RESYNC_SUCCESS =
            "SUCCESS"

        const val RESYNC_FAILED =
            "FAILED"
    }

    private var windowManager: WindowManager? = null

    /*
     * Two separate overlay windows:
     *
     * LEFT:  RESYNC
     * RIGHT: HIDE/SHOW + STOP
     *
     * Keeping RESYNC in its own window means changing its text to
     * "RESYNCING..." or "RESYNCED" cannot push the other buttons around.
     */
    private var resyncView: TextView? = null
    private var resyncParams: WindowManager.LayoutParams? = null

    private var rightControlsView: LinearLayout? = null
    private var rightControlsParams: WindowManager.LayoutParams? = null

    private var resyncButton: TextView? = null
    private var pauseButton: TextView? = null
    private var missedButton: TextView? = null
    private var newSessionButton: TextView? = null
    private var recommendationButton: TextView? = null
    private var stopButton: TextView? = null

    private val blockerViews =
        mutableListOf<View>()

    /*
     * Transparent board-only touch catcher used only while MISSED repair is
     * asking for destination/source squares.
     */
    private var missedTapView: View? = null
    private var missedTapParams: WindowManager.LayoutParams? = null

    private var missedState =
        MISSED_IDLE

    private var recommendationsVisible =
        true

    private var trackingPaused =
        false

    private var lastBoardX =
        Int.MIN_VALUE

    private var lastBoardY =
        Int.MIN_VALUE

    private var lastBoardWidth =
        Int.MIN_VALUE

    private var lastBoardHeight =
        Int.MIN_VALUE

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

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
                removeAllWindows()
                stopSelf()
            }

            ACTION_SHOW -> {
                ensureControlsVisible()
            }

            ACTION_UPDATE_BOARD -> {

                ensureControlsVisible()

                updateTouchGuard(
                    boardX =
                        intent.getIntExtra(
                            EXTRA_BOARD_X,
                            0
                        ),
                    boardY =
                        intent.getIntExtra(
                            EXTRA_BOARD_Y,
                            0
                        ),
                    boardWidth =
                        intent.getIntExtra(
                            EXTRA_BOARD_WIDTH,
                            0
                        ),
                    boardHeight =
                        intent.getIntExtra(
                            EXTRA_BOARD_HEIGHT,
                            0
                        )
                )
            }

            ACTION_SET_RECOMMENDATIONS_VISIBLE -> {

                recommendationsVisible =
                    intent.getBooleanExtra(
                        EXTRA_RECOMMENDATIONS_VISIBLE,
                        true
                    )

                ensureControlsVisible()
                refreshRecommendationButton()
            }

            ACTION_SET_PAUSE_STATE -> {

                trackingPaused =
                    intent.getBooleanExtra(
                        EXTRA_PAUSED,
                        false
                    )

                ensureControlsVisible()
                refreshPauseButton()


                if (trackingPaused) {

                    /*
                     * PAUSE means the user may leave the chess app and touch
                     * anywhere on the phone.
                     *
                     * Remove ONLY the invisible outside-board touch blockers.
                     * Keep the floating controls visible/clickable.
                     */
                    removeBlockers()

                } else {

                    /*
                     * RESUME should restore the outside-board guard using the
                     * most recently known chessboard coordinates.
                     *
                     * updateTouchGuard() normally skips identical coordinates,
                     * so invalidate the cached values first to force recreation
                     * of the blocker windows.
                     */
                    val boardX =
                        lastBoardX

                    val boardY =
                        lastBoardY

                    val boardWidth =
                        lastBoardWidth

                    val boardHeight =
                        lastBoardHeight


                    if (
                        boardX != Int.MIN_VALUE &&
                        boardY != Int.MIN_VALUE &&
                        boardWidth > 0 &&
                        boardHeight > 0
                    ) {

                        lastBoardX =
                            Int.MIN_VALUE

                        lastBoardY =
                            Int.MIN_VALUE

                        lastBoardWidth =
                            Int.MIN_VALUE

                        lastBoardHeight =
                            Int.MIN_VALUE


                        updateTouchGuard(
                            boardX =
                                boardX,
                            boardY =
                                boardY,
                            boardWidth =
                                boardWidth,
                            boardHeight =
                                boardHeight
                        )
                    }
                }
            }


            ACTION_SET_RESYNC_STATE -> {

                val state =
                    intent.getStringExtra(
                        EXTRA_RESYNC_STATE
                    ) ?: RESYNC_IDLE

                ensureControlsVisible()
                setResyncState(state)
            }


            ACTION_SET_MISSED_STATE -> {

                val state =
                    intent.getStringExtra(
                        EXTRA_MISSED_STATE
                    ) ?: MISSED_IDLE

                ensureControlsVisible()
                setMissedState(
                    state
                )
            }
        }

        return START_NOT_STICKY
    }

    private fun ensureControlsVisible() {

        if (
            rightControlsView == null
        ) {
            createControls()
        }
    }


    private fun createControls() {

        /*
         * Clean up any half-created state first.
         */
        removeControls()


        val density =
            resources.displayMetrics.density


        /*
         * RESYNC BUTTON REMOVED.
         *
         * The hard-resync code/constants are deliberately left intact for
         * compatibility, but no visible RESYNC control is created here.
         * MISSED remains the manual one-ply correction tool.
         */


        /*
         * ============================================================
         * RIGHT SIDE — PAUSE + NEW + HIDE/SHOW + STOP
         * ============================================================
         */
        val rightRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                setBackgroundColor(
                    Color.TRANSPARENT
                )
            }


        /*
         * PAUSE / RESUME
         *
         * PAUSE keeps the screen-capture permission/session alive but tells
         * ScreenRecordService to discard all incoming frames.
         *
         * RESUME returns to chess tracking without changing the virtual FEN.
         */
        pauseButton =
            createButton(
                text = "PAUSE",
                backgroundColor =
                    Color.argb(
                        220,
                        105,
                        75,
                        160
                    )
            ).apply {

                setOnClickListener {

                    try {

                        startService(
                            Intent(
                                this@StopOverlayService,
                                ScreenRecordService::class.java
                            ).apply {
                                action =
                                    ScreenRecordService.ACTION_TOGGLE_PAUSE
                            }
                        )

                    } catch (_: Exception) {
                    }
                }
            }


        /*
         * MISSED MOVE
         *
         * Destination-first one-ply manual repair.
         */
        missedButton =
            createButton(
                text = "MISSED",
                backgroundColor =
                    Color.argb(
                        220,
                        35,
                        125,
                        150
                    )
            ).apply {

                setOnClickListener {

                    try {

                        startService(
                            Intent(
                                this@StopOverlayService,
                                ScreenRecordService::class.java
                            ).apply {
                                action =
                                    ScreenRecordService
                                        .ACTION_MANUAL_MISSED_TOGGLE
                            }
                        )

                    } catch (_: Exception) {
                    }
                }
            }


        /*
         * NEW SESSION
         *
         * Equivalent to:
         *   Stop Recording -> Start Recording
         *
         * Permanent board-recognition templates are NOT deleted.
         *
         * Android requires a fresh MediaProjection grant for a truly new
         * recording session, so MainActivity will immediately open the normal
         * capture-permission flow after the current capture has stopped.
         */
        newSessionButton =
            createButton(
                text = "NEW",
                backgroundColor =
                    Color.argb(
                        220,
                        205,
                        120,
                        25
                    )
            ).apply {

                setOnClickListener {

                    isEnabled =
                        false

                    alpha =
                        0.75f


                    try {

                        startService(
                            Intent(
                                this@StopOverlayService,
                                ScreenRecordService::class.java
                            ).apply {
                                action =
                                    ScreenRecordService.ACTION_STOP
                            }
                        )

                    } catch (_: Exception) {
                    }


                    /*
                     * Remove controls + outside-board blockers immediately so
                     * the Android capture-permission UI cannot be obstructed.
                     */
                    removeAllWindows()


                    /*
                     * Give the old MediaProjection a short moment to release
                     * before requesting a brand-new session.
                     */
                    mainHandler.postDelayed(
                        {

                            try {

                                startActivity(
                                    Intent(
                                        this@StopOverlayService,
                                        MainActivity::class.java
                                    ).apply {

                                        addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        )

                                        putExtra(
                                            MainActivity.EXTRA_AUTO_START_NEW_SESSION,
                                            true
                                        )
                                    }
                                )

                            } catch (_: Exception) {
                            }


                            stopSelf()
                        },
                        350L
                    )
                }
            }


        recommendationButton =
            createButton(
                text = "HIDE",
                backgroundColor =
                    Color.argb(
                        220,
                        90,
                        90,
                        90
                    )
            ).apply {

                setOnClickListener {

                    try {

                        startService(
                            Intent(
                                this@StopOverlayService,
                                ScreenRecordService::class.java
                            ).apply {
                                action =
                                    ScreenRecordService.ACTION_TOGGLE_RECOMMENDATIONS
                            }
                        )

                    } catch (_: Exception) {
                    }
                }
            }


        stopButton =
            createButton(
                text = "STOP",
                backgroundColor =
                    Color.argb(
                        220,
                        190,
                        35,
                        35
                    )
            ).apply {

                setOnClickListener {

                    try {

                        startService(
                            Intent(
                                this@StopOverlayService,
                                ScreenRecordService::class.java
                            ).apply {
                                action =
                                    ScreenRecordService.ACTION_STOP
                            }
                        )

                    } catch (_: Exception) {
                    }


                    removeAllWindows()
                    stopSelf()
                }
            }


        rightRow.addView(
            pauseButton
        )

        rightRow.addView(
            missedButton
        )

        rightRow.addView(
            newSessionButton
        )

        rightRow.addView(
            recommendationButton
        )

        rightRow.addView(
            stopButton
        )


        val rightParams =
            createBaseParams(
                width =
                    WindowManager.LayoutParams.WRAP_CONTENT,
                height =
                    WindowManager.LayoutParams.WRAP_CONTENT,
                touchable =
                    true
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.END

                x =
                    (8 * density).toInt()

                y =
                    (68 * density).toInt()
            }


        rightControlsView =
            rightRow

        rightControlsParams =
            rightParams


        try {

            windowManager?.addView(
                rightRow,
                rightParams
            )

        } catch (e: Exception) {

            /*
             * RESYNC UI no longer exists, so there is no left-side view
             * to remove here. Clean up only the controls that still exist.
             */
            resyncButton = null
            resyncView = null
            resyncParams = null

            rightControlsView = null
            rightControlsParams = null

            pauseButton = null
            missedButton = null
            newSessionButton = null
            recommendationButton = null
            stopButton = null

            throw e
        }


        refreshRecommendationButton()
        refreshPauseButton()
        refreshMissedButton()
    }


    private fun createButton(
        text: String,
        backgroundColor: Int
    ): TextView {

        val density =
            resources.displayMetrics.density

        return TextView(this).apply {

            this.text =
                text

            gravity =
                Gravity.CENTER

            setTextColor(
                Color.WHITE
            )

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                12f
            )

            setTypeface(
                typeface,
                Typeface.BOLD
            )

            setPadding(
                (11 * density).toInt(),
                (7 * density).toInt(),
                (11 * density).toInt(),
                (7 * density).toInt()
            )

            background =
                GradientDrawable().apply {

                    setColor(
                        backgroundColor
                    )

                    cornerRadius =
                        14f * density

                    setStroke(
                        maxOf(
                            1,
                            density.toInt()
                        ),
                        Color.WHITE
                    )
                }

            val margin =
                (4 * density).toInt()

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart =
                        margin

                    marginEnd =
                        margin
                }
        }
    }

    private fun refreshRecommendationButton() {

        recommendationButton?.text =
            if (recommendationsVisible) {
                "HIDE"
            } else {
                "SHOW"
            }
    }

    private fun refreshPauseButton() {

        pauseButton?.apply {

            text =
                if (trackingPaused) {
                    "RESUME"
                } else {
                    "PAUSE"
                }

            alpha =
                1.0f

            isEnabled =
                true
        }
    }


    private fun refreshMissedButton() {

        missedButton?.apply {

            when (missedState) {

                MISSED_TAP_TO -> {
                    text = "TAP TO"
                    isEnabled = true
                    alpha = 1.0f
                }

                MISSED_TAP_FROM -> {
                    text = "TAP FROM"
                    isEnabled = true
                    alpha = 1.0f
                }

                MISSED_SUCCESS -> {
                    text = "✓ FIXED"
                    isEnabled = false
                    alpha = 1.0f
                }

                MISSED_FAILED -> {
                    text = "! MISSED"
                    isEnabled = true
                    alpha = 1.0f
                }

                else -> {
                    text = "MISSED"
                    isEnabled = true
                    alpha = 1.0f
                }
            }
        }
    }


    private fun setMissedState(
        state: String
    ) {

        missedState =
            state


        when (state) {

            MISSED_TAP_TO,
            MISSED_TAP_FROM -> {

                refreshMissedButton()
                ensureMissedTapOverlay()
            }

            MISSED_SUCCESS -> {

                removeMissedTapOverlay()
                refreshMissedButton()

                mainHandler.postDelayed(
                    {
                        setMissedState(
                            MISSED_IDLE
                        )
                    },
                    1300L
                )
            }

            MISSED_FAILED -> {

                removeMissedTapOverlay()
                refreshMissedButton()

                mainHandler.postDelayed(
                    {
                        setMissedState(
                            MISSED_IDLE
                        )
                    },
                    1500L
                )
            }

            else -> {

                removeMissedTapOverlay()
                refreshMissedButton()
            }
        }
    }


    private fun ensureMissedTapOverlay() {

        if (
            missedState != MISSED_TAP_TO &&
            missedState != MISSED_TAP_FROM
        ) {
            removeMissedTapOverlay()
            return
        }


        if (
            lastBoardX == Int.MIN_VALUE ||
            lastBoardY == Int.MIN_VALUE ||
            lastBoardWidth <= 0 ||
            lastBoardHeight <= 0
        ) {
            return
        }


        removeMissedTapOverlay()


        val boardX =
            lastBoardX

        val boardY =
            lastBoardY

        val boardWidth =
            lastBoardWidth

        val boardHeight =
            lastBoardHeight


        val tapView =
            View(this).apply {

                setBackgroundColor(
                    Color.TRANSPARENT
                )

                setOnTouchListener {
                        _,
                        event ->

                    if (
                        event.action ==
                        MotionEvent.ACTION_UP
                    ) {

                        val squareWidth =
                            boardWidth.toFloat() /
                                    8f

                        val squareHeight =
                            boardHeight.toFloat() /
                                    8f

                        val col =
                            (event.x / squareWidth)
                                .toInt()
                                .coerceIn(
                                    0,
                                    7
                                )

                        val row =
                            (event.y / squareHeight)
                                .toInt()
                                .coerceIn(
                                    0,
                                    7
                                )


                        /*
                         * Screen board may be White-bottom or Black-bottom.
                         * VirtualChessBoard is always 0=a8 ... 63=h1.
                         */
                        val standardIndex =
                            if (
                                ScreenRecordService
                                    .selectedUserIsWhite
                            ) {

                                row * 8 +
                                        col

                            } else {

                                (7 - row) * 8 +
                                        (7 - col)
                            }


                        try {

                            startService(
                                Intent(
                                    this@StopOverlayService,
                                    ScreenRecordService::class.java
                                ).apply {

                                    action =
                                        ScreenRecordService
                                            .ACTION_MANUAL_MISSED_SQUARE

                                    putExtra(
                                        ScreenRecordService
                                            .EXTRA_MANUAL_MISSED_SQUARE,
                                        standardIndex
                                    )
                                }
                            )

                        } catch (_: Exception) {
                        }
                    }


                    true
                }
            }


        val params =
            createBaseParams(
                width =
                    boardWidth,
                height =
                    boardHeight,
                touchable =
                    true
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.START

                x =
                    boardX

                y =
                    boardY
            }


        try {

            windowManager?.addView(
                tapView,
                params
            )

            missedTapView =
                tapView

            missedTapParams =
                params


            /*
             * Keep the floating controls clickable above the board catcher.
             */
            bringControlsToFront()

        } catch (_: Exception) {

            missedTapView =
                null

            missedTapParams =
                null
        }
    }


    private fun removeMissedTapOverlay() {

        missedTapView?.let {
                view ->

            try {
                windowManager?.removeView(
                    view
                )
            } catch (_: Exception) {
            }
        }


        missedTapView =
            null

        missedTapParams =
            null
    }


    private fun setResyncState(
        state: String
    ) {

        when (state) {

            RESYNC_RUNNING -> {

                resyncButton?.apply {
                    text =
                        "⟳ RESYNCING..."

                    isEnabled =
                        false

                    alpha =
                        0.85f
                }
            }

            RESYNC_SUCCESS -> {

                resyncButton?.apply {
                    text =
                        "✓ RESYNCED"

                    isEnabled =
                        false

                    alpha =
                        1.0f
                }

                mainHandler.postDelayed(
                    {
                        setResyncState(
                            RESYNC_IDLE
                        )
                    },
                    1500L
                )
            }

            RESYNC_FAILED -> {

                resyncButton?.apply {
                    text =
                        "! RESYNC"

                    isEnabled =
                        true

                    alpha =
                        1.0f
                }

                mainHandler.postDelayed(
                    {
                        setResyncState(
                            RESYNC_IDLE
                        )
                    },
                    1800L
                )
            }

            else -> {

                resyncButton?.apply {
                    text =
                        "RESYNC"

                    isEnabled =
                        true

                    alpha =
                        1.0f
                }
            }
        }
    }

    private fun updateTouchGuard(
        boardX: Int,
        boardY: Int,
        boardWidth: Int,
        boardHeight: Int
    ) {

        ensureControlsVisible()

        if (
            boardWidth <= 0 ||
            boardHeight <= 0
        ) {
            return
        }

        if (
            boardX == lastBoardX &&
            boardY == lastBoardY &&
            boardWidth == lastBoardWidth &&
            boardHeight == lastBoardHeight
        ) {
            return
        }

        lastBoardX =
            boardX

        lastBoardY =
            boardY

        lastBoardWidth =
            boardWidth

        lastBoardHeight =
            boardHeight

        removeBlockers()

        val screenWidth =
            resources.displayMetrics.widthPixels

        val screenHeight =
            resources.displayMetrics.heightPixels

        val density =
            resources.displayMetrics.density

        val allowance =
            (3f * density).toInt()

        val safeLeft =
            (boardX - allowance)
                .coerceIn(
                    0,
                    screenWidth
                )

        val safeTop =
            (boardY - allowance)
                .coerceIn(
                    0,
                    screenHeight
                )

        val safeRight =
            (
                    boardX +
                            boardWidth +
                            allowance
                    )
                .coerceIn(
                    0,
                    screenWidth
                )

        val safeBottom =
            (
                    boardY +
                            boardHeight +
                            allowance
                    )
                .coerceIn(
                    0,
                    screenHeight
                )

        addBlocker(
            x = 0,
            y = 0,
            width =
                screenWidth,
            height =
                safeTop
        )

        addBlocker(
            x = 0,
            y = safeBottom,
            width =
                screenWidth,
            height =
                screenHeight -
                        safeBottom
        )

        addBlocker(
            x = 0,
            y = safeTop,
            width =
                safeLeft,
            height =
                safeBottom -
                        safeTop
        )

        addBlocker(
            x = safeRight,
            y = safeTop,
            width =
                screenWidth -
                        safeRight,
            height =
                safeBottom -
                        safeTop
        )

        if (
            missedState == MISSED_TAP_TO ||
            missedState == MISSED_TAP_FROM
        ) {
            ensureMissedTapOverlay()
        } else {
            bringControlsToFront()
        }
    }

    private fun addBlocker(
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {

        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val blocker =
            View(this).apply {

                setBackgroundColor(
                    Color.TRANSPARENT
                )

                setOnTouchListener {
                        _,
                        _: MotionEvent ->
                    true
                }
            }

        val params =
            createBaseParams(
                width =
                    width,
                height =
                    height,
                touchable =
                    true
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.START

                this.x =
                    x

                this.y =
                    y
            }

        try {

            windowManager?.addView(
                blocker,
                params
            )

            blockerViews.add(
                blocker
            )

        } catch (_: Exception) {
        }
    }

    private fun createBaseParams(
        width: Int,
        height: Int,
        touchable: Boolean
    ): WindowManager.LayoutParams {

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

        var flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (!touchable) {
            flags =
                flags or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return WindowManager.LayoutParams(
            width,
            height,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {

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

                setFitInsetsTypes(
                    0
                )
            }
        }
    }

    private fun bringControlsToFront() {

        ensureControlsVisible()


        /*
         * Re-add both control windows AFTER the blockers so they stay
         * clickable above the transparent touch guard.
         */
        val left =
            resyncView

        val leftParams =
            resyncParams

        if (
            left != null &&
            leftParams != null
        ) {

            try {
                windowManager?.removeView(
                    left
                )
            } catch (_: Exception) {
            }

            try {

                windowManager?.addView(
                    left,
                    leftParams
                )

            } catch (_: Exception) {

                resyncView = null
                resyncParams = null
                resyncButton = null
            }
        }


        val right =
            rightControlsView

        val rightParams =
            rightControlsParams

        if (
            right != null &&
            rightParams != null
        ) {

            try {
                windowManager?.removeView(
                    right
                )
            } catch (_: Exception) {
            }

            try {

                windowManager?.addView(
                    right,
                    rightParams
                )

            } catch (_: Exception) {

                rightControlsView = null
                rightControlsParams = null
                pauseButton = null
                missedButton = null
                newSessionButton = null
                recommendationButton = null
                stopButton = null
            }
        }


        /*
         * If either window failed to re-add, recreate the missing controls.
         */
        if (
            resyncView == null ||
            rightControlsView == null
        ) {
            createControls()
        }
    }


    private fun removeBlockers() {

        blockerViews.forEach {
                view ->

            try {
                windowManager?.removeView(
                    view
                )
            } catch (_: Exception) {
            }
        }

        blockerViews.clear()
    }

    private fun removeControls() {

        resyncView?.let {
                view ->

            try {
                windowManager?.removeView(
                    view
                )
            } catch (_: Exception) {
            }
        }


        rightControlsView?.let {
                view ->

            try {
                windowManager?.removeView(
                    view
                )
            } catch (_: Exception) {
            }
        }


        resyncView =
            null

        resyncParams =
            null

        rightControlsView =
            null

        rightControlsParams =
            null

        resyncButton =
            null

        pauseButton =
            null

        missedButton =
            null

        newSessionButton =
            null

        recommendationButton =
            null

        stopButton =
            null
    }


    private fun removeAllWindows() {

        removeMissedTapOverlay()
        removeBlockers()
        removeControls()

        missedState =
            MISSED_IDLE

        lastBoardX =
            Int.MIN_VALUE

        lastBoardY =
            Int.MIN_VALUE

        lastBoardWidth =
            Int.MIN_VALUE

        lastBoardHeight =
            Int.MIN_VALUE
    }

    override fun onDestroy() {

        removeAllWindows()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? =
        null
}
