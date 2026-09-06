package com.example.chessai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class ScreenRecordService : Service() {

    companion object {

        const val CHANNEL_ID = "ScreenRecordChannel"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PROMOTION_CHOICE = "ACTION_PROMOTION_CHOICE"
        const val ACTION_MANUAL_RESYNC = "ACTION_MANUAL_RESYNC"
        const val ACTION_TOGGLE_RECOMMENDATIONS = "ACTION_TOGGLE_RECOMMENDATIONS"
        const val ACTION_TOGGLE_PAUSE = "ACTION_TOGGLE_PAUSE"

        /*
         * Manual one-move repair.
         *
         * MISSED button -> ACTION_MANUAL_MISSED_TOGGLE
         * board tap     -> ACTION_MANUAL_MISSED_SQUARE
         */
        const val ACTION_MANUAL_MISSED_TOGGLE = "ACTION_MANUAL_MISSED_TOGGLE"
        const val ACTION_MANUAL_MISSED_SQUARE = "ACTION_MANUAL_MISSED_SQUARE"
        const val EXTRA_MANUAL_MISSED_SQUARE = "MANUAL_MISSED_SQUARE"

        const val ACTION_HARD_RESYNC_APPLY = "ACTION_HARD_RESYNC_APPLY"
        const val ACTION_HARD_RESYNC_CANCEL = "ACTION_HARD_RESYNC_CANCEL"

        const val EXTRA_HARD_RESYNC_FEN = "HARD_RESYNC_FEN"

        const val EXTRA_CASTLE_WK_KNOWLEDGE = "CASTLE_WK_KNOWLEDGE"
        const val EXTRA_CASTLE_WQ_KNOWLEDGE = "CASTLE_WQ_KNOWLEDGE"
        const val EXTRA_CASTLE_BK_KNOWLEDGE = "CASTLE_BK_KNOWLEDGE"
        const val EXTRA_CASTLE_BQ_KNOWLEDGE = "CASTLE_BQ_KNOWLEDGE"

        const val EXTRA_PROMOTION_PIECE = "PROMOTION_PIECE"

        var pendingSquareBitmaps: List<Bitmap> = emptyList()
        var pendingBoardBitmap: Bitmap? = null

        var hasPromptedThisSession = false
        var isBoardSetupConfirmed = false
        var selectedUserIsWhite: Boolean = true
        var preAppliedOpeningMove: Pair<Int, Int>? = null

        fun resetSession() {
            hasPromptedThisSession = false
            isBoardSetupConfirmed = false
            preAppliedOpeningMove = null

            Log.d(
                "ChessAI",
                "Session reset: resumed board detection loop."
            )
        }

        fun resetScanningSession() {
            resetSession()
        }
    }

    private val NOTIFICATION_ID = 1001

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var isProcessing = false

    /*
     * ============================================================
     * DIAGNOSTIC WATCHDOG - LOG ONLY
     * ============================================================
     *
     * This does NOT:
     * - accept or reject moves
     * - change FEN
     * - change the 3-frame stability logic
     * - change the 600ms cooldown
     * - change arrows
     * - restart capture
     *
     * It only writes diagnostic Logcat lines.
     *
     * IMPORTANT:
     * The Handler is created in onCreate(), after Android has attached the
     * Service context. Creating Handler(mainLooper) as a property can crash
     * Service construction because the base Context is not attached yet.
     */
    private val WATCHDOG_TAG =
        "ChessAI_Watchdog"

    private val WATCHDOG_INTERVAL_MS =
        2000L

    /*
     * ============================================================
     * CAPTURE DIAGNOSTICS ONLY
     * ============================================================
     *
     * IMPORTANT:
     * This version deliberately does NOT rebuild, detach, reattach, or refresh
     * ImageReader / VirtualDisplay automatically.
     *
     * We only collect enough information to learn WHY callbacks disappear.
     */
    private val CAPTURE_GAP_LOG_MS =
        1200L

    /*
     * ============================================================
     * LIGHTWEIGHT CAPTURE WAKE
     * ============================================================
     *
     * If callbacks disappear while normal tracking is active, keep the SAME:
     * - MediaProjection
     * - VirtualDisplay
     * - ImageReader
     * - ImageReader Surface
     * - FEN / VirtualChessBoard
     * - visual baseline
     *
     * We only detach and immediately reattach the existing ImageReader Surface
     * from the existing VirtualDisplay.
     */
    private val CAPTURE_WAKE_AFTER_MS =
        1500L

    /*
     * After the first wake, if callbacks are STILL not arriving normally,
     * gently pulse the SAME surface again every 800ms.
     *
     * This does NOT reduce the detector from 3 stable frames.
     * It only helps the existing 3-frame detector actually receive frames.
     */
    private val CAPTURE_WAKE_PULSE_MS =
        800L

    private var watchdogHandler: Handler? =
        null

    @Volatile
    private var lastCaptureWakeAt =
        0L

    @Volatile
    private var captureWakeCount =
        0L

    @Volatile
    private var captureCallbackCount =
        0L

    @Volatile
    private var captureImageAcquiredCount =
        0L

    @Volatile
    private var captureNullImageCount =
        0L

    @Volatile
    private var captureBusyDiscardCount =
        0L

    @Volatile
    private var capturePausedDiscardCount =
        0L

    @Volatile
    private var previousCaptureCallbackAt =
        0L

    @Volatile
    private var lastCaptureStage =
        "NOT_STARTED"

    @Volatile
    private var watchdogLastFrameCallbackAt =
        0L

    @Volatile
    private var watchdogLastAnalysisStartAt =
        0L

    @Volatile
    private var watchdogLastAnalysisFinishAt =
        0L

    @Volatile
    private var watchdogLastBoardFoundAt =
        0L

    @Volatile
    private var watchdogStarted =
        false

    private val watchdogRunnable =
        object : Runnable {

            override fun run() {

                if (!watchdogStarted) {
                    return
                }

                val now =
                    System.currentTimeMillis()

                val frameAge =
                    if (watchdogLastFrameCallbackAt > 0L) {
                        now - watchdogLastFrameCallbackAt
                    } else {
                        -1L
                    }

                val startAge =
                    if (watchdogLastAnalysisStartAt > 0L) {
                        now - watchdogLastAnalysisStartAt
                    } else {
                        -1L
                    }

                val finishAge =
                    if (watchdogLastAnalysisFinishAt > 0L) {
                        now - watchdogLastAnalysisFinishAt
                    } else {
                        -1L
                    }

                val boardAge =
                    if (watchdogLastBoardFoundAt > 0L) {
                        now - watchdogLastBoardFoundAt
                    } else {
                        -1L
                    }


                /*
                 * ========================================================
                 * LIGHTWEIGHT CAPTURE WAKE
                 * ========================================================
                 *
                 * The diagnostics showed periods where:
                 * - callback delivery stops
                 * - processing is idle
                 * - both surfaces still report valid
                 * - the processing thread is alive
                 *
                 * In that case, wake ONLY the existing VirtualDisplay by
                 * detaching and reattaching the SAME ImageReader Surface.
                 *
                 * No ImageReader recreation.
                 * No MediaProjection recreation.
                 * No FEN/baseline/move-detector changes.
                 */
                if (
                    frameAge >= CAPTURE_WAKE_AFTER_MS &&
                    !scanningPaused &&
                    !manualMissedRepairActive &&
                    !isResyncing &&
                    !hardResyncRequested &&
                    !isShuttingDown &&
                    !isProcessing &&
                    (
                            lastCaptureWakeAt == 0L ||
                                    now - lastCaptureWakeAt >= CAPTURE_WAKE_PULSE_MS
                            )
                ) {

                    wakeExistingCaptureSurface()
                }


                val state =
                    when {

                        isShuttingDown ->
                            "STOPPING"

                        scanningPaused ->
                            "PAUSED"

                        manualMissedRepairActive ->
                            "MISSED_REPAIR_ACTIVE"

                        frameAge < 0L ->
                            "WAITING_FOR_FIRST_FRAME"

                        frameAge > WATCHDOG_INTERVAL_MS ->
                            "NO_FRAME_CALLBACK"

                        isProcessing &&
                                startAge > WATCHDOG_INTERVAL_MS ->
                            "PROCESSING_STUCK_OR_SLOW"

                        finishAge > WATCHDOG_INTERVAL_MS ->
                            "FRAMES_ARRIVING_ANALYSIS_NOT_FINISHING"

                        boardAge > WATCHDOG_INTERVAL_MS ->
                            "ANALYSIS_RUNNING_BOARD_NOT_FOUND_RECENTLY"

                        else ->
                            "HEALTHY"
                    }


                val readerSurfaceValid =
                    try {
                        imageReader?.surface?.isValid == true
                    } catch (_: Exception) {
                        false
                    }

                val displaySurfaceValid =
                    try {
                        virtualDisplay?.surface?.isValid == true
                    } catch (_: Exception) {
                        false
                    }

                val processingThreadAlive =
                    handlerThread?.isAlive == true

                Log.d(
                    WATCHDOG_TAG,
                    "state=$state " +
                            "frameAge=${frameAge}ms " +
                            "analysisStartAge=${startAge}ms " +
                            "analysisFinishAge=${finishAge}ms " +
                            "boardFoundAge=${boardAge}ms " +
                            "stage=$lastCaptureStage " +
                            "wakeCount=$captureWakeCount " +
                            "callbacks=$captureCallbackCount " +
                            "acquired=$captureImageAcquiredCount " +
                            "nullImages=$captureNullImageCount " +
                            "busyDrops=$captureBusyDiscardCount " +
                            "pausedDrops=$capturePausedDiscardCount " +
                            "readerSurfaceValid=$readerSurfaceValid " +
                            "displaySurfaceValid=$displaySurfaceValid " +
                            "processingThreadAlive=$processingThreadAlive " +
                            "isProcessing=$isProcessing " +
                            "paused=$scanningPaused " +
                            "missedRepair=$manualMissedRepairActive"
                )


                watchdogHandler?.postDelayed(
                    this,
                    WATCHDOG_INTERVAL_MS
                )
            }
        }

    @Volatile
    private var pendingPromotionUserChoice: Char? = null

    private var pendingPromotionMove: DetectedMove? = null

    private val savedBoardHistory = mutableListOf<Bitmap>()

    private val virtualBoard = VirtualChessBoard()

    /*
     * ============================================================
     * STOCKFISH + CLEAN LIVE LOG
     * ============================================================
     *
     * Keep all existing diagnostic logs for troubleshooting, but during
     * normal use filter Android Studio Logcat to:
     *
     *     tag:ChessAI_LIVE
     *
     * That view will contain only:
     * - MOVE
     * - FEN
     * - BEST 3
     */
    private val LIVE_TAG = "ChessAI_LIVE"

    private var stockfishEngine: StockfishEngine? = null

    @Volatile
    private var stockfishRequestGeneration = 0L

    @Volatile
    private var recommendationsVisible = true

    /*
     * PAUSE keeps MediaProjection alive but completely stops chess-frame
     * processing. This lets the user leave the chess app / inspect something
     * else without those screens being interpreted as chess moves.
     */
    @Volatile
    private var scanningPaused = false

    /*
     * On RESUME we do not compare the first returned board against the old
     * screenshot. We first establish a fresh visual baseline while leaving the
     * VirtualChessBoard/FEN untouched.
     *
     * If the real chess position changed while paused, the user should press
     * RESYNC after returning to the board.
     */
    @Volatile
    private var resumeNeedsFreshBaseline = false

    /*
     * Manual MISSED-MOVE repair is completely separate from normal detection.
     * While active, captured frames are discarded until the user finishes the
     * one-ply correction.
     */
    @Volatile
    private var manualMissedRepairActive = false

    private var manualMissedDestination: Int? = null

    private var manualMissedCandidates: List<String> =
        emptyList()

    private var latestRecommendationSide: String? = null

    private var latestRecommendationMoves:
            List<StockfishEngine.SuggestedMove> =
        emptyList()

    @Volatile
    private var isResyncing = false

    /*
     * TRUE after a HARD RESYNC recognition/apply failure.
     *
     * While frozen:
     * - the old VirtualChessBoard/FEN is preserved but NOT trusted
     * - normal move detection is disabled
     * - Stockfish recommendations are hidden/disabled
     * - the RESYNC button remains available
     *
     * A successful RESYNC clears this state.
     * An explicit wizard CANCEL also clears it and returns to the old board.
     */
    @Volatile
    private var resyncFailureFrozen = false

    /*
     * True while the recording service is shutting down.
     *
     * RESYNC checks this before doing any work or committing a recovered
     * position so STOP can never race a recovery commit.
     */
    @Volatile
    private var isShuttingDown = false

    private var unresolvedStablePasses = 0

    /*
     * HARD RESYNC is MANUAL ONLY.
     *
     * No automatic recovery job is ever launched from unresolved frames.
     */
    @Volatile
    private var hardResyncRequested = false

    /*
     * Identifies the currently active asynchronous Gemini RESYNC request.
     * Incrementing this invalidates any older callback.
     */
    @Volatile
    private var geminiResyncGeneration = 0L

    private var hardResyncStableFrame: Bitmap? = null
    private var hardResyncStableCount = 0

    /*
     * Settled board captured by HARD RESYNC while the metadata wizard is open.
     * Owned only by ScreenProcessingThread.
     */
    private var hardResyncCapturedBoard: Bitmap? = null

    private val HARD_RESYNC_REQUIRED_STABLE_FRAMES = 3

    /*
     * Latest chessboard position on the physical screen.
     *
     * These are SCREEN coordinates from OpenCV's detected boardRect.
     * RecommendationOverlayService uses them later to align move markers
     * exactly with the visible chessboard.
     */
    @Volatile
    private var latestBoardX = 0

    @Volatile
    private var latestBoardY = 0

    @Volatile
    private var latestBoardWidth = 0

    @Volatile
    private var latestBoardHeight = 0

    /*
     * ------------------------------------------------------------
     * STABLE BOARD TRACKING
     * ------------------------------------------------------------
     */

    /**
     * Last board that we have accepted as the official chess state.
     *
     * IMPORTANT:
     * This is NOT updated during animation.
     *
     * It is only updated after a move has been successfully detected.
     */
    private var previousBoardBitmap: Bitmap? = null

    /**
     * Candidate frame currently being tested for stability.
     */
    private var pendingStableFrame: Bitmap? = null

    /**
     * Number of consecutive frames matching pendingStableFrame.
     */
    private var stableFrameCount = 0

    /**
     * Number of stable frames required before we analyze a move.
     *
     * Previously this was effectively 2.
     *
     * We intentionally use 3 here:
     *
     *     frame A
     *     frame A
     *     frame A
     *
     * This gives animations/highlights more time to settle.
     */
    private val REQUIRED_STABLE_FRAMES = 3

    /*
     * PROMOTION FAST PATH
     *
     * A checkmating promotion can make Chess.com replace the board with the
     * game-over UI almost immediately. In the real b7-b8=Q# test we reached
     * 2/3 stable frames, then the board disappeared before frame 3.
     *
     * Only when the CURRENT virtual side to move has a pawn that can
     * pseudo-legally step/capture onto the promotion rank do we lower the
     * stability requirement to 2.
     *
     * Every ordinary position still requires 3 stable frames.
     */
    private val PROMOTION_REQUIRED_STABLE_FRAMES = 2

    /**
     * Prevents another move from being processed immediately after
     * one has just been accepted.
     *
     * This is especially useful when the board animation continues
     * for another frame or two after the final position appears.
     */
    /*
     * Shorter cooldown for fast human play.
     *
     * IMPORTANT:
     * This is the ONLY behavior change in this version.
     * HARD RESYNC code is left exactly as it was in the working persistent build.
     */
    private val MOVE_COOLDOWN_MS = 600L

    private val RESIDUE_CONFIRMATION_SCANS = 2

    private val residueObservationCounts =
        IntArray(64)

    private val persistentResidueSquares =
        mutableSetOf<Int>()

    private var lastAcceptedMoveTime = 0L


    override fun onCreate() {
        super.onCreate()

        /*
         * Safe watchdog initialization.
         *
         * Android has attached the Service context by this point, so
         * mainLooper/getMainLooper() is valid here.
         */
        watchdogHandler =
            Handler(
                mainLooper
            )

        watchdogLastFrameCallbackAt = 0L
        watchdogLastAnalysisStartAt = 0L
        watchdogLastAnalysisFinishAt = 0L
        watchdogLastBoardFoundAt = 0L

        captureCallbackCount = 0L
        captureImageAcquiredCount = 0L
        captureNullImageCount = 0L
        captureBusyDiscardCount = 0L
        capturePausedDiscardCount = 0L
        previousCaptureCallbackAt = 0L
        lastCaptureStage = "SERVICE_CREATED"

        lastCaptureWakeAt = 0L
        captureWakeCount = 0L

        watchdogStarted = true

        watchdogHandler?.removeCallbacks(
            watchdogRunnable
        )

        watchdogHandler?.postDelayed(
            watchdogRunnable,
            WATCHDOG_INTERVAL_MS
        )

        hasPromptedThisSession = false
        isBoardSetupConfirmed = false

        isProcessing = false
        preAppliedOpeningMove = null

        pendingPromotionUserChoice = null
        pendingPromotionMove = null

        savedBoardHistory.clear()

        previousBoardBitmap?.recycle()
        previousBoardBitmap = null

        pendingStableFrame?.recycle()
        pendingStableFrame = null

        stableFrameCount = 0
        lastAcceptedMoveTime = 0L

        clearPersistentResidues()

        recommendationsVisible = true
        scanningPaused = false
        resumeNeedsFreshBaseline = false
        manualMissedRepairActive = false
        manualMissedDestination = null
        manualMissedCandidates = emptyList()
        resyncFailureFrozen = false
        latestRecommendationSide = null
        latestRecommendationMoves = emptyList()
        isResyncing = false
        resyncFailureFrozen = false
        isShuttingDown = false
        unresolvedStablePasses = 0

        hardResyncRequested = false

        hardResyncStableFrame?.recycle()
        hardResyncStableFrame = null
        hardResyncStableCount = 0

        hardResyncCapturedBoard?.recycle()
        hardResyncCapturedBoard = null

        val recognizerLoaded =
            BoardPositionRecognizer.loadFromStorage(
                applicationContext
            )

        Log.d(
            "ChessAI_HardResync",
            "Persistent recognizer loaded=$recognizerLoaded"
        )

        try {

            stockfishEngine =
                StockfishEngine(
                    context = applicationContext
                ).also {
                    it.start()
                }

        } catch (e: Exception) {

            stockfishEngine = null

            Log.e(
                LIVE_TAG,
                "STOCKFISH ERROR: ${e.message}"
            )
        }


    }


    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        /*
         * MediaProjection capture permission is tied to the original explicit
         * start request. Android must NEVER revive this service with a null
         * Intent and then let it call startForeground(mediaProjection).
         *
         * A sticky automatic restart has no valid capture grant and can crash
         * with SecurityException on modern Android.
         */
        if (intent == null) {

            Log.w(
                LIVE_TAG,
                "Ignoring automatic ScreenRecordService restart with null Intent."
            )

            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }

            stopSelf(startId)

            return START_NOT_STICKY
        }

        /*
         * ------------------------------------------------------------
         * STOP REQUEST
         * ------------------------------------------------------------
         */
        if (intent?.action == ACTION_PROMOTION_CHOICE) {
            val pieceString =
                intent.getStringExtra(
                    EXTRA_PROMOTION_PIECE
                )

            val piece =
                pieceString
                    ?.firstOrNull()
                    ?.lowercaseChar()

            if (
                piece == 'q' ||
                piece == 'r' ||
                piece == 'b' ||
                piece == 'n'
            ) {
                pendingPromotionUserChoice = piece

                Log.d(
                    "ChessAI_Promotion",
                    "Manual promotion choice received: $piece"
                )
            }

            return START_NOT_STICKY
        }

        if (
            intent?.action ==
            ACTION_TOGGLE_PAUSE
        ) {

            if (!scanningPaused) {

                /*
                 * Enter PAUSE.
                 *
                 * MediaProjection stays alive; only chess frame processing is
                 * suspended. Keep the virtual FEN exactly as-is.
                 */
                scanningPaused =
                    true

                resumeNeedsFreshBaseline =
                    false

                hideRecommendationOverlay()

                updateControlPauseState()

                Log.d(
                    LIVE_TAG,
                    "TRACKING PAUSED | FEN: ${virtualBoard.generateFen()}"
                )

            } else {

                /*
                 * RESUME.
                 *
                 * Do not immediately compare the next board against the old
                 * pre-pause screenshot. First re-establish a visual baseline.
                 */
                scanningPaused =
                    false

                resumeNeedsFreshBaseline =
                    true

                pendingStableFrame?.recycle()
                pendingStableFrame =
                    null

                stableFrameCount =
                    0

                clearPersistentResidues()

                lastAcceptedMoveTime =
                    0L

                updateControlPauseState()

                Log.d(
                    LIVE_TAG,
                    "TRACKING RESUMED - waiting for fresh board baseline | " +
                            "FEN: ${virtualBoard.generateFen()}"
                )
            }

            return START_NOT_STICKY
        }


        /*
         * ------------------------------------------------------------
         * MANUAL MISSED-MOVE REPAIR
         * ------------------------------------------------------------
         *
         * Completely separate from normal automatic move detection.
         */
        if (
            intent?.action ==
            ACTION_MANUAL_MISSED_TOGGLE
        ) {

            if (manualMissedRepairActive) {
                cancelManualMissedRepair()
            } else {
                startManualMissedRepair()
            }

            return START_NOT_STICKY
        }


        if (
            intent?.action ==
            ACTION_MANUAL_MISSED_SQUARE
        ) {

            val squareIndex =
                intent.getIntExtra(
                    EXTRA_MANUAL_MISSED_SQUARE,
                    -1
                )

            if (
                manualMissedRepairActive &&
                squareIndex in 0..63
            ) {
                handleManualMissedSquare(
                    squareIndex
                )
            }

            return START_NOT_STICKY
        }


        if (
            intent?.action ==
            ACTION_TOGGLE_RECOMMENDATIONS
        ) {

            recommendationsVisible =
                !recommendationsVisible

            if (recommendationsVisible) {

                val side =
                    latestRecommendationSide

                if (
                    !resyncFailureFrozen &&
                    side != null &&
                    latestRecommendationMoves.isNotEmpty()
                ) {
                    updateRecommendationOverlay(
                        sideToMove = side,
                        moves = latestRecommendationMoves
                    )
                }

            } else {

                hideRecommendationOverlay()
            }

            updateControlRecommendationVisibility()

            return START_NOT_STICKY
        }


        if (
            intent?.action ==
            ACTION_MANUAL_RESYNC
        ) {

            requestHardResync()

            return START_NOT_STICKY
        }


        if (
            intent?.action ==
            ACTION_HARD_RESYNC_CANCEL
        ) {

            cancelHardResync(
                showFailure =
                    false
            )

            return START_NOT_STICKY
        }


        if (
            intent?.action ==
            ACTION_HARD_RESYNC_APPLY
        ) {

            val resyncedFen =
                intent.getStringExtra(
                    EXTRA_HARD_RESYNC_FEN
                )

            if (resyncedFen != null) {

                applyHardResyncFen(
                    fen =
                        resyncedFen
                )

            } else {

                cancelHardResync(
                    showFailure =
                        true
                )
            }

            return START_NOT_STICKY
        }


        if (intent?.action == ACTION_STOP) {

            stopScreenRecording()

            stopForeground(STOP_FOREGROUND_REMOVE)

            stopSelf()

            return START_NOT_STICKY
        }


        /*
         * ------------------------------------------------------------
         * VALIDATE MEDIA PROJECTION GRANT BEFORE startForeground()
         * ------------------------------------------------------------
         *
         * Every action-only request returned above. Reaching this point means
         * this must be the original capture-start Intent.
         */
        val resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                0
            )


        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )

            } else {

                @Suppress("DEPRECATION")
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA
                )
            }


        if (
            resultCode == 0 ||
            resultData == null
        ) {

            Log.w(
                LIVE_TAG,
                "ScreenRecordService start rejected: no valid MediaProjection grant."
            )

            stopSelf(startId)

            return START_NOT_STICKY
        }


        /*
         * ------------------------------------------------------------
         * INITIALIZE OPENCV
         * ------------------------------------------------------------
         */
        if (!org.opencv.android.OpenCVLoader.initDebug()) {

            Log.e(
                "ChessAI",
                "Internal OpenCV library not found."
            )

        } else {

            Log.d(
                "ChessAI",
                "OpenCV loaded successfully!"
            )
        }


        /*
         * ------------------------------------------------------------
         * FOREGROUND SERVICE NOTIFICATION
         * ------------------------------------------------------------
         */
        createNotificationChannel()

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle("Chess Assistant")
                .setContentText("Screen recording active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setUsesChronometer(true)
                .build()


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }


        /*
         * ------------------------------------------------------------
         * MEDIA PROJECTION
         * ------------------------------------------------------------
         */
        if (
            resultCode != 0 &&
            resultData != null
        ) {

            val projectionManager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager


            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )


            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {

                    override fun onStop() {

                        super.onStop()

                        stopScreenRecording()
                    }

                },
                Handler(mainLooper)
            )


            startVirtualDisplay()

            /*
             * Floating STOP control.
             *
             * This is shown only after screen capture has successfully started.
             * Pressing it sends ACTION_STOP back to THIS service, so it follows
             * the exact same shutdown path as the Stop Recording button in
             * MainActivity.
             */
            showStopOverlay()
            updateControlRecommendationVisibility()
            setResyncUiState(
                StopOverlayService.RESYNC_IDLE
            )
        }


        return START_NOT_STICKY
    }


    /*
     * ============================================================
     * VIRTUAL DISPLAY
     * ============================================================
     */

    /*
     * Rebuild ONLY ImageReader + VirtualDisplay after the capture callback stalls.
     *
     * IMPORTANT:
     * - MediaProjection stays alive.
     * - VirtualChessBoard/FEN is untouched.
     * - previousBoardBitmap is untouched.
     * - stable-frame logic is untouched.
     * - recommendation overlay is untouched.
     *
     * We intentionally request a fresh visual baseline after transport recovery so
     * the first returned frame is not compared against a potentially stale visual
     * screenshot. This does NOT alter the virtual chess position.
     */


    /*
     * Wake the existing capture path without creating or destroying anything.
     *
     * This intentionally preserves the current visual baseline. If a chess move
     * happened while callbacks were silent, the first returned frame can still be
     * compared against the pre-stall baseline and detected normally.
     */
    private fun wakeExistingCaptureSurface() {

        val display =
            virtualDisplay

        val reader =
            imageReader

        if (
            display == null ||
            reader == null ||
            isShuttingDown
        ) {

            Log.w(
                WATCHDOG_TAG,
                "CAPTURE_WAKE skipped display=${display != null} reader=${reader != null}"
            )

            return
        }


        val surface =
            try {
                reader.surface
            } catch (e: Exception) {

                Log.e(
                    WATCHDOG_TAG,
                    "CAPTURE_WAKE failed reading ImageReader surface",
                    e
                )

                return
            }


        if (!surface.isValid) {

            Log.w(
                WATCHDOG_TAG,
                "CAPTURE_WAKE skipped because existing ImageReader surface is invalid"
            )

            return
        }


        lastCaptureWakeAt =
            System.currentTimeMillis()

        captureWakeCount++

        val callbacksBefore =
            captureCallbackCount

        val frameAgeBefore =
            if (watchdogLastFrameCallbackAt > 0L) {
                lastCaptureWakeAt - watchdogLastFrameCallbackAt
            } else {
                -1L
            }


        Log.w(
            WATCHDOG_TAG,
            "CAPTURE_WAKE pulse count=$captureWakeCount " +
                    "frameAge=${frameAgeBefore}ms callbacksBefore=$callbacksBefore " +
                    "using SAME ImageReader and SAME VirtualDisplay"
        )


        try {

            /*
             * Very small nudge to SurfaceFlinger / VirtualDisplay.
             *
             * Do NOT release the VirtualDisplay.
             * Do NOT close/recreate ImageReader.
             */
            display.surface =
                null

            display.surface =
                surface


            Log.w(
                WATCHDOG_TAG,
                "CAPTURE_WAKE surface reattached successfully " +
                        "count=$captureWakeCount"
            )

        } catch (e: Exception) {

            Log.e(
                WATCHDOG_TAG,
                "CAPTURE_WAKE failed count=$captureWakeCount",
                e
            )
        }
    }


    private fun startVirtualDisplay() {

        handlerThread =
            HandlerThread(
                "ScreenProcessingThread"
            ).apply {
                start()
            }


        handler =
            Handler(
                handlerThread!!.looper
            )


        createImageReaderAndVirtualDisplay()
    }


    private fun attachImageReaderListener(
        reader: ImageReader,
        width: Int,
        height: Int
    ) {

        reader.setOnImageAvailableListener(
            { reader ->

                val callbackNow =
                    System.currentTimeMillis()

                val callbackGap =
                    if (previousCaptureCallbackAt > 0L) {
                        callbackNow - previousCaptureCallbackAt
                    } else {
                        -1L
                    }

                previousCaptureCallbackAt =
                    callbackNow

                watchdogLastFrameCallbackAt =
                    callbackNow

                /*
                 * We received a frame again.
                 * Reset pulse timing so a future stall starts a fresh wake cycle.
                 */
                lastCaptureWakeAt =
                    0L

                captureCallbackCount++

                lastCaptureStage =
                    "CALLBACK_ENTER"

                if (callbackGap >= CAPTURE_GAP_LOG_MS) {

                    Log.w(
                        WATCHDOG_TAG,
                        "FRAME_CALLBACK_RESUMED afterGap=${callbackGap}ms " +
                                "callbacks=$captureCallbackCount " +
                                "isProcessing=$isProcessing " +
                                "paused=$scanningPaused " +
                                "missedRepair=$manualMissedRepairActive"
                    )
                }

                /*
                 * If STOP has started, do not begin any new bitmap/native image
                 * work while ImageReader / MediaProjection are being torn down.
                 */
                if (isShuttingDown) {

                    lastCaptureStage =
                        "DISCARD_SHUTTING_DOWN"

                    reader.acquireLatestImage()?.close()

                    return@setOnImageAvailableListener
                }


                /*
                 * PAUSE means zero chess image processing.
                 *
                 * Keep MediaProjection alive, but immediately discard the
                 * newest image so buffers cannot build up.
                 */
                if (
                    scanningPaused ||
                    manualMissedRepairActive
                ) {

                    capturePausedDiscardCount++

                    lastCaptureStage =
                        if (scanningPaused) {
                            "DISCARD_PAUSED"
                        } else {
                            "DISCARD_MISSED_REPAIR"
                        }

                    reader.acquireLatestImage()?.close()

                    return@setOnImageAvailableListener
                }


                /*
                 * Do not process multiple frames simultaneously.
                 */
                if (isProcessing) {

                    captureBusyDiscardCount++

                    lastCaptureStage =
                        "DISCARD_BUSY"

                    reader.acquireLatestImage()?.close()

                    return@setOnImageAvailableListener
                }


                isProcessing = true

                lastCaptureStage =
                    "ACQUIRE_LATEST_IMAGE"


                val image =
                    reader.acquireLatestImage()


                if (image != null) {

                    captureImageAcquiredCount++

                    lastCaptureStage =
                        "IMAGE_ACQUIRED"

                    try {

                        lastCaptureStage =
                            "IMAGE_TO_BITMAP_START"

                        val bitmap =
                            imageToBitmap(
                                image,
                                width,
                                height
                            )


                        if (bitmap != null) {

                            lastCaptureStage =
                                "BITMAP_READY"

                            watchdogLastAnalysisStartAt =
                                System.currentTimeMillis()

                            try {

                                lastCaptureStage =
                                    "DETECT_CHESSBOARD_START"

                                detectChessboard(bitmap)

                                lastCaptureStage =
                                    "DETECT_CHESSBOARD_RETURNED"

                            } finally {

                                watchdogLastAnalysisFinishAt =
                                    System.currentTimeMillis()
                            }

                            /*
                             * imageToBitmap() creates a bitmap.
                             * detectChessboard() copies the pieces it
                             * needs, so the temporary full-screen
                             * bitmap can now be recycled.
                             */
                            bitmap.recycle()
                        }

                    } catch (e: Exception) {

                        Log.e(
                            "ChessAI",
                            "Error processing frame",
                            e
                        )

                    } finally {

                        lastCaptureStage =
                            "CLOSING_IMAGE"

                        image.close()

                        isProcessing = false

                        lastCaptureStage =
                            "IDLE"
                    }

                } else {

                    captureNullImageCount++

                    isProcessing = false

                    lastCaptureStage =
                        "NULL_IMAGE"
                }

            },
            handler
        )
    }


    private fun createImageReaderAndVirtualDisplay() {

        val metrics =
            resources.displayMetrics

        val width =
            metrics.widthPixels

        val height =
            metrics.heightPixels

        val density =
            metrics.densityDpi


        imageReader =
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )


        attachImageReaderListener(
            reader = imageReader!!,
            width = width,
            height = height
        )


        virtualDisplay =
            mediaProjection?.createVirtualDisplay(
                "ScreenRecord",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )


        Log.d(
            "ChessAI",
            "VirtualDisplay started successfully."
        )
    }


    /*
     * ============================================================
     * IMAGE → BITMAP
     * ============================================================
     */

    private fun imageToBitmap(
        image: Image,
        expectedWidth: Int,
        expectedHeight: Int
    ): Bitmap? {

        val planes =
            image.planes

        val buffer =
            planes[0].buffer

        val pixelStride =
            planes[0].pixelStride

        val rowStride =
            planes[0].rowStride

        val rowPadding =
            rowStride -
                    pixelStride * expectedWidth


        val bitmap =
            Bitmap.createBitmap(
                expectedWidth +
                        rowPadding / pixelStride,
                expectedHeight,
                Bitmap.Config.ARGB_8888
            )


        bitmap.copyPixelsFromBuffer(
            buffer
        )


        return if (rowPadding == 0) {

            bitmap

        } else {

            val cropped =
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    expectedWidth,
                    expectedHeight
                )

            bitmap.recycle()

            cropped
        }
    }


    /*
     * ============================================================
     * CHESSBOARD DETECTION
     * ============================================================
     */

    private fun detectChessboard(
        bitmap: Bitmap
    ) {

        var mat: Mat? = null
        var grayMat: Mat? = null
        var blurred: Mat? = null
        var thresh: Mat? = null
        var hierarchy: Mat? = null

        try {

            mat = Mat()

            Utils.bitmapToMat(
                bitmap,
                mat
            )


            grayMat = Mat()

            Imgproc.cvtColor(
                mat,
                grayMat,
                Imgproc.COLOR_RGBA2GRAY
            )


            blurred = Mat()

            Imgproc.GaussianBlur(
                grayMat,
                blurred,
                org.opencv.core.Size(
                    5.0,
                    5.0
                ),
                0.0
            )


            thresh = Mat()

            Imgproc.adaptiveThreshold(
                blurred,
                thresh,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                11,
                2.0
            )


            val contours =
                ArrayList<org.opencv.core.MatOfPoint>()

            hierarchy =
                Mat()


            Imgproc.findContours(
                thresh,
                contours,
                hierarchy,
                Imgproc.RETR_TREE,
                Imgproc.CHAIN_APPROX_SIMPLE
            )


            var boardRect:
                    org.opencv.core.Rect? =
                null


            val screenArea =
                bitmap.width *
                        bitmap.height


            for (contour in contours) {

                val approx =
                    org.opencv.core.MatOfPoint2f()


                val mat2f =
                    org.opencv.core.MatOfPoint2f(
                        *contour.toArray()
                    )


                Imgproc.approxPolyDP(
                    mat2f,
                    approx,
                    0.02 *
                            Imgproc.arcLength(
                                mat2f,
                                true
                            ),
                    true
                )


                val rect =
                    Imgproc.boundingRect(
                        contour
                    )


                val area =
                    rect.width *
                            rect.height


                if (
                    approx.total() == 4L &&
                    area > screenArea * 0.25 &&
                    area < screenArea * 0.90
                ) {

                    val aspect =
                        rect.width.toDouble() /
                                rect.height.toDouble()


                    if (
                        aspect in 0.9..1.1
                    ) {

                        boardRect =
                            rect

                        break
                    }
                }


                approx.release()
                mat2f.release()
            }


            /*
             * --------------------------------------------------------
             * BOARD FOUND
             * --------------------------------------------------------
             */
            if (boardRect != null) {

                watchdogLastBoardFoundAt =
                    System.currentTimeMillis()

                lastCaptureStage =
                    "BOARD_FOUND"

                /*
                 * Save the exact chessboard rectangle in SCREEN coordinates.
                 *
                 * OpenCV found this rectangle inside the full captured screen,
                 * so these x/y/width/height values can be sent directly to
                 * the overlay window.
                 */
                latestBoardX =
                    boardRect!!.x

                latestBoardY =
                    boardRect!!.y

                latestBoardWidth =
                    boardRect!!.width

                latestBoardHeight =
                    boardRect!!.height

                val currentFrame =
                    Bitmap.createBitmap(
                        bitmap,
                        boardRect!!.x,
                        boardRect!!.y,
                        boardRect!!.width,
                        boardRect!!.height
                    )


                /*
                 * ====================================================
                 * MID-GAME RESTART HARD RESYNC BYPASS
                 * ====================================================
                 * Allows STOP mid-game -> restart capture -> RESYNC without
                 * first pretending the current position is a new starting board.
                 */
                if (
                    hardResyncRequested
                ) {

                    val standardResyncFrame =
                        ChessBoardUtils.normalizeBoardOrientation(
                            boardBitmap = currentFrame,
                            userIsWhite = ScreenRecordService.selectedUserIsWhite
                        )

                    processHardResyncFrame(
                        standardFrame = standardResyncFrame
                    )

                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardResyncFrame.recycle()
                    currentFrame.recycle()
                    return
                }

                /*
                 * If the HARD RESYNC metadata wizard is already open, keep the
                 * normal setup prompt paused until APPLY or CANCEL arrives.
                 */
                if (
                    isResyncing &&
                    !hardResyncRequested
                ) {

                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    currentFrame.recycle()
                    return
                }


                /*
                 * A failed HARD RESYNC deliberately freezes the old virtual
                 * state. We still locate/crop the board so another RESYNC can
                 * run, but NO normal move may be inferred from the stale FEN.
                 */
                if (resyncFailureFrozen) {

                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    currentFrame.recycle()
                    return
                }


                /*
                 * ====================================================
                 * 1. INITIAL PROMPT PHASE
                 * ====================================================
                 */
                if (!hasPromptedThisSession) {

                    val squares =
                        ChessBoardUtils.sliceBoardIntoSquares(
                            currentFrame
                        )


                    checkIfDuplicateAndSave(
                        currentFrame,
                        squares,
                        ""
                    )


                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    /*
                     * currentFrame is now owned by
                     * pendingBoardBitmap after the copy.
                     */
                    currentFrame.recycle()

                    return
                }


                /*
                 * ====================================================
                 * 2. WAIT FOR USER CONFIRMATION
                 * ====================================================
                 */
                if (!isBoardSetupConfirmed) {

                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    currentFrame.recycle()

                    return
                }


                /*
                 * ====================================================
                 * TOUCH GUARD — ONLY AFTER USER ANSWERS YES
                 * ====================================================
                 *
                 * isBoardSetupConfirmed becomes true only after the user
                 * confirms the detected board in the setup prompt.
                 *
                 * Before that:
                 *   - STOP button is visible
                 *   - the rest of the screen is NOT touch-blocked
                 *
                 * After YES:
                 *   - chessboard remains touch-through
                 *   - everything outside the board is blocked
                 */
                updateStopTouchGuard(
                    boardX = latestBoardX,
                    boardY = latestBoardY,
                    boardWidth = latestBoardWidth,
                    boardHeight = latestBoardHeight
                )


                /*
                 * ====================================================
                 * NORMALIZE BOARD ORIENTATION
                 * ====================================================
                 *
                 * VirtualChessBoard always uses:
                 * 0 = a8 ... 63 = h1.
                 *
                 * A Black-bottom screen board is rotated 180 degrees
                 * into standard coordinates before any stateful image
                 * comparison or move detection.
                 */
                val rawStandardFrame =
                    ChessBoardUtils.normalizeBoardOrientation(
                        boardBitmap = currentFrame,
                        userIsWhite = ScreenRecordService.selectedUserIsWhite
                    )

                /*
                 * ====================================================
                 * RECOMMENDATION OVERLAY PIXEL SUPPRESSION
                 * ====================================================
                 *
                 * MediaProjection includes our arrows/square outlines.
                 * Do NOT blink/hide them. Instead, before stability and move
                 * detection, replace only the pixels covered by OUR known
                 * overlay geometry with pixels from the last accepted clean
                 * board baseline.
                 *
                 * The mask is deliberately narrow. We ignore the drawn line /
                 * border itself, not the whole chess square, so real piece
                 * motion remains visible around the overlay.
                 */
                val standardFrame =
                    suppressRecommendationOverlayPixels(
                        capturedBoard = rawStandardFrame
                    )

                rawStandardFrame.recycle()


                /*
                 * ====================================================
                 * RESUME FRESH VISUAL BASELINE
                 * ====================================================
                 *
                 * PAUSE preserved the VirtualChessBoard/FEN. The first valid
                 * board seen after RESUME becomes the new visual comparison
                 * baseline only.
                 *
                 * IMPORTANT:
                 * If the actual chess position changed while PAUSED, use
                 * RESYNC. This baseline operation intentionally does not guess
                 * or alter the virtual chess state.
                 */
                if (resumeNeedsFreshBaseline) {

                    previousBoardBitmap?.recycle()

                    previousBoardBitmap =
                        standardFrame.copy(
                            Bitmap.Config.ARGB_8888,
                            false
                        )


                    pendingStableFrame?.recycle()

                    pendingStableFrame =
                        null

                    stableFrameCount =
                        0

                    clearPersistentResidues()

                    unresolvedStablePasses =
                        0

                    resumeNeedsFreshBaseline =
                        false


                    Log.d(
                        LIVE_TAG,
                        "RESUME BASELINE ESTABLISHED | " +
                                "FEN unchanged: ${virtualBoard.generateFen()}"
                    )


                    if (
                        recommendationsVisible &&
                        latestRecommendationSide != null &&
                        latestRecommendationMoves.isNotEmpty()
                    ) {

                        updateRecommendationOverlay(
                            sideToMove =
                                latestRecommendationSide!!,
                            moves =
                                latestRecommendationMoves
                        )
                    }


                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardFrame.recycle()
                    currentFrame.recycle()

                    return
                }


                /*
                 * ====================================================
                 * MANUAL HARD RESYNC MODE
                 * ====================================================
                 *
                 * While active:
                 * - normal move acceptance is paused
                 * - recommendation overlay stays hidden
                 * - we wait for 3 matching settled board frames
                 * - Gemini full-board recognition runs ONCE
                 */
                if (hardResyncRequested) {

                    processHardResyncFrame(
                        standardFrame =
                            standardFrame
                    )

                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardFrame.recycle()
                    currentFrame.recycle()

                    return
                }


                /*
                 * HARD RESYNC board was recognized and the metadata wizard is
                 * currently open. Keep normal FEN tracking paused until APPLY
                 * or CANCEL arrives.
                 */
                if (
                    isResyncing &&
                    !hardResyncRequested
                ) {

                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardFrame.recycle()
                    currentFrame.recycle()

                    return
                }


                /*
                 * ====================================================
                 * 3. INITIALIZE BASELINE
                 * ====================================================
                 */
                if (previousBoardBitmap == null) {

                    previousBoardBitmap =
                        standardFrame.copy(
                            Bitmap.Config.ARGB_8888,
                            false
                        )



                    /*
                     * Reset virtual chess state.
                     */
                    virtualBoard.resetToDefault(
                        ScreenRecordService.selectedUserIsWhite
                    )


                    /*
                     * If the user is Black, apply the opening move
                     * selected in the wizard.
                     */
                    if (!virtualBoard.isUserWhite) {

                        val openingMove =
                            ScreenRecordService
                                .preAppliedOpeningMove


                        if (openingMove != null) {

                            virtualBoard.applyMove(
                                openingMove.first,
                                openingMove.second
                            )


                            val sq1 =
                                ChessBoardUtils
                                    .indexToSquareName(
                                        openingMove.first
                                    )


                            val sq2 =
                                ChessBoardUtils
                                    .indexToSquareName(
                                        openingMove.second
                                    )


                            Log.d(
                                "ChessAI_Opening",
                                "[SUCCESS] Applied wizard opening move: " +
                                        "$sq1 -> $sq2"
                            )


                            ScreenRecordService
                                .preAppliedOpeningMove = null

                        } else {

                            Log.d(
                                "ChessAI_Opening",
                                "[WAITING] No opening move was pre-selected."
                            )
                        }
                    }


                    PromotionRecognizer.learnFromBoard(
                        standardBoardBitmap = standardFrame,
                        virtualBoard = virtualBoard
                    )

                    if (!BoardPositionRecognizer.isTrained()) {

                        BoardPositionRecognizer.learnFromTrustedBoard(
                            standardBoardBitmap =
                                standardFrame,
                            virtualBoard =
                                virtualBoard
                        )

                        if (BoardPositionRecognizer.isTrained()) {
                            BoardPositionRecognizer.saveToStorage(
                                context = applicationContext,
                                overwrite = false
                            )
                        }

                    } else {

                        Log.d(
                            "ChessAI_HardResync",
                            "Using existing persistent board recognition templates; skipping retraining."
                        )
                    }

                    Log.d(
                        "ChessAI_Promotion",
                        "Promotion templates learned from confirmed board."
                    )

                    val initialFen =
                        virtualBoard.generateFen()

                    Log.d(
                        "ChessAI",
                        "Board setup confirmed. " +
                                "Baseline established. " +
                                "FEN: $initialFen"
                    )

                    Log.d(
                        LIVE_TAG,
                        "FEN: $initialFen"
                    )

                    requestStockfishAnalysis(
                        fen = initialFen
                    )


                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardFrame.recycle()
                    currentFrame.recycle()

                    return
                }


                /*
                 * ====================================================
                 * 4. MOVE COOLDOWN
                 * ====================================================
                 *
                 * After accepting a move, ignore incoming frames for
                 * a short period.
                 *
                 * This prevents the tail end of an animation from
                 * immediately becoming another move.
                 */
                val now =
                    System.currentTimeMillis()


                if (
                    now -
                    lastAcceptedMoveTime <
                    MOVE_COOLDOWN_MS
                ) {

                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardFrame.recycle()
                    currentFrame.recycle()

                    return
                }


                /*
                 * ====================================================
                 * PROMOTION FAST-PATH STABILITY REQUIREMENT
                 * ====================================================
                 *
                 * This does NOT guess that a promotion happened.
                 * It merely lets the already-existing move detector inspect
                 * the settled board one frame earlier when promotion is
                 * actually available from the CURRENT virtual position.
                 */
                val promotionFastPathActive =
                    hasPromotionMoveAvailableForActiveSide()

                val requiredStableFrames =
                    if (promotionFastPathActive) {
                        PROMOTION_REQUIRED_STABLE_FRAMES
                    } else {
                        REQUIRED_STABLE_FRAMES
                    }

                if (promotionFastPathActive) {
                    Log.d(
                        "ChessAI_Promotion",
                        "PROMOTION_FAST_PATH active=${virtualBoard.getActiveColor()} " +
                                "stableRequirement=$requiredStableFrames"
                    )
                }


                /*
                 * ====================================================
                 * 5. STABILITY FILTER
                 * ====================================================
                 *
                 * We DON'T compare the current frame directly to the
                 * previous official chess position.
                 *
                 * Instead:
                 *
                 * current → pending
                 *
                 * then:
                 *
                 * current ≈ pending
                 *
                 * repeatedly.
                 *
                 * Only after REQUIRED_STABLE_FRAMES do we analyze
                 * the move.
                 */
                if (pendingStableFrame == null) {

                    pendingStableFrame =
                        standardFrame.copy(
                            Bitmap.Config.ARGB_8888,
                            false
                        )

                    stableFrameCount = 1


                    Log.d(
                        "ChessAI_Stability",
                        "New candidate frame. " +
                                "Stable count = 1/$requiredStableFrames"
                    )


                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardFrame.recycle()
                    currentFrame.recycle()

                    return
                }


                /*
                 * Compare the new frame to the previous candidate.
                 */
                val isStable =
                    ChessBoardUtils.areBitmapsSimilar(
                        pendingStableFrame,
                        standardFrame
                    )


                if (!isStable) {

                    /*
                     * Animation is still happening or the board has
                     * changed.
                     *
                     * Throw away the old candidate and start again.
                     */
                    pendingStableFrame?.recycle()


                    pendingStableFrame =
                        standardFrame.copy(
                            Bitmap.Config.ARGB_8888,
                            false
                        )


                    stableFrameCount = 1


                    Log.d(
                        "ChessAI_Stability",
                        "Board changed. " +
                                "Reset stable count = 1/$requiredStableFrames"
                    )


                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardFrame.recycle()
                    currentFrame.recycle()

                    return
                }


                /*
                 * Frame matches the previous candidate.
                 */
                stableFrameCount++


                Log.d(
                    "ChessAI_Stability",
                    "Stable frame confirmed: " +
                            "$stableFrameCount/$requiredStableFrames"
                )


                /*
                 * Still not stable enough.
                 */
                if (
                    stableFrameCount <
                    requiredStableFrames
                ) {

                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardFrame.recycle()
                    currentFrame.recycle()

                    return
                }


                /*
                 * ====================================================
                 * 6. FINAL STABLE FRAME
                 * ====================================================
                 *
                 * We now have a genuinely stable board frame.
                 *
                 * This is the ONLY point at which we ask the chess
                 * detector to determine a move.
                 */
                Log.d(
                    "ChessAI_Stability",
                    "Board is STABLE. Analyzing move."
                )


                /*
                 * ====================================================
                 * 5B. PENDING PROMOTION FALLBACK
                 * ====================================================
                 */
                val waitingPromotion =
                    pendingPromotionMove

                if (waitingPromotion != null) {

                    val chosenPiece =
                        pendingPromotionUserChoice

                    if (chosenPiece == null) {

                        Log.d(
                            "ChessAI_Promotion",
                            "WAITING_FOR_MANUAL_PROMOTION_CHOICE " +
                                    "${ChessBoardUtils.indexToSquareName(waitingPromotion.fromIdx)}->" +
                                    ChessBoardUtils.indexToSquareName(waitingPromotion.toIdx)
                        )

                        pendingStableFrame?.recycle()
                        pendingStableFrame = null
                        stableFrameCount = 0

                        releaseOpenCv(
                            mat,
                            grayMat,
                            blurred,
                            thresh,
                            hierarchy
                        )

                        standardFrame.recycle()
                        currentFrame.recycle()
                        return
                    }

                    val fenBeforePromotion =
                        virtualBoard.generateFen()

                    val updatedPromotionFen =
                        virtualBoard.applyPromotionMove(
                            fromIndex = waitingPromotion.fromIdx,
                            toIndex = waitingPromotion.toIdx,
                            promotionPiece = chosenPiece
                        )

                    if (updatedPromotionFen == fenBeforePromotion) {

                        Log.e(
                            "ChessAI_Promotion",
                            "MANUAL_PROMOTION_APPLY_FAILED " +
                                    "${ChessBoardUtils.indexToSquareName(waitingPromotion.fromIdx)}->" +
                                    "${ChessBoardUtils.indexToSquareName(waitingPromotion.toIdx)} " +
                                    "piece=$chosenPiece"
                        )

                    } else {

                        val promotionSquares =
                            setOf(
                                waitingPromotion.fromIdx,
                                waitingPromotion.toIdx
                            )

                        val patchedPromotionBaseline =
                            patchAcceptedMoveIntoBaseline(
                                oldBaseline = previousBoardBitmap!!,
                                currentFrame = standardFrame,
                                squareIndices = promotionSquares
                            )

                        previousBoardBitmap?.recycle()
                        previousBoardBitmap =
                            patchedPromotionBaseline

                        updatePersistentResidues(
                            changedIndices =
                                ChessBoardUtils.lastMeaningfulChangedIndices,
                            explainedSquares =
                                promotionSquares
                        )

                        lastAcceptedMoveTime =
                            System.currentTimeMillis()

                        Log.d(
                            "ChessAI_Promotion",
                            "MANUAL_PROMOTION_APPLIED " +
                                    "${ChessBoardUtils.indexToSquareName(waitingPromotion.fromIdx)}->" +
                                    "${ChessBoardUtils.indexToSquareName(waitingPromotion.toIdx)} " +
                                    "piece=$chosenPiece | FEN before=$fenBeforePromotion | " +
                                    "FEN after=$updatedPromotionFen"
                        )

                        val promotionFrom =
                            ChessBoardUtils.indexToSquareName(
                                waitingPromotion.fromIdx
                            )

                        val promotionTo =
                            ChessBoardUtils.indexToSquareName(
                                waitingPromotion.toIdx
                            )

                        val promotionMovedSide =
                            if (
                                updatedPromotionFen
                                    .split(" ")
                                    .getOrNull(1) == "w"
                            ) {
                                "BLACK"
                            } else {
                                "WHITE"
                            }

                        Log.d(
                            LIVE_TAG,
                            "MOVE: $promotionMovedSide " +
                                    "$promotionFrom -> $promotionTo=$chosenPiece"
                        )

                        Log.d(
                            LIVE_TAG,
                            "FEN: $updatedPromotionFen"
                        )

                        requestStockfishAnalysis(
                            fen = updatedPromotionFen
                        )
                    }

                    pendingPromotionMove = null
                    pendingPromotionUserChoice = null

                    pendingStableFrame?.recycle()
                    pendingStableFrame = null
                    stableFrameCount = 0

                    releaseOpenCv(
                        mat,
                        grayMat,
                        blurred,
                        thresh,
                        hierarchy
                    )

                    standardFrame.recycle()
                    currentFrame.recycle()
                    return
                }


                /*
                 * ====================================================
                 * 6. HYBRID MOVE DETECTION — NO RECOVERY / NO CATCH-UP
                 * ====================================================
                 *
                 * The detector combines:
                 *
                 * - visual changed-square evidence
                 * - VirtualChessBoard legality
                 * - source/destination occupancy checks
                 *
                 * IMPORTANT FOR LIVE STOCKFISH USE:
                 *
                 * We never invent a later move, never skip a ply, and never
                 * modify the FEN unless one move is confidently detected.
                 *
                 * If the board has an incomplete/transient visual change
                 * (for example only the source square during animation, or
                 * leftover rook rendering after castling), we simply keep
                 * the previous official baseline and scan again.
                 */
                val detectedMove =
                    ChessBoardUtils.detectMoveFromFrames(
                        oldBoard = previousBoardBitmap!!,
                        newBoard = standardFrame,
                        virtualBoard = virtualBoard,
                        persistentResidueSquares =
                            persistentResidueSquares.toSet()
                    )


                if (detectedMove != null) {

                    val sq1 =
                        ChessBoardUtils.indexToSquareName(
                            detectedMove.fromIdx
                        )

                    val sq2 =
                        ChessBoardUtils.indexToSquareName(
                            detectedMove.toIdx
                        )

                    val fenBefore =
                        virtualBoard.generateFen()

                    /*
                     * Capture the PRE-MOVE virtual state before applyMove().
                     *
                     * We use this only to determine which board squares are
                     * legitimately explained by the accepted move.
                     */
                    val movedPieceBefore =
                        virtualBoard.getPieceAt(
                            detectedMove.fromIdx
                        )

                    val destinationPieceBefore =
                        virtualBoard.getPieceAt(
                            detectedMove.toIdx
                        )

                    val baselineSquaresToPatch =
                        getBaselineSquaresForAcceptedMove(
                            fromIdx = detectedMove.fromIdx,
                            toIdx = detectedMove.toIdx,
                            movedPieceBefore = movedPieceBefore,
                            destinationPieceBefore = destinationPieceBefore
                        )

                    /*
                     * Apply STANDARD indices directly.
                     *
                     * Promotion cannot use ordinary applyMove(), because the
                     * resulting Q/R/B/N must be known first.
                     */
                    val isPromotion =
                        virtualBoard.isPromotionMove(
                            detectedMove.fromIdx,
                            detectedMove.toIdx
                        )

                    val updatedFen =
                        if (isPromotion) {

                            val recognition =
                                PromotionRecognizer.recognize(
                                    standardBoardBitmap = standardFrame,
                                    targetIndex = detectedMove.toIdx,
                                    whitePiece =
                                        movedPieceBefore?.isUpperCase() == true
                                )

                            Log.d(
                                "ChessAI_Promotion",
                                "PROMOTION_RECOGNITION $sq1->$sq2 " +
                                        "best=${recognition.piece ?: '?'} " +
                                        "score=${String.format("%.3f", recognition.bestScore)} " +
                                        "second=${String.format("%.3f", recognition.secondScore)} " +
                                        "confident=${recognition.confident} " +
                                        "scores=${recognition.scores}"
                            )

                            if (
                                recognition.confident &&
                                recognition.piece != null
                            ) {

                                val recognizedPiece =
                                    recognition.piece.lowercaseChar()

                                val fen =
                                    virtualBoard.applyPromotionMove(
                                        fromIndex = detectedMove.fromIdx,
                                        toIndex = detectedMove.toIdx,
                                        promotionPiece = recognizedPiece
                                    )

                                Log.d(
                                    "ChessAI_Promotion",
                                    "AUTO_PROMOTION_APPLIED " +
                                            "$sq1->$sq2=$recognizedPiece"
                                )

                                fen

                            } else {

                                pendingPromotionMove =
                                    detectedMove

                                pendingPromotionUserChoice =
                                    null

                                try {
                                    startService(
                                        Intent(
                                            this,
                                            OverlayPromptService::class.java
                                        ).apply {
                                            action =
                                                OverlayPromptService
                                                    .ACTION_SHOW_PROMOTION
                                        }
                                    )
                                } catch (e: Exception) {
                                    Log.e(
                                        "ChessAI_Promotion",
                                        "Failed to show fallback promotion prompt.",
                                        e
                                    )
                                }

                                Log.d(
                                    "ChessAI_Promotion",
                                    "PROMOTION_NEEDS_MANUAL_CHOICE " +
                                            "$sq1->$sq2. FEN unchanged."
                                )

                                pendingStableFrame?.recycle()
                                pendingStableFrame = null
                                stableFrameCount = 0

                                releaseOpenCv(
                                    mat,
                                    grayMat,
                                    blurred,
                                    thresh,
                                    hierarchy
                                )

                                standardFrame.recycle()
                                currentFrame.recycle()
                                return
                            }

                        } else {

                            virtualBoard.applyMove(
                                detectedMove.fromIdx,
                                detectedMove.toIdx
                            )
                        }

                    val label =
                        if (detectedMove.isUserMove) {
                            "USER (YOU)"
                        } else {
                            "OPPONENT"
                        }

                    Log.d(
                        "ChessAI",
                        "[$label] Move Detected: " +
                                "$sq1 -> $sq2 | " +
                                "FEN: $updatedFen"
                    )

                    Log.d(
                        "ChessAI_State",
                        "FEN before=$fenBefore | FEN after=$updatedFen"
                    )

                    val movedSide =
                        if (
                            updatedFen
                                .split(" ")
                                .getOrNull(1) == "w"
                        ) {
                            "BLACK"
                        } else {
                            "WHITE"
                        }

                    Log.d(
                        LIVE_TAG,
                        "MOVE: $movedSide $sq1 -> $sq2"
                    )

                    Log.d(
                        LIVE_TAG,
                        "FEN: $updatedFen"
                    )

                    requestStockfishAnalysis(
                        fen = updatedFen
                    )

                    /*
                     * -------------------------------------------------
                     * PATCH-ONLY BASELINE UPDATE
                     * -------------------------------------------------
                     *
                     * DO NOT replace the entire baseline with the current
                     * screenshot.
                     *
                     * The current frame may already contain:
                     * - the user's click/selection animation for the NEXT move
                     * - a hover/ripple
                     * - a lingering highlight on an unrelated square
                     *
                     * Only copy the squares that this accepted chess move
                     * actually explains.
                     *
                     * Example:
                     *   ...h5->g4 is accepted while d1 is already selected.
                     *
                     * Old behavior:
                     *   entire screenshot became baseline, so animated d1 was
                     *   accidentally stored and Qd1xg4 later lost its source.
                     *
                     * New behavior:
                     *   patch only h5 and g4. d1 stays identical to the old
                     *   official baseline until a real d1 move is accepted.
                     */
                    val patchedBaseline =
                        patchAcceptedMoveIntoBaseline(
                            oldBaseline = previousBoardBitmap!!,
                            currentFrame = standardFrame,
                            squareIndices = baselineSquaresToPatch
                        )

                    previousBoardBitmap?.recycle()
                    previousBoardBitmap =
                        patchedBaseline

                    Log.d(
                        "ChessAI_Baseline",
                        "PATCHED accepted move $sq1->$sq2 squares=" +
                                baselineSquaresToPatch
                                    .sorted()
                                    .joinToString(
                                        prefix = "[",
                                        postfix = "]"
                                    ) {
                                        ChessBoardUtils.indexToSquareName(it)
                                    }
                    )

                    updatePersistentResidues(
                        changedIndices =
                            ChessBoardUtils.lastMeaningfulChangedIndices,
                        explainedSquares =
                            baselineSquaresToPatch
                    )

                    val acceptedAt =
                        System.currentTimeMillis()

                    lastAcceptedMoveTime =
                        acceptedAt

                    unresolvedStablePasses = 0

                    Log.d(
                        "ChessAI_Stability",
                        "Move accepted. Starting ${MOVE_COOLDOWN_MS}ms cooldown."
                    )

                } else {

                    updatePersistentResidues(
                        changedIndices =
                            ChessBoardUtils.lastMeaningfulChangedIndices,
                        explainedSquares =
                            emptySet()
                    )

                    when (
                        ChessBoardUtils.lastDetectionOutcome
                    ) {

                        ChessBoardUtils.DetectionOutcome.NO_CHANGE -> {

                            unresolvedStablePasses = 0

                            /*
                             * Nothing happened. This includes fully settled
                             * idle frames and empty-square clicks.
                             */
                            Log.d(
                                "ChessAI_Stability",
                                "No meaningful board change. Continuing scan."
                            )
                        }


                        ChessBoardUtils.DetectionOutcome.UNRESOLVED -> {

                            /*
                             * Something changed, but not enough evidence
                             * exists for one legal + occupancy-confirmed move.
                             *
                             * DO NOT:
                             * - change FEN
                             * - change active side
                             * - update previousBoardBitmap
                             * - enter catch-up
                             * - enter DESYNC lock
                             *
                             * Just scan the next settled frame. If this was
                             * animation/source-only evidence, the destination
                             * should appear on a later frame and the SAME move
                             * can then be detected normally.
                             */
                            Log.d(
                                "ChessAI_MoveDetection",
                                "UNRESOLVED transient/partial change. " +
                                        "FEN and official baseline unchanged; rescanning."
                            )

                            unresolvedStablePasses++

                            /*
                             * HARD RESYNC is intentionally MANUAL ONLY.
                             *
                             * An unresolved frame never launches extra image
                             * processing in the background.
                             */
                        }


                        ChessBoardUtils.DetectionOutcome.MOVE_FOUND -> {
                            /*
                             * Defensive fallback. MOVE_FOUND should have
                             * returned a non-null DetectedMove above.
                             */
                            Log.w(
                                "ChessAI_MoveDetection",
                                "MOVE_FOUND outcome without DetectedMove; ignoring frame."
                            )
                        }
                    }
                }


                /*
                 * Start a fresh stability cycle.
                 */
                pendingStableFrame?.recycle()
                pendingStableFrame = null
                stableFrameCount = 0


                releaseOpenCv(
                    mat,
                    grayMat,
                    blurred,
                    thresh,
                    hierarchy
                )


                standardFrame.recycle()
                currentFrame.recycle()

                return
            }


            /*
             * --------------------------------------------------------
             * NO BOARD FOUND
             * --------------------------------------------------------
             */
            Log.d(
                "ChessAI",
                "Chessboard not detected in current frame."
            )


            releaseOpenCv(
                mat,
                grayMat,
                blurred,
                thresh,
                hierarchy
            )

        } catch (e: Exception) {

            Log.e(
                "ChessAI",
                "OpenCV grid processing error",
                e
            )

        } finally {

            /*
             * The individual Mats are released above on normal
             * execution. This is kept as a safety cleanup.
             */
        }
    }




    /*
     * ============================================================
     * STOCKFISH ANALYSIS
     * ============================================================
     */

    private fun requestStockfishAnalysis(
        fen: String
    ) {

        /*
         * Never analyze the stale virtual position after a failed RESYNC.
         */
        if (resyncFailureFrozen) {

            Log.w(
                LIVE_TAG,
                "BEST 3 SUPPRESSED: tracking is frozen after failed RESYNC."
            )

            hideRecommendationOverlay()

            return
        }


        val engine =
            stockfishEngine
                ?: run {

                    Log.e(
                        LIVE_TAG,
                        "BEST 3: STOCKFISH NOT AVAILABLE"
                    )

                    hideRecommendationOverlay()

                    return
                }

        val sideToMove =
            if (
                fen
                    .split(" ")
                    .getOrNull(1) == "b"
            ) {
                "BLACK"
            } else {
                "WHITE"
            }

        val generation =
            ++stockfishRequestGeneration

        engine.analyzeTopMoves(
            fen = fen,
            multiPv = 5,
            moveTimeMs = 400L,
            onError = { error ->

                /*
                 * StockfishEngine already restarted the native process and
                 * retried once before this callback is invoked.
                 *
                 * Do NOT translate an engine failure into
                 * "checkmate/stalemate/no legal moves".
                 */
                if (
                    generation !=
                    stockfishRequestGeneration
                ) {
                    return@analyzeTopMoves
                }


                Handler(mainLooper).post {

                    if (
                        generation !=
                        stockfishRequestGeneration
                    ) {
                        return@post
                    }


                    Log.e(
                        LIVE_TAG,
                        "STOCKFISH ANALYSIS UNAVAILABLE: ${error.message}"
                    )


                    latestRecommendationSide =
                        null

                    latestRecommendationMoves =
                        emptyList()

                    hideRecommendationOverlay()
                }
            }
        ) { result ->

            /*
             * A newer board may already have been accepted while Stockfish
             * was thinking. Never print an obsolete recommendation set.
             */
            if (
                generation !=
                stockfishRequestGeneration
            ) {
                return@analyzeTopMoves
            }

            Handler(mainLooper).post {

                if (
                    generation !=
                    stockfishRequestGeneration ||
                    resyncFailureFrozen
                ) {
                    return@post
                }


                val safeResult =
                    result
                        .filterNot {
                                suggestion ->
                            isDisallowedCastleMove(
                                suggestion.move
                            )
                        }
                        .take(3)


                if (safeResult.isEmpty()) {

                    /*
                     * We only reach this branch after a SUCCESSFUL engine
                     * analysis. Engine failures use onError above.
                     */
                    Log.d(
                        LIVE_TAG,
                        "BEST 3 FOR $sideToMove: none " +
                                "(checkmate/stalemate/no legal moves)"
                    )

                    latestRecommendationSide =
                        null

                    latestRecommendationMoves =
                        emptyList()

                    hideRecommendationOverlay()

                    return@post
                }

                val formatted =
                    safeResult
                        .mapIndexed {
                                index,
                                suggestion ->

                            val score =
                                when {

                                    suggestion.mate != null -> {

                                        val mate =
                                            suggestion.mate

                                        if (mate > 0) {
                                            "M+$mate"
                                        } else {
                                            "M$mate"
                                        }
                                    }

                                    suggestion.centipawns != null -> {

                                        String.format(
                                            "%+.2f",
                                            suggestion.centipawns /
                                                    100.0
                                        )
                                    }

                                    else -> {
                                        "?"
                                    }
                                }

                            "${index + 1}) ${suggestion.move} [$score]"
                        }
                        .joinToString(" | ")

                Log.d(
                    LIVE_TAG,
                    "BEST 3 FOR $sideToMove: $formatted"
                )

                latestRecommendationSide =
                    sideToMove

                latestRecommendationMoves =
                    safeResult

                if (recommendationsVisible) {

                    updateRecommendationOverlay(
                        sideToMove = sideToMove,
                        moves = latestRecommendationMoves
                    )
                }
            }
        }
    }


    /*
     * ============================================================
     * RECOMMENDATION OVERLAY PIXEL SUPPRESSION
     * ============================================================
     *
     * This is the no-blink solution.
     *
     * We know the exact UCI moves that RecommendationOverlayService is
     * drawing. Build the same geometry in BOARD coordinates, slightly wider
     * than the visible strokes to include anti-aliasing, then reconstruct
     * those narrow pixels from nearby clean pixels in the CURRENT frame.
     *
     * IMPORTANT:
     * - arrows stay visible to the user continuously
     * - no capture frames are intentionally skipped
     * - Gemini / RESYNC is untouched
     * - only the narrow known overlay geometry is ignored
     */
    private fun suppressRecommendationOverlayPixels(
        capturedBoard: Bitmap
    ): Bitmap {

        val baseline =
            previousBoardBitmap
                ?: return capturedBoard.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

        if (
            !recommendationsVisible ||
            latestRecommendationMoves.isEmpty() ||
            baseline.width != capturedBoard.width ||
            baseline.height != capturedBoard.height
        ) {
            return capturedBoard.copy(
                Bitmap.Config.ARGB_8888,
                false
            )
        }

        val width =
            capturedBoard.width

        val height =
            capturedBoard.height

        val result =
            capturedBoard.copy(
                Bitmap.Config.ARGB_8888,
                true
            )

        val mask =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(mask)

        canvas.drawColor(
            Color.TRANSPARENT
        )

        val density =
            resources.displayMetrics.density

        /*
         * The visible overlay uses 4dp for strong arrows, 3dp for square
         * outlines and 1.6dp for faint arrows. Make the exclusion strokes a
         * little wider so anti-aliased edge pixels are covered too.
         */
        val strongMaskPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = density * 7.0f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        val faintMaskPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = density * 4.0f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        val squareMaskPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = density * 6.0f
                strokeJoin = Paint.Join.ROUND
            }

        val topMoves =
            latestRecommendationMoves
                .take(3)
                .mapNotNull {
                    normalizeOverlayUci(it.move)
                }

        val topMoveSet =
            topMoves.toSet()

        /*
         * Faint arrows exist only when it is the opponent's turn.
         */
        val side =
            latestRecommendationSide

        val opponentToMove =
            when (side) {
                "WHITE" ->
                    !ScreenRecordService.selectedUserIsWhite

                "BLACK" ->
                    ScreenRecordService.selectedUserIsWhite

                else ->
                    false
            }

        if (opponentToMove) {

            virtualBoard
                .getAllLegalMovesUci()
                .mapNotNull {
                    normalizeOverlayUci(it)
                }
                .distinct()
                .filterNot {
                    it in topMoveSet
                }
                .filterNot {
                    isDisallowedCastleMove(it)
                }
                .forEach { uci ->

                    drawOverlayArrowMask(
                        canvas = canvas,
                        uci = uci,
                        paint = faintMaskPaint,
                        boardWidth = width,
                        boardHeight = height,
                        strong = false
                    )
                }
        }

        /*
         * Strong top-3 arrows and their source/destination square outlines.
         */
        val outlinedSquares =
            mutableSetOf<String>()

        topMoves.forEach { uci ->

            drawOverlayArrowMask(
                canvas = canvas,
                uci = uci,
                paint = strongMaskPaint,
                boardWidth = width,
                boardHeight = height,
                strong = true
            )

            outlinedSquares.add(
                uci.substring(0, 2)
            )

            outlinedSquares.add(
                uci.substring(2, 4)
            )
        }

        outlinedSquares.forEach { square ->

            drawOverlaySquareMask(
                canvas = canvas,
                square = square,
                paint = squareMaskPaint,
                boardWidth = width,
                boardHeight = height
            )
        }

        /*
         * ================================================================
         * CURRENT-FRAME INPAINTING
         * ================================================================
         *
         * DO NOT copy masked pixels from previousBoardBitmap.
         *
         * That earlier approach caused a serious bug:
         * if the real move was underneath a recommendation arrow, copying
         * the OLD piece pixels back into the source/destination made the
         * occupancy detector believe the piece had never moved.
         *
         * Instead, reconstruct each narrow overlay pixel from nearby
         * UNMASKED pixels in the CURRENT captured frame.
         *
         * This preserves the new chess position:
         * - vacated source -> neighbours are the empty board square
         * - occupied destination -> neighbours are the moved piece / square
         *
         * The arrow itself is narrow, so local interpolation is enough.
         */
        val capturedPixels =
            IntArray(
                width * height
            )

        val maskPixels =
            IntArray(
                width * height
            )

        result.getPixels(
            capturedPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        mask.getPixels(
            maskPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val originalPixels =
            capturedPixels.copyOf()

        /*
         * Search only a short distance from the overlay stroke.
         * The mask is intentionally thin, so normally a valid sample is
         * found within just a few pixels.
         */
        val maxSearchRadius =
            maxOf(
                6,
                (resources.displayMetrics.density * 4.0f).toInt()
            )

        fun isMasked(
            x: Int,
            y: Int
        ): Boolean {

            if (
                x !in 0 until width ||
                y !in 0 until height
            ) {
                return true
            }

            return Color.alpha(
                maskPixels[
                    y * width + x
                ]
            ) > 0
        }

        fun currentPixel(
            x: Int,
            y: Int
        ): Int =
            originalPixels[
                y * width + x
            ]

        for (y in 0 until height) {

            for (x in 0 until width) {

                val index =
                    y * width + x

                if (
                    Color.alpha(
                        maskPixels[index]
                    ) == 0
                ) {
                    continue
                }

                var redSum = 0
                var greenSum = 0
                var blueSum = 0
                var alphaSum = 0
                var sampleCount = 0

                /*
                 * Sample nearest clean pixels in 8 directions.
                 * Stop after the first clean pixel in each direction so
                 * reconstruction stays local and does not blur whole pieces.
                 */
                val directions =
                    arrayOf(
                        intArrayOf(-1, 0),
                        intArrayOf(1, 0),
                        intArrayOf(0, -1),
                        intArrayOf(0, 1),
                        intArrayOf(-1, -1),
                        intArrayOf(1, -1),
                        intArrayOf(-1, 1),
                        intArrayOf(1, 1)
                    )

                for (direction in directions) {

                    for (
                    radius in
                    1..maxSearchRadius
                    ) {

                        val sx =
                            x +
                                    direction[0] *
                                    radius

                        val sy =
                            y +
                                    direction[1] *
                                    radius

                        if (
                            sx !in 0 until width ||
                            sy !in 0 until height
                        ) {
                            break
                        }

                        if (
                            !isMasked(
                                sx,
                                sy
                            )
                        ) {

                            val pixel =
                                currentPixel(
                                    sx,
                                    sy
                                )

                            alphaSum +=
                                Color.alpha(
                                    pixel
                                )

                            redSum +=
                                Color.red(
                                    pixel
                                )

                            greenSum +=
                                Color.green(
                                    pixel
                                )

                            blueSum +=
                                Color.blue(
                                    pixel
                                )

                            sampleCount++

                            break
                        }
                    }
                }

                /*
                 * If no clean neighbour could be found, leave the captured
                 * pixel alone. Never fall back to the OLD baseline because
                 * that is what erased real moves.
                 */
                if (
                    sampleCount > 0
                ) {

                    capturedPixels[index] =
                        Color.argb(
                            alphaSum /
                                    sampleCount,
                            redSum /
                                    sampleCount,
                            greenSum /
                                    sampleCount,
                            blueSum /
                                    sampleCount
                        )
                }
            }
        }

        result.setPixels(
            capturedPixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        mask.recycle()

        return result
    }


    private fun normalizeOverlayUci(
        rawMove: String
    ): String? {

        val move =
            rawMove
                .trim()
                .lowercase()

        if (move.length < 4) {
            return null
        }

        val base =
            move.substring(
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
            overlaySquareCenter(
                from,
                800,
                800
            ) == null ||
            overlaySquareCenter(
                to,
                800,
                800
            ) == null
        ) {
            return null
        }

        return base
    }


    private fun overlaySquareCenter(
        square: String,
        boardWidth: Int,
        boardHeight: Int
    ): Pair<Float, Float>? {

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

        /*
         * capturedBoard is already normalized to standard orientation:
         * top-left=a8, bottom-right=h1.
         */
        val col =
            file - 'a'

        val rankNumber =
            rank - '0'

        val row =
            8 - rankNumber

        val squareWidth =
            boardWidth / 8f

        val squareHeight =
            boardHeight / 8f

        return Pair(
            (col + 0.5f) * squareWidth,
            (row + 0.5f) * squareHeight
        )
    }


    private fun drawOverlayArrowMask(
        canvas: Canvas,
        uci: String,
        paint: Paint,
        boardWidth: Int,
        boardHeight: Int,
        strong: Boolean
    ) {

        if (uci.length < 4) {
            return
        }

        val start =
            overlaySquareCenter(
                uci.substring(0, 2),
                boardWidth,
                boardHeight
            ) ?: return

        val destination =
            overlaySquareCenter(
                uci.substring(2, 4),
                boardWidth,
                boardHeight
            ) ?: return

        val dx =
            destination.first -
                    start.first

        val dy =
            destination.second -
                    start.second

        val length =
            kotlin.math.sqrt(
                dx * dx +
                        dy * dy
            )

        if (length < 1f) {
            return
        }

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
         * Match RecommendationOverlayService exactly: leave the center of the
         * source square untouched so current-frame inpainting never has to
         * reconstruct the vacated piece center.
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
                    unitX * startInset

        val startY =
            start.second +
                    unitY * startInset

        val endX =
            destination.first -
                    unitX * endInset

        val endY =
            destination.second -
                    unitY * endInset

        canvas.drawLine(
            startX,
            startY,
            endX,
            endY,
            paint
        )

        /*
         * Mask the two arrow-head arms too.
         */
        val angle =
            kotlin.math.atan2(
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

        val halfAngle =
            if (strong) {
                Math.toRadians(28.0)
            } else {
                Math.toRadians(24.0)
            }

        val leftX =
            endX -
                    headLength *
                    kotlin.math.cos(
                        angle -
                                halfAngle
                    ).toFloat()

        val leftY =
            endY -
                    headLength *
                    kotlin.math.sin(
                        angle -
                                halfAngle
                    ).toFloat()

        val rightX =
            endX -
                    headLength *
                    kotlin.math.cos(
                        angle +
                                halfAngle
                    ).toFloat()

        val rightY =
            endY -
                    headLength *
                    kotlin.math.sin(
                        angle +
                                halfAngle
                    ).toFloat()

        val head =
            Path().apply {
                moveTo(
                    leftX,
                    leftY
                )

                lineTo(
                    endX,
                    endY
                )

                lineTo(
                    rightX,
                    rightY
                )
            }

        canvas.drawPath(
            head,
            paint
        )
    }


    private fun drawOverlaySquareMask(
        canvas: Canvas,
        square: String,
        paint: Paint,
        boardWidth: Int,
        boardHeight: Int
    ) {

        if (square.length != 2) {
            return
        }

        val file =
            square[0]

        val rank =
            square[1]

        if (
            file !in 'a'..'h' ||
            rank !in '1'..'8'
        ) {
            return
        }

        val col =
            file - 'a'

        val row =
            8 -
                    (rank - '0')

        val squareWidth =
            boardWidth / 8f

        val squareHeight =
            boardHeight / 8f

        /*
         * The real overlay nests shared top-3 outlines slightly inward.
         * A single slightly wider border mask covers all of those strokes.
         */
        val inset =
            paint.strokeWidth / 2f +
                    resources.displayMetrics.density * 1.0f

        val left =
            col * squareWidth +
                    inset

        val top =
            row * squareHeight +
                    inset

        val right =
            (col + 1) * squareWidth -
                    inset

        val bottom =
            (row + 1) * squareHeight -
                    inset

        canvas.drawRect(
            left,
            top,
            right,
            bottom,
            paint
        )
    }


    /*
     * ============================================================
     * RECOMMENDATION OVERLAY
     * ============================================================
     */

    private fun updateRecommendationOverlay(
        sideToMove: String,
        moves: List<StockfishEngine.SuggestedMove>
    ) {

        val lines =
            moves
                .take(3)
                .mapIndexed {
                        index,
                        suggestion ->

                    val score =
                        when {

                            suggestion.mate != null -> {

                                val mate =
                                    suggestion.mate

                                if (mate > 0) {
                                    "M+$mate"
                                } else {
                                    "M$mate"
                                }
                            }

                            suggestion.centipawns != null -> {

                                String.format(
                                    "%+.2f",
                                    suggestion.centipawns /
                                            100.0
                                )
                            }

                            else -> {
                                "?"
                            }
                        }

                    "${index + 1}) ${suggestion.move} [$score]"
                }

        val intent =
            Intent(
                this,
                RecommendationOverlayService::class.java
            ).apply {

                action =
                    RecommendationOverlayService.ACTION_UPDATE

                putExtra(
                    RecommendationOverlayService.EXTRA_SIDE,
                    sideToMove
                )

                putStringArrayListExtra(
                    RecommendationOverlayService.EXTRA_LINES,
                    ArrayList(lines)
                )

                putStringArrayListExtra(
                    RecommendationOverlayService.EXTRA_UCI_MOVES,
                    ArrayList(
                        moves
                            .take(3)
                            .map { it.move }
                    )
                )

                /*
                 * On the OPPONENT'S turn only, also send every locally legal
                 * move. These are drawn as faint arrows underneath Stockfish's
                 * top 3.
                 *
                 * This does NOT ask Stockfish for more lines and therefore does
                 * not make engine analysis heavier.
                 */
                val opponentToMove =
                    if (sideToMove == "WHITE") {
                        !ScreenRecordService.selectedUserIsWhite
                    } else {
                        ScreenRecordService.selectedUserIsWhite
                    }

                val allLegalOpponentMoves =
                    if (opponentToMove) {

                        virtualBoard
                            .getAllLegalMovesUci()
                            .filterNot {
                                isDisallowedCastleMove(it)
                            }

                    } else {

                        emptyList()
                    }

                putStringArrayListExtra(
                    RecommendationOverlayService.EXTRA_ALL_LEGAL_MOVES,
                    ArrayList(
                        allLegalOpponentMoves
                    )
                )

                putExtra(
                    RecommendationOverlayService.EXTRA_BOARD_X,
                    latestBoardX
                )

                putExtra(
                    RecommendationOverlayService.EXTRA_BOARD_Y,
                    latestBoardY
                )

                putExtra(
                    RecommendationOverlayService.EXTRA_BOARD_WIDTH,
                    latestBoardWidth
                )

                putExtra(
                    RecommendationOverlayService.EXTRA_BOARD_HEIGHT,
                    latestBoardHeight
                )

                putExtra(
                    RecommendationOverlayService.EXTRA_USER_IS_WHITE,
                    ScreenRecordService.selectedUserIsWhite
                )
            }

        try {
            startService(intent)
        } catch (e: Exception) {

            Log.e(
                LIVE_TAG,
                "OVERLAY ERROR: ${e.message}"
            )
        }
    }


    private fun hideRecommendationOverlay() {

        try {

            startService(
                Intent(
                    this,
                    RecommendationOverlayService::class.java
                ).apply {
                    action =
                        RecommendationOverlayService.ACTION_HIDE
                }
            )

        } catch (_: Exception) {
        }
    }



    /*
     * ============================================================
     * MANUAL MISSED-MOVE REPAIR
     * ============================================================
     *
     * Destination-first:
     *
     * MISSED -> tap destination.
     *
     * If only one legal source can reach that destination, the app repairs the
     * move immediately. If several legal sources can reach it, one more source
     * tap identifies the exact piece.
     *
     * No Gemini. No automatic recovery. Normal detector settings are untouched.
     */

    private fun startManualMissedRepair() {

        if (
            isShuttingDown ||
            isResyncing ||
            hardResyncRequested ||
            resyncFailureFrozen ||
            !isBoardSetupConfirmed ||
            latestBoardWidth <= 0 ||
            latestBoardHeight <= 0
        ) {

            setMissedMoveUiState(
                StopOverlayService.MISSED_FAILED
            )

            return
        }


        manualMissedRepairActive =
            true

        manualMissedDestination =
            null

        manualMissedCandidates =
            emptyList()


        pendingStableFrame?.recycle()

        pendingStableFrame =
            null

        stableFrameCount =
            0


        hideRecommendationOverlay()


        setMissedMoveUiState(
            StopOverlayService.MISSED_TAP_TO
        )


        Log.d(
            LIVE_TAG,
            "MISSED REPAIR: tap destination square."
        )
    }


    private fun cancelManualMissedRepair() {

        manualMissedRepairActive =
            false

        manualMissedDestination =
            null

        manualMissedCandidates =
            emptyList()


        pendingStableFrame?.recycle()

        pendingStableFrame =
            null

        stableFrameCount =
            0


        setMissedMoveUiState(
            StopOverlayService.MISSED_IDLE
        )


        if (
            recommendationsVisible &&
            latestRecommendationSide != null &&
            latestRecommendationMoves.isNotEmpty()
        ) {

            updateRecommendationOverlay(
                sideToMove =
                    latestRecommendationSide!!,
                moves =
                    latestRecommendationMoves
            )
        }


        Log.d(
            LIVE_TAG,
            "MISSED REPAIR cancelled. Normal tracking unchanged."
        )
    }


    private fun handleManualMissedSquare(
        squareIndex: Int
    ) {

        if (!manualMissedRepairActive) {
            return
        }


        val destination =
            manualMissedDestination


        /*
         * FIRST TAP = destination.
         */
        if (destination == null) {

            val candidates =
                virtualBoard
                    .getAllLegalMovesUci()
                    .filter {
                            move ->

                        move.length >= 4 &&
                                uciSquareToIndex(
                                    move.substring(
                                        2,
                                        4
                                    )
                                ) == squareIndex
                    }
                    /*
                     * Promotion generates q/r/b/n variants with the same source
                     * and destination. Group those variants as one path.
                     */
                    .groupBy {
                            move ->
                        move.take(4)
                    }
                    .values
                    .mapNotNull {
                            variants ->

                        if (variants.size == 1) {
                            variants.first()
                        } else {
                            /*
                             * Do not make source ambiguity look larger just
                             * because promotion has four legal piece choices.
                             * Use the queen variant as the representative path.
                             */
                            variants.firstOrNull {
                                    move ->
                                move.length >= 5 &&
                                        move[4].lowercaseChar() == 'q'
                            }
                        }
                    }


            if (candidates.isEmpty()) {

                Log.w(
                    LIVE_TAG,
                    "MISSED REPAIR: no legal move ends on " +
                            ChessBoardUtils.indexToSquareName(squareIndex)
                )

                manualMissedRepairActive =
                    false

                setMissedMoveUiState(
                    StopOverlayService.MISSED_FAILED
                )

                return
            }


            if (candidates.size == 1) {

                applyManualMissedMove(
                    candidates.first()
                )

                return
            }


            manualMissedDestination =
                squareIndex

            manualMissedCandidates =
                candidates


            setMissedMoveUiState(
                StopOverlayService.MISSED_TAP_FROM
            )


            Log.d(
                LIVE_TAG,
                "MISSED REPAIR: destination " +
                        ChessBoardUtils.indexToSquareName(squareIndex) +
                        " has ${candidates.size} legal sources; tap source."
            )

            return
        }


        /*
         * SECOND TAP = source.
         */
        val matching =
            manualMissedCandidates
                .filter {
                        move ->

                    move.length >= 4 &&
                            uciSquareToIndex(
                                move.substring(
                                    0,
                                    2
                                )
                            ) == squareIndex
                }


        if (matching.size == 1) {

            applyManualMissedMove(
                matching.first()
            )

            return
        }


        /*
         * Wrong source: remain in source-selection mode so another tap can be
         * tried without restarting the repair.
         */
        setMissedMoveUiState(
            StopOverlayService.MISSED_TAP_FROM
        )


        Log.w(
            LIVE_TAG,
            "MISSED REPAIR: source " +
                    ChessBoardUtils.indexToSquareName(squareIndex) +
                    " does not uniquely match destination " +
                    ChessBoardUtils.indexToSquareName(destination)
        )
    }


    private fun applyManualMissedMove(
        uciMove: String
    ) {

        if (
            !manualMissedRepairActive ||
            uciMove.length < 4
        ) {
            return
        }


        val fromIdx =
            uciSquareToIndex(
                uciMove.substring(
                    0,
                    2
                )
            )

        val toIdx =
            uciSquareToIndex(
                uciMove.substring(
                    2,
                    4
                )
            )


        if (
            fromIdx !in 0..63 ||
            toIdx !in 0..63
        ) {

            manualMissedRepairActive =
                false

            setMissedMoveUiState(
                StopOverlayService.MISSED_FAILED
            )

            return
        }


        /*
         * Final legality check against the stale-but-official virtual position.
         */
        val exactLegalMoves =
            virtualBoard
                .getAllLegalMovesUci()
                .filter {
                        move ->
                    move.length >= 4 &&
                            move.take(4) ==
                            uciMove.take(4)
                }


        if (exactLegalMoves.isEmpty()) {

            manualMissedRepairActive =
                false

            setMissedMoveUiState(
                StopOverlayService.MISSED_FAILED
            )

            return
        }


        val fenBefore =
            virtualBoard.generateFen()


        val updatedFen =
            if (
                uciMove.length >= 5 &&
                virtualBoard.isPromotionMove(
                    fromIdx,
                    toIdx
                )
            ) {

                virtualBoard.applyPromotionMove(
                    fromIdx,
                    toIdx,
                    uciMove[4]
                )

            } else {

                virtualBoard.applyMove(
                    fromIdx,
                    toIdx
                )
            }


        val fromName =
            ChessBoardUtils.indexToSquareName(
                fromIdx
            )

        val toName =
            ChessBoardUtils.indexToSquareName(
                toIdx
            )


        manualMissedRepairActive =
            false

        manualMissedDestination =
            null

        manualMissedCandidates =
            emptyList()


        /*
         * User-confirmed one-ply repair: next real board frame becomes the new
         * visual baseline while the just-updated FEN remains authoritative.
         */
        resumeNeedsFreshBaseline =
            true

        pendingStableFrame?.recycle()

        pendingStableFrame =
            null

        stableFrameCount =
            0

        clearPersistentResidues()

        unresolvedStablePasses =
            0

        lastAcceptedMoveTime =
            System.currentTimeMillis()


        Log.d(
            LIVE_TAG,
            "MISSED REPAIR: $fromName -> $toName"
        )

        Log.d(
            LIVE_TAG,
            "FEN: $updatedFen"
        )

        Log.d(
            "ChessAI_State",
            "Manual missed repair FEN before=$fenBefore | FEN after=$updatedFen"
        )


        requestStockfishAnalysis(
            fen =
                updatedFen
        )


        setMissedMoveUiState(
            StopOverlayService.MISSED_SUCCESS
        )
    }


    private fun uciSquareToIndex(
        square: String
    ): Int {

        if (square.length != 2) {
            return -1
        }

        val file =
            square[0].lowercaseChar() -
                    'a'

        val rank =
            square[1] -
                    '1'


        if (
            file !in 0..7 ||
            rank !in 0..7
        ) {
            return -1
        }


        val row =
            7 -
                    rank

        return row * 8 +
                file
    }


    private fun setMissedMoveUiState(
        state: String
    ) {

        try {

            startService(
                Intent(
                    this,
                    StopOverlayService::class.java
                ).apply {

                    action =
                        StopOverlayService.ACTION_SET_MISSED_STATE

                    putExtra(
                        StopOverlayService.EXTRA_MISSED_STATE,
                        state
                    )
                }
            )

        } catch (_: Exception) {
        }
    }


    /*
     * ============================================================
     * CONTROL OVERLAY STATE
     * ============================================================
     */

    private fun updateControlRecommendationVisibility() {

        try {

            startService(
                Intent(
                    this,
                    StopOverlayService::class.java
                ).apply {

                    action =
                        StopOverlayService
                            .ACTION_SET_RECOMMENDATIONS_VISIBLE

                    putExtra(
                        StopOverlayService
                            .EXTRA_RECOMMENDATIONS_VISIBLE,
                        recommendationsVisible
                    )
                }
            )

        } catch (_: Exception) {
        }
    }


    private fun updateControlPauseState() {

        try {

            startService(
                Intent(
                    this,
                    StopOverlayService::class.java
                ).apply {

                    action =
                        StopOverlayService.ACTION_SET_PAUSE_STATE

                    putExtra(
                        StopOverlayService.EXTRA_PAUSED,
                        scanningPaused
                    )
                }
            )

        } catch (_: Exception) {
        }
    }


    private fun setResyncUiState(
        state: String
    ) {

        try {

            startService(
                Intent(
                    this,
                    StopOverlayService::class.java
                ).apply {

                    action =
                        StopOverlayService
                            .ACTION_SET_RESYNC_STATE

                    putExtra(
                        StopOverlayService
                            .EXTRA_RESYNC_STATE,
                        state
                    )
                }
            )

        } catch (_: Exception) {
        }
    }


    /*
     * ============================================================
     * MANUAL HARD RESYNC
     * ============================================================
     *
     * This does NOT trust the current VirtualChessBoard piece placement.
     *
     * Flow:
     *
     * RESYNC button
     * -> pause normal move acceptance
     * -> hide recommendations
     * -> wait for stable board
     * -> recognize all 64 pieces ONCE
     * -> ask side-to-move + YES/NO/UNKNOWN castling
     * -> construct a fresh FEN
     * -> reset VirtualChessBoard from that FEN
     * -> make current screen the new official baseline
     * -> ask Stockfish for fresh best moves
     *
     * No automatic resync.
     * No multi-ply brute force.
     */


    private fun requestHardResync() {

        if (
            isShuttingDown ||
            hardResyncRequested ||
            isResyncing
        ) {
            return
        }


        isResyncing =
            true

        geminiResyncGeneration++

        hardResyncRequested =
            true

        hardResyncStableCount =
            0


        hardResyncStableFrame?.recycle()

        hardResyncStableFrame =
            null


        hardResyncCapturedBoard?.recycle()

        hardResyncCapturedBoard =
            null


        /*
         * Remove our own colored recommendation outlines before learning the
         * current board. The recognizer also ignores the perimeter, but hiding
         * them gives us the cleanest possible frame.
         */
        stockfishRequestGeneration++

        hideRecommendationOverlay()


        setResyncUiState(
            StopOverlayService.RESYNC_RUNNING
        )


        Log.d(
            LIVE_TAG,
            "HARD RESYNC STARTED: waiting for settled current board."
        )
    }


    /**
     * Called only by ScreenProcessingThread from detectChessboard().
     */
    private fun processHardResyncFrame(
        standardFrame: Bitmap
    ) {

        if (
            !hardResyncRequested ||
            isShuttingDown
        ) {
            return
        }


        if (
            hardResyncStableFrame ==
            null
        ) {

            hardResyncStableFrame =
                standardFrame.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

            hardResyncStableCount =
                1


            Log.d(
                "ChessAI_HardResync",
                "Waiting for stable board 1/$HARD_RESYNC_REQUIRED_STABLE_FRAMES"
            )

            return
        }


        val stable =
            ChessBoardUtils.areBitmapsSimilar(
                hardResyncStableFrame,
                standardFrame
            )


        if (!stable) {

            hardResyncStableFrame?.recycle()

            hardResyncStableFrame =
                standardFrame.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

            hardResyncStableCount =
                1


            Log.d(
                "ChessAI_HardResync",
                "Board still moving. Restart stable count."
            )

            return
        }


        hardResyncStableCount++


        Log.d(
            "ChessAI_HardResync",
            "Stable board $hardResyncStableCount/$HARD_RESYNC_REQUIRED_STABLE_FRAMES"
        )


        if (
            hardResyncStableCount <
            HARD_RESYNC_REQUIRED_STABLE_FRAMES
        ) {
            return
        }


        /*
         * Capture ONE private current-board bitmap.
         */
        hardResyncCapturedBoard?.recycle()

        hardResyncCapturedBoard =
            standardFrame.copy(
                Bitmap.Config.ARGB_8888,
                false
            )


        hardResyncStableFrame?.recycle()

        hardResyncStableFrame =
            null

        hardResyncStableCount =
            0


        val captured =
            hardResyncCapturedBoard
                ?: run {

                    cancelHardResync(
                        showFailure =
                            true
                    )

                    return
                }


        /*
         * ============================================================
         * GEMINI FULL-BOARD RECOGNITION
         * ============================================================
         *
         * Gemini receives only this normalized, settled chessboard crop.
         * It returns only the piece-placement portion of FEN.
         *
         * The old VirtualChessBoard does NOT influence recognition.
         */
        hardResyncRequested =
            false


        val requestGeneration =
            geminiResyncGeneration


        Log.d(
            "ChessAI_Gemini",
            "Stable board captured. Sending ONE RESYNC image to Gemini."
        )


        GeminiBoardRecognizer.recognize(
            boardBitmap =
                captured
        ) {
                result ->

            /*
             * Ignore a stale asynchronous result after STOP / NEW / CANCEL
             * or after a newer RESYNC has started.
             */
            if (
                requestGeneration !=
                geminiResyncGeneration ||
                isShuttingDown ||
                !isResyncing
            ) {

                Log.d(
                    "ChessAI_Gemini",
                    "Ignoring stale Gemini RESYNC result."
                )

                return@recognize
            }


            if (
                !result.success ||
                result.fenPlacement.isNullOrBlank()
            ) {

                Log.w(
                    LIVE_TAG,
                    "HARD RESYNC FAILED: Gemini board recognition failed. " +
                            "reason=${result.error ?: "unknown"}"
                )


                cancelHardResync(
                    showFailure =
                        true
                )

                return@recognize
            }


            val placement =
                result.fenPlacement


            Log.d(
                LIVE_TAG,
                "HARD RESYNC GEMINI PLACEMENT: $placement"
            )


            /*
             * Gemini's job ends here.
             *
             * The existing wizard remains authoritative for:
             * - side to move
             * - castling rights
             */
            try {

                startService(
                    Intent(
                        this,
                        OverlayPromptService::class.java
                    ).apply {

                        action =
                            OverlayPromptService.ACTION_SHOW_HARD_RESYNC

                        putExtra(
                            OverlayPromptService.EXTRA_RESYNC_PLACEMENT,
                            placement
                        )
                    }
                )


                Log.d(
                    "ChessAI_Gemini",
                    "Gemini recognition accepted. Opening metadata wizard."
                )

            } catch (e: Exception) {

                Log.e(
                    LIVE_TAG,
                    "HARD RESYNC failed to show metadata wizard.",
                    e
                )


                cancelHardResync(
                    showFailure =
                        true
                )
            }
        }
    }


    private fun applyHardResyncFen(
        fen: String
    ) {

        val processingHandler =
            handler


        if (
            processingHandler ==
            null ||
            isShuttingDown
        ) {

            cancelHardResync(
                showFailure =
                    true
            )

            return
        }


        processingHandler.post {

            if (isShuttingDown) {
                return@post
            }


            val captured =
                hardResyncCapturedBoard


            if (
                captured ==
                null ||
                captured.isRecycled
            ) {

                Log.w(
                    LIVE_TAG,
                    "HARD RESYNC APPLY FAILED: captured board is unavailable."
                )

                cancelHardResync(
                    showFailure =
                        true
                )

                return@post
            }


            val applied =
                virtualBoard.resetFromFen(
                    fen =
                        fen,
                    userIsWhite =
                        ScreenRecordService.selectedUserIsWhite
                )


            if (!applied) {

                Log.w(
                    LIVE_TAG,
                    "HARD RESYNC APPLY FAILED: invalid FEN=$fen"
                )

                cancelHardResync(
                    showFailure =
                        true
                )

                return@post
            }


            /*
             * HARD RESYNC now becomes the authoritative current setup.
             * This prevents the fresh-game prompt from appearing on the next frame.
             */
            hasPromptedThisSession =
                true

            isBoardSetupConfirmed =
                true


            previousBoardBitmap?.recycle()

            previousBoardBitmap =
                captured.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )


            pendingStableFrame?.recycle()

            pendingStableFrame =
                null

            stableFrameCount =
                0


            clearPersistentResidues()

            unresolvedStablePasses =
                0

            lastAcceptedMoveTime =
                System.currentTimeMillis()


            stockfishRequestGeneration++

            /*
             * The newly reconstructed FEN is now authoritative again.
             */
            resyncFailureFrozen =
                false


            Log.d(
                LIVE_TAG,
                "HARD RESYNC FEN: $fen"
            )


            requestStockfishAnalysis(
                fen
            )


            hardResyncCapturedBoard?.recycle()

            hardResyncCapturedBoard =
                null


            isResyncing =
                false

            hardResyncRequested =
                false


            setResyncUiState(
                StopOverlayService.RESYNC_SUCCESS
            )
        }
    }


    private fun cancelHardResync(
        showFailure: Boolean
    ) {

        /*
         * Serialize cleanup with the scanner if possible.
         */
        val cleanup = {

            geminiResyncGeneration++

            hardResyncRequested =
                false

            isResyncing =
                false


            hardResyncStableFrame?.recycle()

            hardResyncStableFrame =
                null

            hardResyncStableCount =
                0


            hardResyncCapturedBoard?.recycle()

            hardResyncCapturedBoard =
                null


            if (showFailure) {

                /*
                 * CRITICAL:
                 * A recognition/apply failure means the old virtual FEN is no
                 * longer trusted. Preserve it only as historical state; do not
                 * use it for another move or another Stockfish request.
                 */
                resyncFailureFrozen =
                    true

                stockfishRequestGeneration++

                latestRecommendationSide =
                    null

                latestRecommendationMoves =
                    emptyList()

                hideRecommendationOverlay()


                setResyncUiState(
                    StopOverlayService.RESYNC_FAILED
                )


                Log.w(
                    LIVE_TAG,
                    "TRACKING FROZEN: RESYNC failed. " +
                            "Press RESYNC again to recover."
                )

            } else {

                /*
                 * Explicit user CANCEL is deliberate. Return to the previous
                 * virtual board rather than remaining frozen.
                 */
                resyncFailureFrozen =
                    false


                setResyncUiState(
                    StopOverlayService.RESYNC_IDLE
                )


                if (
                    recommendationsVisible &&
                    latestRecommendationSide !=
                    null &&
                    latestRecommendationMoves.isNotEmpty()
                ) {

                    updateRecommendationOverlay(
                        sideToMove =
                            latestRecommendationSide!!,
                        moves =
                            latestRecommendationMoves
                    )
                }
            }
        }


        val processingHandler =
            handler


        if (
            processingHandler !=
            null &&
            android.os.Looper.myLooper() !=
            processingHandler.looper
        ) {

            processingHandler.post {
                cleanup.invoke()
            }

        } else {

            cleanup.invoke()
        }
    }


    /**
     * Stockfish safety filter.
     *
     * After HARD RESYNC, only castling rights explicitly answered YES are
     * included in VirtualChessBoard/FEN. UNKNOWN therefore becomes false.
     *
     * This additional filter is defensive so a castle is never displayed when
     * VirtualChessBoard says the corresponding right is unavailable.
     */
    private fun isDisallowedCastleMove(
        uci: String
    ): Boolean {

        return when (uci.lowercase()) {

            "e1g1" ->
                !virtualBoard.canCastle(
                    white =
                        true,
                    kingside =
                        true
                )

            "e1c1" ->
                !virtualBoard.canCastle(
                    white =
                        true,
                    kingside =
                        false
                )

            "e8g8" ->
                !virtualBoard.canCastle(
                    white =
                        false,
                    kingside =
                        true
                )

            "e8c8" ->
                !virtualBoard.canCastle(
                    white =
                        false,
                    kingside =
                        false
                )

            else ->
                false
        }
    }


    /*
     * ============================================================
     * PROMOTION FAST-PATH ELIGIBILITY
     * ============================================================
     */

    /**
     * Returns true when the CURRENT side to move has at least one pawn with
     * a pseudo-legal move onto the promotion rank.
     *
     * This helper intentionally does not decide which move occurred and does
     * not modify the board/FEN. It only chooses whether the stability filter
     * may analyze after 2 matching frames instead of 3.
     *
     * White promotion sources are rank 7 (internal row 1).
     * Black promotion sources are rank 2 (internal row 6).
     *
     * We require one of:
     * - forward promotion square is empty
     * - diagonal promotion square contains an enemy piece
     *
     * Full king-safety legality remains the responsibility of the normal move
     * detector, exactly as before.
     */
    private fun hasPromotionMoveAvailableForActiveSide(): Boolean {

        val activeColor =
            virtualBoard.getActiveColor()

        val whiteToMove =
            activeColor == 'w'

        val expectedPawn =
            if (whiteToMove) {
                'P'
            } else {
                'p'
            }

        val sourceRow =
            if (whiteToMove) {
                1
            } else {
                6
            }

        val targetRow =
            if (whiteToMove) {
                0
            } else {
                7
            }

        for (col in 0 until 8) {

            val fromIndex =
                sourceRow * 8 + col

            if (
                virtualBoard.getPieceAt(fromIndex) !=
                expectedPawn
            ) {
                continue
            }

            /*
             * Straight promotion.
             */
            val forwardIndex =
                targetRow * 8 + col

            if (
                virtualBoard.getPieceAt(forwardIndex) ==
                null
            ) {
                return true
            }

            /*
             * Capture promotion to the left/right.
             */
            for (deltaCol in intArrayOf(-1, 1)) {

                val targetCol =
                    col + deltaCol

                if (targetCol !in 0..7) {
                    continue
                }

                val targetIndex =
                    targetRow * 8 + targetCol

                val targetPiece =
                    virtualBoard.getPieceAt(targetIndex)
                        ?: continue

                val targetIsWhite =
                    targetPiece.isUpperCase()

                if (
                    targetIsWhite !=
                    whiteToMove
                ) {
                    return true
                }
            }
        }

        return false
    }


    /*
     * ============================================================
     * PERSISTENT VISUAL RESIDUE TRACKING
     * ============================================================
     *
     * This NEVER changes previousBoardBitmap.
     */
    private fun updatePersistentResidues(
        changedIndices: Set<Int>,
        explainedSquares: Set<Int>
    ) {

        for (index in 0 until 64) {

            if (index in explainedSquares) {

                residueObservationCounts[index] = 0

                if (
                    persistentResidueSquares.remove(index)
                ) {
                    Log.d(
                        "ChessAI_Residue",
                        "RESIDUE_CLEARED reason=EXPLAINED_MOVE " +
                                ChessBoardUtils.indexToSquareName(index)
                    )
                }

                continue
            }

            if (index in changedIndices) {

                residueObservationCounts[index] =
                    minOf(
                        residueObservationCounts[index] + 1,
                        RESIDUE_CONFIRMATION_SCANS
                    )

                if (
                    residueObservationCounts[index] >=
                    RESIDUE_CONFIRMATION_SCANS &&
                    persistentResidueSquares.add(index)
                ) {
                    Log.d(
                        "ChessAI_Residue",
                        "RESIDUE_MARKED " +
                                ChessBoardUtils.indexToSquareName(index) +
                                " after=" +
                                residueObservationCounts[index] +
                                " stable analyses"
                    )
                }

            } else {

                residueObservationCounts[index] = 0

                if (
                    persistentResidueSquares.remove(index)
                ) {
                    Log.d(
                        "ChessAI_Residue",
                        "RESIDUE_CLEARED reason=DISAPPEARED " +
                                ChessBoardUtils.indexToSquareName(index)
                    )
                }
            }
        }
    }


    private fun clearPersistentResidues() {
        residueObservationCounts.fill(0)
        persistentResidueSquares.clear()
    }


    /*
     * ============================================================
     * ACCEPTED-MOVE BASELINE PATCHING
     * ============================================================
     */


    /**
     * Returns only the board squares that are legitimately modified by the
     * accepted chess move.
     *
     * Normal move / capture / promotion:
     *     source + destination
     *
     * Castling:
     *     king source + king destination + rook source + rook destination
     *
     * NOTE:
     * En-passant visual support is not currently part of ChessBoardUtils'
     * legal candidate generation. If en-passant detection is added later,
     * add the captured pawn square here as well.
     */
    private fun getBaselineSquaresForAcceptedMove(
        fromIdx: Int,
        toIdx: Int,
        movedPieceBefore: Char?,
        destinationPieceBefore: Char?
    ): Set<Int> {

        val affected =
            linkedSetOf(
                fromIdx,
                toIdx
            )

        if (movedPieceBefore == null) {
            return affected
        }

        val fromRow =
            fromIdx / 8

        val fromCol =
            fromIdx % 8

        val toRow =
            toIdx / 8

        val toCol =
            toIdx % 8

        val isKing =
            movedPieceBefore == 'K' ||
                    movedPieceBefore == 'k'

        /*
         * Castling changes four physical squares.
         */
        if (
            isKing &&
            fromRow == toRow &&
            kotlin.math.abs(
                fromCol - toCol
            ) == 2
        ) {

            if (toCol > fromCol) {

                // Kingside castle.
                val rookFrom =
                    fromRow * 8 + 7

                val rookTo =
                    fromRow * 8 + 5

                affected.add(
                    rookFrom
                )

                affected.add(
                    rookTo
                )

            } else {

                // Queenside castle.
                val rookFrom =
                    fromRow * 8

                val rookTo =
                    fromRow * 8 + 3

                affected.add(
                    rookFrom
                )

                affected.add(
                    rookTo
                )
            }
        }

        /*
         * destinationPieceBefore is intentionally retained in the signature.
         *
         * It gives us the PRE-MOVE occupancy information needed when we add
         * explicit en-passant baseline patching later.
         */
        @Suppress("UNUSED_VARIABLE")
        val destinationBefore =
            destinationPieceBefore

        return affected
    }


    /**
     * Creates the next official baseline by copying the old baseline and
     * replacing ONLY the squares explained by the accepted chess move.
     *
     * This is the key protection against unrelated selection/animation
     * changes contaminating the official reference image.
     */
    private fun patchAcceptedMoveIntoBaseline(
        oldBaseline: Bitmap,
        currentFrame: Bitmap,
        squareIndices: Set<Int>
    ): Bitmap {

        /*
         * Keep the exact official-baseline dimensions.
         *
         * normalizeBoardOrientation() normally makes these dimensions equal,
         * but scale defensively instead of falling back to a full-frame copy.
         */
        val alignedCurrent =
            if (
                currentFrame.width ==
                oldBaseline.width &&
                currentFrame.height ==
                oldBaseline.height
            ) {

                currentFrame

            } else {

                Log.w(
                    "ChessAI_Baseline",
                    "Baseline/current size mismatch. " +
                            "old=${oldBaseline.width}x${oldBaseline.height}, " +
                            "current=${currentFrame.width}x${currentFrame.height}. " +
                            "Scaling current frame before square patching."
                )

                Bitmap.createScaledBitmap(
                    currentFrame,
                    oldBaseline.width,
                    oldBaseline.height,
                    true
                )
            }


        val patched =
            oldBaseline.copy(
                Bitmap.Config.ARGB_8888,
                true
            )


        val canvas =
            Canvas(
                patched
            )


        /*
         * Use proportional boundaries instead of width/8 integer stepping so
         * every pixel remains covered even if a board dimension is not exactly
         * divisible by 8.
         */
        for (index in squareIndices) {

            if (index !in 0..63) {
                continue
            }

            val row =
                index / 8

            val col =
                index % 8

            val left =
                col *
                        patched.width /
                        8

            val right =
                (col + 1) *
                        patched.width /
                        8

            val top =
                row *
                        patched.height /
                        8

            val bottom =
                (row + 1) *
                        patched.height /
                        8


            val src =
                Rect(
                    left,
                    top,
                    right,
                    bottom
                )


            val dst =
                Rect(
                    left,
                    top,
                    right,
                    bottom
                )


            canvas.drawBitmap(
                alignedCurrent,
                src,
                dst,
                null
            )
        }


        if (
            alignedCurrent !==
            currentFrame
        ) {
            alignedCurrent.recycle()
        }


        return patched
    }


    /*
     * ============================================================
     * OPENCV CLEANUP
     * ============================================================
     */

    private fun releaseOpenCv(
        mat: Mat?,
        grayMat: Mat?,
        blurred: Mat?,
        thresh: Mat?,
        hierarchy: Mat?
    ) {

        try {
            mat?.release()
        } catch (_: Exception) {
        }

        try {
            grayMat?.release()
        } catch (_: Exception) {
        }

        try {
            blurred?.release()
        } catch (_: Exception) {
        }

        try {
            thresh?.release()
        } catch (_: Exception) {
        }

        try {
            hierarchy?.release()
        } catch (_: Exception) {
        }
    }


    /*
     * ============================================================
     * INITIAL BOARD PROMPT
     * ============================================================
     */

    private fun checkIfDuplicateAndSave(
        newBoardBitmap: Bitmap,
        squares: ArrayList<Bitmap>,
        currentSignature: String
    ) {

        hasPromptedThisSession = true


        pendingBoardBitmap =
            newBoardBitmap.copy(
                Bitmap.Config.ARGB_8888,
                false
            )


        pendingSquareBitmaps =
            squares


        val intent =
            Intent(
                this,
                OverlayPromptService::class.java
            )


        startService(intent)
    }


    /*
     * ============================================================
     * STOP SCREEN RECORDING
     * ============================================================
     */

    /*
     * ============================================================
     * FLOATING STOP OVERLAY
     * ============================================================
     */

    private fun showStopOverlay() {

        try {

            startService(
                Intent(
                    this,
                    StopOverlayService::class.java
                ).apply {
                    action =
                        StopOverlayService.ACTION_SHOW
                }
            )

        } catch (e: Exception) {

            Log.e(
                "ChessAI",
                "Failed to show floating stop button.",
                e
            )
        }
    }


    private fun updateStopTouchGuard(
        boardX: Int,
        boardY: Int,
        boardWidth: Int,
        boardHeight: Int
    ) {

        if (
            boardWidth <= 0 ||
            boardHeight <= 0
        ) {
            return
        }

        try {

            startService(
                Intent(
                    this,
                    StopOverlayService::class.java
                ).apply {

                    action =
                        StopOverlayService.ACTION_UPDATE_BOARD

                    putExtra(
                        StopOverlayService.EXTRA_BOARD_X,
                        boardX
                    )

                    putExtra(
                        StopOverlayService.EXTRA_BOARD_Y,
                        boardY
                    )

                    putExtra(
                        StopOverlayService.EXTRA_BOARD_WIDTH,
                        boardWidth
                    )

                    putExtra(
                        StopOverlayService.EXTRA_BOARD_HEIGHT,
                        boardHeight
                    )
                }
            )

        } catch (e: Exception) {

            Log.e(
                "ChessAI",
                "Failed to update touch guard.",
                e
            )
        }
    }


    private fun hideStopOverlay() {

        try {

            startService(
                Intent(
                    this,
                    StopOverlayService::class.java
                ).apply {
                    action =
                        StopOverlayService.ACTION_HIDE
                }
            )

        } catch (_: Exception) {
        }
    }


    private fun stopScreenRecording() {

        watchdogStarted =
            false

        watchdogHandler?.removeCallbacks(
            watchdogRunnable
        )


        /*
         * Prevent a queued/manual/automatic RESYNC from committing while the
         * capture service is being torn down.
         */
        isShuttingDown = true
        isResyncing = false

        geminiResyncGeneration++

        handlerThread?.quitSafely()

        virtualDisplay?.release()

        imageReader?.close()

        mediaProjection?.stop()


        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        handlerThread = null
        handler = null


        previousBoardBitmap?.recycle()
        previousBoardBitmap = null


        pendingStableFrame?.recycle()
        pendingStableFrame = null


        stableFrameCount = 0

        lastAcceptedMoveTime = 0L

        clearPersistentResidues()

        pendingPromotionMove = null
        pendingPromotionUserChoice = null
        PromotionRecognizer.clear()

        stockfishRequestGeneration++

        try {
            stockfishEngine?.close()
        } catch (_: Exception) {
        }

        stockfishEngine = null

        hideRecommendationOverlay()
        hideStopOverlay()

        recommendationsVisible = true
        scanningPaused = false
        resumeNeedsFreshBaseline = false
        manualMissedRepairActive = false
        manualMissedDestination = null
        manualMissedCandidates = emptyList()
        latestRecommendationSide = null
        latestRecommendationMoves = emptyList()

        hardResyncRequested = false

        hardResyncStableFrame?.recycle()
        hardResyncStableFrame = null
        hardResyncStableCount = 0

        hardResyncCapturedBoard?.recycle()
        hardResyncCapturedBoard = null

        isResyncing = false
        unresolvedStablePasses = 0


        savedBoardHistory.clear()


        Log.d(
            "ChessAI",
            "Screen recording stopped and scanning state cleared."
        )
    }


    /*
     * ============================================================
     * NOTIFICATION CHANNEL
     * ============================================================
     */

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val serviceChannel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen Recording Service Channel",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {

                    description =
                        "Active screen recording indicator"
                }


            val manager =
                getSystemService(
                    NotificationManager::class.java
                )


            manager?.createNotificationChannel(
                serviceChannel
            )
        }
    }


    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}