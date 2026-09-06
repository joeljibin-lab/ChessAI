package com.example.chessai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors


object GeminiBoardRecognizer {

    private const val TAG = "ChessAI_Gemini"

    /*
     * 512x512 keeps each chess square at about 64x64 pixels.
     * This is a conservative reduction intended to lower upload/vision latency
     * without aggressively reducing piece detail.
     */
    private const val GEMINI_BOARD_SIZE = 512
    private const val LABEL_MARGIN = 64
    private const val LABELED_IMAGE_SIZE =
        GEMINI_BOARD_SIZE + LABEL_MARGIN * 2
    private const val JPEG_QUALITY = 90
    private const val MAX_RECOGNITION_ATTEMPTS = 2

    /*
     * We intentionally keep Gemini OUTSIDE the normal move-detection loop.
     *
     * Gemini is called ONLY when the user manually presses RESYNC.
     */
    private val executor =
        Executors.newSingleThreadExecutor()


    data class RecognitionResult(
        val success: Boolean,
        val fenPlacement: String? = null,
        val uncertainSquares: List<String> = emptyList(),
        val error: String? = null
    )


    /**
     * Analyze ONE normalized chessboard image.
     *
     * IMPORTANT:
     *
     * The bitmap must already be in STANDARD orientation:
     *
     *      a8 ... h8
     *       .     .
     *       .     .
     *      a1 ... h1
     *
     * This function does NOT determine:
     *
     * - whose turn it is
     * - castling rights
     * - en-passant
     * - halfmove clock
     * - fullmove number
     *
     * It recognizes PIECE PLACEMENT ONLY.
     */
    fun recognize(
        boardBitmap: Bitmap,
        attemptNumber: Int = 1,
        callback: (RecognitionResult) -> Unit
    ) {

        /*
         * Copy the bitmap before leaving the caller thread.
         *
         * ScreenRecordService aggressively recycles temporary board bitmaps.
         * Gemini must therefore own its own copy.
         */
        val bitmapCopy =
            try {

                boardBitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

            } catch (e: Exception) {

                callback(
                    RecognitionResult(
                        success = false,
                        error = "Could not copy board image."
                    )
                )

                return
            }


        executor.execute {

            try {

                Log.d(
                    TAG,
                    "Starting Gemini RESYNC recognition " +
                            "attempt $attemptNumber/$MAX_RECOGNITION_ATTEMPTS."
                )


                val apiKey =
                    BuildConfig.GEMINI_API_KEY


                if (apiKey.isBlank()) {

                    Log.e(
                        TAG,
                        "GEMINI_API_KEY is empty."
                    )

                    callback(
                        RecognitionResult(
                            success = false,
                            error = "Gemini API key is missing."
                        )
                    )

                    return@execute
                }


                /*
                 * ---------------------------------------------------------
                 * CONSERVATIVE RESIZE + VISUAL COORDINATE LABELS
                 * ---------------------------------------------------------
                 *
                 * IMPORTANT:
                 * ScreenRecordService has ALREADY normalized the board so:
                 *
                 *   top-left     = a8
                 *   top-right    = h8
                 *   bottom-left  = a1
                 *   bottom-right = h1
                 *
                 * Gemini occasionally ignored that fact when it existed only
                 * in the text prompt. We therefore draw file/rank labels
                 * AROUND the board itself.
                 *
                 * The chessboard remains 512x512, so recognition detail is
                 * not reduced. The final image is larger only because of the
                 * white coordinate border.
                 */
                val geminiBoard =
                    if (
                        bitmapCopy.width == GEMINI_BOARD_SIZE &&
                        bitmapCopy.height == GEMINI_BOARD_SIZE
                    ) {

                        bitmapCopy

                    } else {

                        Bitmap.createScaledBitmap(
                            bitmapCopy,
                            GEMINI_BOARD_SIZE,
                            GEMINI_BOARD_SIZE,
                            true
                        )
                    }


                val labeledBitmap =
                    createCoordinateLabeledBoard(
                        geminiBoard
                    )


                /*
                 * geminiBoard may be the same object as bitmapCopy.
                 * Only recycle an extra scaled copy here.
                 */
                if (
                    geminiBoard !== bitmapCopy &&
                    !geminiBoard.isRecycled
                ) {

                    geminiBoard.recycle()
                }


                val outputStream =
                    ByteArrayOutputStream()


                val compressed =
                    labeledBitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        JPEG_QUALITY,
                        outputStream
                    )


                if (
                    !labeledBitmap.isRecycled
                ) {

                    labeledBitmap.recycle()
                }


                if (!compressed) {

                    callback(
                        RecognitionResult(
                            success = false,
                            error = "Could not compress board image."
                        )
                    )

                    return@execute
                }


                val imageBytes =
                    outputStream.toByteArray()


                Log.d(
                    TAG,
                    "Gemini labeled board image=" +
                            "${LABELED_IMAGE_SIZE}x${LABELED_IMAGE_SIZE}, " +
                            "board=${GEMINI_BOARD_SIZE}x${GEMINI_BOARD_SIZE}, " +
                            "jpegQuality=$JPEG_QUALITY, bytes=${imageBytes.size}"
                )


                /*
                 * Safety limit.
                 */
                if (imageBytes.size > 2_000_000) {

                    callback(
                        RecognitionResult(
                            success = false,
                            error = "Board image is unexpectedly large."
                        )
                    )

                    return@execute
                }


                val base64Image =
                    Base64.encodeToString(
                        imageBytes,
                        Base64.NO_WRAP
                    )


                /*
                 * ---------------------------------------------------------
                 * STRICT CHESSBOARD PROMPT
                 * ---------------------------------------------------------
                 *
                 * Gemini is deliberately NOT allowed to reconstruct history.
                 *
                 * It only reports what is visibly on the board.
                 */
                val prompt =
                    """
You are a visual chessboard transcription system.

You are given ONE image containing an 8x8 chessboard WITH VISIBLE
coordinate labels around its border.

THE VISIBLE COORDINATE LABELS ARE AUTHORITATIVE.

The board has already been normalized before you receive it:

top-left     = a8
top-right    = h8
bottom-left  = a1
bottom-right = h1

Read the visible file letters a-h and rank numbers 8-1 around the image.
DO NOT rotate, mirror, flip, or reinterpret the board orientation.

Your ONLY task is to identify the visible contents of all 64 squares.

DO NOT infer move history.
DO NOT infer whose turn it is.
DO NOT infer castling rights.
DO NOT infer en-passant.
DO NOT infer legal moves.
DO NOT repair the position.
DO NOT assume pieces from a normal starting position.
DO NOT use chess strategy to guess missing pieces.

Read ONLY what is visibly present in the supplied image.

Use normal FEN piece symbols:

White:
P = pawn
N = knight
B = bishop
R = rook
Q = queen
K = king

Black:
p = pawn
n = knight
b = bishop
r = rook
q = queen
k = king

Return the PIECE-PLACEMENT portion of FEN.

Example starting position:

rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR

If ANY square is visually uncertain, include that square in
uncertainSquares.

Do NOT guess an uncertain square.

fenPlacement must contain ONLY the 8 FEN ranks.

Use ONLY these FEN piece characters:
P N B R Q K p n b r q k
and digits 1 through 8 for empty runs.

Never invent another letter.
                    """.trimIndent() +
                            if (attemptNumber > 1) {
                                """

THIS IS AN AUTOMATIC RETRY because the previous transcription was rejected.
Slow down and re-read the VISIBLE coordinate labels.
Do not rotate the board.
Return a fresh transcription from the image only.
                                """.trimIndent()
                            } else {
                                ""
                            }


                /*
                 * ---------------------------------------------------------
                 * RESPONSE SCHEMA
                 * ---------------------------------------------------------
                 *
                 * Force Gemini to give us machine-readable JSON.
                 */
                val responseSchema =
                    JSONObject().apply {

                        put(
                            "type",
                            "object"
                        )

                        put(
                            "properties",
                            JSONObject().apply {

                                put(
                                    "fenPlacement",
                                    JSONObject().apply {

                                        put(
                                            "type",
                                            "string"
                                        )

                                        put(
                                            "description",
                                            "The 8-rank FEN piece-placement field only."
                                        )
                                    }
                                )


                                put(
                                    "uncertainSquares",
                                    JSONObject().apply {

                                        put(
                                            "type",
                                            "array"
                                        )

                                        put(
                                            "items",
                                            JSONObject().apply {

                                                put(
                                                    "type",
                                                    "string"
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )


                        put(
                            "required",
                            JSONArray().apply {

                                put(
                                    "fenPlacement"
                                )

                                put(
                                    "uncertainSquares"
                                )
                            }
                        )
                    }


                /*
                 * ---------------------------------------------------------
                 * GEMINI REQUEST BODY
                 * ---------------------------------------------------------
                 */
                val requestBody =
                    JSONObject().apply {

                        put(
                            "contents",
                            JSONArray().apply {

                                put(
                                    JSONObject().apply {

                                        put(
                                            "role",
                                            "user"
                                        )

                                        put(
                                            "parts",
                                            JSONArray().apply {

                                                /*
                                                 * Send image FIRST.
                                                 */
                                                put(
                                                    JSONObject().apply {

                                                        put(
                                                            "inline_data",
                                                            JSONObject().apply {

                                                                put(
                                                                    "mime_type",
                                                                    "image/jpeg"
                                                                )

                                                                put(
                                                                    "data",
                                                                    base64Image
                                                                )
                                                            }
                                                        )
                                                    }
                                                )


                                                put(
                                                    JSONObject().apply {

                                                        put(
                                                            "text",
                                                            prompt
                                                        )
                                                    }
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )


                        /*
                         * Gemini structured output.
                         */
                        put(
                            "generationConfig",
                            JSONObject().apply {

                                put(
                                    "temperature",
                                    0
                                )

                                /*
                                 * Board transcription is a visual extraction
                                 * task, not a deep chess-reasoning task.
                                 * Keep reasoning minimal to reduce latency.
                                 */
                                put(
                                    "thinkingConfig",
                                    JSONObject().apply {

                                        put(
                                            "thinkingLevel",
                                            "minimal"
                                        )
                                    }
                                )

                                put(
                                    "responseMimeType",
                                    "application/json"
                                )

                                put(
                                    "responseSchema",
                                    responseSchema
                                )
                            }
                        )
                    }


                /*
                 * ---------------------------------------------------------
                 * HTTP REQUEST
                 * ---------------------------------------------------------
                 *
                 * IMPORTANT:
                 *
                 * The API key goes in the HTTP header.
                 * It is NOT placed in the URL.
                 */
                val url =
                    URL(
                        "https://generativelanguage.googleapis.com/" +
                                "v1beta/models/gemini-3.6-flash:generateContent"
                    )


                val connection =
                    url.openConnection()
                            as HttpURLConnection


                try {

                    connection.requestMethod =
                        "POST"

                    connection.connectTimeout =
                        15_000

                    connection.readTimeout =
                        30_000

                    connection.doOutput =
                        true

                    connection.setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    connection.setRequestProperty(
                        "x-goog-api-key",
                        apiKey
                    )


                    connection.outputStream.use {
                            stream ->

                        stream.write(
                            requestBody
                                .toString()
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        )
                    }


                    val responseCode =
                        connection.responseCode


                    Log.d(
                        TAG,
                        "Gemini HTTP response=$responseCode"
                    )


                    /*
                     * -----------------------------------------------------
                     * QUOTA EXHAUSTED
                     * -----------------------------------------------------
                     */
                    if (responseCode == 429) {

                        callback(
                            RecognitionResult(
                                success = false,
                                error =
                                    "Gemini free-tier quota reached."
                            )
                        )

                        return@execute
                    }


                    /*
                     * Authentication/key problem.
                     */
                    if (
                        responseCode == 401 ||
                        responseCode == 403
                    ) {

                        callback(
                            RecognitionResult(
                                success = false,
                                error =
                                    "Gemini API key was rejected."
                            )
                        )

                        return@execute
                    }


                    /*
                     * Other HTTP failure.
                     */
                    if (
                        responseCode !in
                        200..299
                    ) {

                        val errorText =
                            connection
                                .errorStream
                                ?.bufferedReader()
                                ?.use {
                                    it.readText()
                                }
                                ?: "Unknown Gemini error"


                        Log.e(
                            TAG,
                            "Gemini HTTP error: $errorText"
                        )


                        callback(
                            RecognitionResult(
                                success = false,
                                error =
                                    "Gemini request failed ($responseCode)."
                            )
                        )

                        return@execute
                    }


                    val responseText =
                        connection
                            .inputStream
                            .bufferedReader()
                            .use {
                                it.readText()
                            }


                    Log.d(
                        TAG,
                        "Gemini response received."
                    )


                    /*
                     * -----------------------------------------------------
                     * EXTRACT MODEL JSON
                     * -----------------------------------------------------
                     *
                     * Outer JSON:
                     *
                     * candidates[]
                     *   -> content
                     *      -> parts[]
                     *         -> text
                     *
                     * The text itself is our structured JSON result.
                     */
                    val outerJson =
                        JSONObject(
                            responseText
                        )


                    val candidates =
                        outerJson.optJSONArray(
                            "candidates"
                        )


                    if (
                        candidates == null ||
                        candidates.length() == 0
                    ) {

                        callback(
                            RecognitionResult(
                                success = false,
                                error =
                                    "Gemini returned no board result."
                            )
                        )

                        return@execute
                    }


                    val firstCandidate =
                        candidates.getJSONObject(
                            0
                        )


                    val content =
                        firstCandidate.getJSONObject(
                            "content"
                        )


                    val parts =
                        content.getJSONArray(
                            "parts"
                        )


                    var modelText:
                            String? =
                        null


                    for (
                    i in
                    0 until parts.length()
                    ) {

                        val part =
                            parts.getJSONObject(i)


                        if (
                            part.has("text")
                        ) {

                            modelText =
                                part.getString(
                                    "text"
                                )

                            break
                        }
                    }


                    if (
                        modelText.isNullOrBlank()
                    ) {

                        callback(
                            RecognitionResult(
                                success = false,
                                error =
                                    "Gemini returned an empty result."
                            )
                        )

                        return@execute
                    }


                    Log.d(
                        TAG,
                        "Gemini structured result=$modelText"
                    )


                    val resultJson =
                        JSONObject(
                            modelText
                        )


                    val fenPlacement =
                        resultJson
                            .optString(
                                "fenPlacement",
                                ""
                            )
                            .trim()


                    val uncertainJson =
                        resultJson
                            .optJSONArray(
                                "uncertainSquares"
                            )


                    val uncertainSquares =
                        mutableListOf<String>()


                    if (
                        uncertainJson != null
                    ) {

                        for (
                        i in
                        0 until uncertainJson.length()
                        ) {

                            val square =
                                uncertainJson
                                    .optString(i)
                                    .trim()
                                    .lowercase()


                            if (
                                square.matches(
                                    Regex(
                                        "^[a-h][1-8]$"
                                    )
                                )
                            ) {

                                uncertainSquares.add(
                                    square
                                )
                            }
                        }
                    }


                    /*
                     * -----------------------------------------------------
                     * NEVER ACCEPT AN UNCERTAIN POSITION
                     * -----------------------------------------------------
                     */
                    if (
                        uncertainSquares.isNotEmpty()
                    ) {

                        Log.w(
                            TAG,
                            "Gemini uncertain squares=" +
                                    uncertainSquares
                                        .joinToString()
                        )


                        if (
                            attemptNumber <
                            MAX_RECOGNITION_ATTEMPTS
                        ) {

                            Log.w(
                                TAG,
                                "Retrying Gemini RESYNC once because the " +
                                        "first result contained uncertain squares."
                            )

                            recognize(
                                boardBitmap = bitmapCopy,
                                attemptNumber =
                                    attemptNumber + 1,
                                callback = callback
                            )

                            return@execute
                        }


                        callback(
                            RecognitionResult(
                                success = false,
                                fenPlacement =
                                    fenPlacement,
                                uncertainSquares =
                                    uncertainSquares,
                                error =
                                    "Gemini is uncertain about " +
                                            uncertainSquares
                                                .joinToString()
                            )
                        )

                        return@execute
                    }


                    /*
                     * -----------------------------------------------------
                     * LOCAL VALIDATION
                     * -----------------------------------------------------
                     *
                     * Never trust AI output without checking it ourselves.
                     */
                    val validationError =
                        validateFenPlacement(
                            fenPlacement
                        )


                    if (
                        validationError != null
                    ) {

                        Log.e(
                            TAG,
                            "Rejected Gemini FEN: " +
                                    validationError +
                                    " | " +
                                    fenPlacement
                        )


                        if (
                            attemptNumber <
                            MAX_RECOGNITION_ATTEMPTS
                        ) {

                            Log.w(
                                TAG,
                                "Retrying Gemini RESYNC once after invalid FEN."
                            )

                            recognize(
                                boardBitmap = bitmapCopy,
                                attemptNumber =
                                    attemptNumber + 1,
                                callback = callback
                            )

                            return@execute
                        }


                        callback(
                            RecognitionResult(
                                success = false,
                                fenPlacement =
                                    fenPlacement,
                                error =
                                    validationError
                            )
                        )

                        return@execute
                    }


                    /*
                     * SUCCESS.
                     */
                    Log.d(
                        TAG,
                        "GEMINI RESYNC SUCCESS | " +
                                "placement=$fenPlacement"
                    )


                    callback(
                        RecognitionResult(
                            success = true,
                            fenPlacement =
                                fenPlacement,
                            uncertainSquares =
                                emptyList(),
                            error =
                                null
                        )
                    )


                } finally {

                    connection.disconnect()
                }


            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Gemini recognition failed.",
                    e
                )


                callback(
                    RecognitionResult(
                        success = false,
                        error =
                            e.message
                                ?: "Gemini recognition failed."
                    )
                )

            } finally {

                /*
                 * Gemini owns this copy.
                 */
                if (
                    !bitmapCopy.isRecycled
                ) {

                    bitmapCopy.recycle()
                }
            }
        }
    }


    /**
     * Validate the piece-placement portion of a FEN.
     *
     * Returns:
     *
     * null  = valid
     * String = reason it was rejected
     */
    /**
     * Add an explicit coordinate border around a board that is ALREADY in
     * standard orientation.
     *
     * The board itself remains 512x512. Nothing is cropped or downscaled
     * beyond the same conservative 512x512 size we were already using.
     */
    private fun createCoordinateLabeledBoard(
        board: Bitmap
    ): Bitmap {

        val output =
            Bitmap.createBitmap(
                LABELED_IMAGE_SIZE,
                LABELED_IMAGE_SIZE,
                Bitmap.Config.ARGB_8888
            )


        val canvas =
            Canvas(
                output
            )


        canvas.drawColor(
            Color.WHITE
        )


        val boardLeft =
            LABEL_MARGIN.toFloat()

        val boardTop =
            LABEL_MARGIN.toFloat()


        canvas.drawBitmap(
            board,
            boardLeft,
            boardTop,
            null
        )


        val textPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.BLACK

                textAlign =
                    Paint.Align.CENTER

                textSize =
                    28f

                typeface =
                    Typeface.create(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )
            }


        val square =
            GEMINI_BOARD_SIZE /
                    8f


        /*
         * Files a-h on BOTH top and bottom.
         */
        for (col in 0 until 8) {

            val x =
                boardLeft +
                        (
                                col +
                                        0.5f
                                ) *
                        square

            val file =
                ('a'.code + col)
                    .toChar()
                    .toString()


            canvas.drawText(
                file,
                x,
                boardTop - 20f,
                textPaint
            )


            canvas.drawText(
                file,
                x,
                boardTop +
                        GEMINI_BOARD_SIZE +
                        43f,
                textPaint
            )
        }


        /*
         * Ranks 8-1 on BOTH left and right.
         */
        for (row in 0 until 8) {

            val yCenter =
                boardTop +
                        (
                                row +
                                        0.5f
                                ) *
                        square

            /*
             * Paint.drawText uses a baseline rather than a vertical center.
             */
            val y =
                yCenter -
                        (
                                textPaint.ascent() +
                                        textPaint.descent()
                                ) /
                        2f

            val rank =
                (8 - row)
                    .toString()


            canvas.drawText(
                rank,
                boardLeft - 30f,
                y,
                textPaint
            )


            canvas.drawText(
                rank,
                boardLeft +
                        GEMINI_BOARD_SIZE +
                        30f,
                y,
                textPaint
            )
        }


        /*
         * Extra corner anchors make the orientation impossible to interpret
         * from chess-piece placement alone.
         */
        val anchorPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.BLACK

                textAlign =
                    Paint.Align.LEFT

                textSize =
                    18f

                typeface =
                    Typeface.create(
                        Typeface.DEFAULT,
                        Typeface.BOLD
                    )
            }


        canvas.drawText(
            "a8",
            4f,
            20f,
            anchorPaint
        )


        anchorPaint.textAlign =
            Paint.Align.RIGHT


        canvas.drawText(
            "h1",
            LABELED_IMAGE_SIZE - 4f,
            LABELED_IMAGE_SIZE - 6f,
            anchorPaint
        )


        return output
    }


    private fun validateFenPlacement(
        fen: String
    ): String? {

        if (
            fen.isBlank()
        ) {

            return "Gemini returned an empty FEN."
        }


        /*
         * Piece-placement must not contain the other FEN fields.
         */
        if (
            fen.contains(" ")
        ) {

            return "Gemini returned more than piece placement."
        }


        val ranks =
            fen.split("/")


        if (
            ranks.size != 8
        ) {

            return "FEN must contain exactly 8 ranks."
        }


        val expanded =
            mutableListOf<Char>()


        val validPieces =
            "prnbqkPRNBQK"


        for (
        rank in ranks
        ) {

            var squareCount =
                0


            for (
            char in rank
            ) {

                when {

                    char in '1'..'8' -> {

                        val emptyCount =
                            char.digitToInt()


                        squareCount +=
                            emptyCount


                        repeat(
                            emptyCount
                        ) {

                            expanded.add(
                                '.'
                            )
                        }
                    }


                    validPieces.contains(
                        char
                    ) -> {

                        squareCount++

                        expanded.add(
                            char
                        )
                    }


                    else -> {

                        return "FEN contains invalid character '$char'."
                    }
                }
            }


            if (
                squareCount != 8
            ) {

                return "Every FEN rank must describe exactly 8 squares."
            }
        }


        if (
            expanded.size != 64
        ) {

            return "FEN does not describe exactly 64 squares."
        }


        /*
         * Exactly one king per side.
         */
        if (
            expanded.count {
                it == 'K'
            } != 1
        ) {

            return "Expected exactly one white king."
        }


        if (
            expanded.count {
                it == 'k'
            } != 1
        ) {

            return "Expected exactly one black king."
        }


        /*
         * Pawns cannot exceed their original count.
         */
        if (
            expanded.count {
                it == 'P'
            } > 8
        ) {

            return "Too many white pawns."
        }


        if (
            expanded.count {
                it == 'p'
            } > 8
        ) {

            return "Too many black pawns."
        }


        /*
         * Promotions can produce extra queens/rooks/etc.,
         * but total pieces can never exceed 16 per side.
         */
        val whitePieces =
            expanded.count {
                it in "PRNBQK"
            }


        val blackPieces =
            expanded.count {
                it in "prnbqk"
            }


        if (
            whitePieces > 16
        ) {

            return "Too many white pieces."
        }


        if (
            blackPieces > 16
        ) {

            return "Too many black pieces."
        }


        /*
         * A legal settled chess position cannot have an unpromoted pawn
         * sitting on rank 8 or rank 1.
         *
         * expanded[0..7]   = rank 8
         * expanded[56..63] = rank 1
         */
        for (
        i in 0..7
        ) {

            if (
                expanded[i] == 'P' ||
                expanded[i] == 'p'
            ) {

                return "Pawn detected on rank 8."
            }
        }


        for (
        i in 56..63
        ) {

            if (
                expanded[i] == 'P' ||
                expanded[i] == 'p'
            ) {

                return "Pawn detected on rank 1."
            }
        }


        return null
    }
}
