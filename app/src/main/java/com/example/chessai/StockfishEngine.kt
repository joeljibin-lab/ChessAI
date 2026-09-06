package com.example.chessai

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * UCI wrapper for a Stockfish Android executable packaged as:
 *
 *   app/src/main/jniLibs/arm64-v8a/libstockfish.so
 *
 * Optional x86_64 emulator build:
 *
 *   app/src/main/jniLibs/x86_64/libstockfish.so
 *
 * IMPORTANT:
 * This must be a Stockfish binary COMPILED FOR ANDROID for the matching ABI.
 * A Windows stockfish.exe cannot be used here.
 */
class StockfishEngine(
    private val context: Context
) : Closeable {

    data class SuggestedMove(
        val move: String,
        val centipawns: Int?,
        val mate: Int?,
        val depth: Int?,
        val pv: List<String>
    )

    companion object {
        private const val TAG = "ChessAI_Stockfish"
        private const val ENGINE_FILE = "libstockfish.so"

        /*
         * One automatic process restart/retry is enough to recover from the
         * observed "java.io.IOException: Stream closed" failure without
         * creating an infinite retry loop.
         */
        private const val MAX_ANALYSIS_ATTEMPTS = 2
    }

    private val executor =
        Executors.newSingleThreadExecutor()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    @Synchronized
    fun start() {

        if (closed) {
            throw IllegalStateException(
                "StockfishEngine has already been closed."
            )
        }


        if (
            started &&
            process?.isAlive == true &&
            writer != null &&
            reader != null
        ) {
            return
        }


        /*
         * "started" can remain true after the native process/pipe dies.
         * Tear down stale handles before launching a fresh process.
         */
        resetProcess()


        val binary =
            File(
                context.applicationInfo.nativeLibraryDir,
                ENGINE_FILE
            )

        if (!binary.exists()) {

            throw IllegalStateException(
                "Stockfish binary not found at ${binary.absolutePath}. " +
                        "Add app/src/main/jniLibs/<ABI>/libstockfish.so"
            )
        }


        process =
            ProcessBuilder(
                binary.absolutePath
            )
                .redirectErrorStream(true)
                .start()


        writer =
            BufferedWriter(
                OutputStreamWriter(
                    process!!.outputStream
                )
            )


        reader =
            BufferedReader(
                InputStreamReader(
                    process!!.inputStream
                )
            )


        send("uci")
        waitForExact("uciok")

        send(
            "setoption name Threads value 1"
        )

        send(
            "setoption name Hash value 64"
        )

        send(
            "setoption name MultiPV value 3"
        )

        send("isready")
        waitForExact("readyok")

        started = true


        Log.d(
            TAG,
            "Stockfish ready: ${binary.absolutePath}"
        )
    }

    /**
     * Top-N analysis for one FEN.
     *
     * If the native process/pipe dies, this method:
     *
     *   failure -> recreate Stockfish -> retry once
     *
     * Only a successful UCI analysis calls [callback].
     * A final engine failure calls [onError] instead, so callers never mistake
     * "Stream closed" for checkmate/stalemate.
     */
    fun analyzeTopMoves(
        fen: String,
        multiPv: Int = 3,
        moveTimeMs: Long = 400L,
        onError: (Throwable) -> Unit = {},
        callback: (List<SuggestedMove>) -> Unit
    ) {

        if (closed) {

            onError(
                IllegalStateException(
                    "StockfishEngine is closed."
                )
            )

            return
        }


        /*
         * Stop an older search as soon as a newer FEN arrives instead of
         * waiting for its full movetime.
         */
        if (started) {

            try {
                send("stop")
            } catch (e: Exception) {

                /*
                 * Do not report this yet. The queued analysis below will
                 * detect/recreate the dead process and retry.
                 */
                Log.w(
                    TAG,
                    "Unable to stop previous search; engine will be checked before analysis.",
                    e
                )
            }
        }


        executor.execute {

            var lastError: Throwable? =
                null


            for (
            attempt in 1..MAX_ANALYSIS_ATTEMPTS
            ) {

                try {

                    start()


                    val count =
                        multiPv.coerceIn(
                            1,
                            10
                        )


                    send(
                        "setoption name MultiPV value $count"
                    )

                    send(
                        "position fen $fen"
                    )

                    send(
                        "go movetime $moveTimeMs"
                    )


                    val latest =
                        linkedMapOf<Int, SuggestedMove>()


                    var sawBestMove =
                        false


                    while (true) {

                        val line =
                            reader?.readLine()
                                ?: throw IllegalStateException(
                                    "Stockfish stdout closed during analysis."
                                )


                        if (
                            line.startsWith(
                                "info "
                            )
                        ) {

                            parseInfo(line)
                                ?.let {
                                        parsed ->

                                    latest[
                                        parsed.first
                                    ] =
                                        parsed.second
                                }
                        }


                        if (
                            line.startsWith(
                                "bestmove "
                            )
                        ) {

                            sawBestMove =
                                true

                            break
                        }
                    }


                    if (!sawBestMove) {

                        throw IllegalStateException(
                            "Stockfish analysis ended without bestmove."
                        )
                    }


                    callback(
                        latest
                            .toSortedMap()
                            .values
                            .take(count)
                    )


                    return@execute

                } catch (e: Throwable) {

                    lastError =
                        e


                    Log.e(
                        TAG,
                        "Analysis attempt $attempt/$MAX_ANALYSIS_ATTEMPTS failed.",
                        e
                    )


                    /*
                     * Invalidate every pipe/process handle. The next attempt
                     * starts a brand-new native Stockfish process.
                     */
                    resetProcess()


                    if (
                        attempt <
                        MAX_ANALYSIS_ATTEMPTS
                    ) {

                        Log.w(
                            TAG,
                            "Restarting Stockfish and retrying analysis once."
                        )
                    }
                }
            }


            onError(
                lastError
                    ?: IllegalStateException(
                        "Stockfish analysis failed for an unknown reason."
                    )
            )
        }
    }

    /**
     * Example:
     *
     * info depth 18 multipv 2 score cp 31 nodes ... pv g1f3 g8f6 ...
     */
    private fun parseInfo(
        line: String
    ): Pair<Int, SuggestedMove>? {

        val tokens =
            line
                .trim()
                .split(
                    Regex("\\s+")
                )

        var depth: Int? = null
        var multiPv = 1
        var cp: Int? = null
        var mate: Int? = null
        var pvStart = -1

        var i = 0

        while (i < tokens.size) {

            when (tokens[i]) {

                "depth" -> {

                    depth =
                        tokens
                            .getOrNull(i + 1)
                            ?.toIntOrNull()

                    i += 2
                }

                "multipv" -> {

                    multiPv =
                        tokens
                            .getOrNull(i + 1)
                            ?.toIntOrNull()
                            ?: 1

                    i += 2
                }

                "score" -> {

                    val type =
                        tokens.getOrNull(i + 1)

                    val value =
                        tokens
                            .getOrNull(i + 2)
                            ?.toIntOrNull()

                    when (type) {

                        "cp" -> {
                            cp = value
                            mate = null
                        }

                        "mate" -> {
                            mate = value
                            cp = null
                        }
                    }

                    i += 3
                }

                "pv" -> {

                    pvStart =
                        i + 1

                    break
                }

                else -> {
                    i++
                }
            }
        }

        if (pvStart !in tokens.indices) {
            return null
        }

        val pv =
            tokens.subList(
                pvStart,
                tokens.size
            )

        val firstMove =
            pv.firstOrNull()
                ?: return null

        return multiPv to
                SuggestedMove(
                    move = firstMove,
                    centipawns = cp,
                    mate = mate,
                    depth = depth,
                    pv = pv
                )
    }

    @Synchronized
    private fun send(
        command: String
    ) {

        val out =
            writer
                ?: throw IllegalStateException(
                    "Stockfish stdin is not open."
                )

        out.write(command)
        out.newLine()
        out.flush()
    }

    private fun waitForExact(
        expected: String
    ) {

        while (true) {

            val line =
                reader?.readLine()
                    ?: throw IllegalStateException(
                        "Stockfish stopped while waiting for $expected."
                    )

            if (
                line.trim() ==
                expected
            ) {
                return
            }
        }
    }

    /**
     * Close only the native process/pipes.
     *
     * The analysis executor remains alive so a later analysis can restart the
     * process after a Stream-closed failure.
     */
    @Synchronized
    private fun resetProcess() {

        try {
            writer?.close()
        } catch (_: Exception) {
        }

        try {
            reader?.close()
        } catch (_: Exception) {
        }

        try {
            process?.destroy()
        } catch (_: Exception) {
        }

        try {
            process?.waitFor(
                150,
                TimeUnit.MILLISECONDS
            )
        } catch (_: Exception) {
        }

        try {

            if (
                process?.isAlive ==
                true
            ) {
                process?.destroyForcibly()
            }

        } catch (_: Exception) {
        }


        process =
            null

        writer =
            null

        reader =
            null

        started =
            false
    }

    override fun close() {

        if (closed) {
            return
        }


        closed =
            true


        try {

            if (
                started &&
                process?.isAlive == true
            ) {

                send("quit")
            }

        } catch (_: Exception) {
        }


        resetProcess()


        executor.shutdownNow()
    }
}
