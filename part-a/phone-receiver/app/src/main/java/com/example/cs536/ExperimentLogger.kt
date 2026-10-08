package com.example.cs536

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

class ExperimentLogger(
    private val context: Context
) {

    private var writer: PrintWriter? = null

    private var experimentStartTime = 0L

    private var currentFile: File? = null

    @Synchronized
    fun start(runName: String) {

        stop()

        val experimentDirectory =
            File(
                context.getExternalFilesDir(null),
                "experiments"
            )

        if (!experimentDirectory.exists()) {
            experimentDirectory.mkdirs()
        }

        val safeRunName =
            runName
                .trim()
                .replace(
                    Regex("[^A-Za-z0-9_-]"),
                    "_"
                )

        currentFile =
            File(
                experimentDirectory,
                "$safeRunName.csv"
            )

        writer =
            PrintWriter(
                FileWriter(
                    currentFile,
                    false
                )
            )

        experimentStartTime =
            SystemClock.elapsedRealtime()

        /*
         * Part D CSV
         *
         * processed_frame_number:
         *      Number of frames actually processed by AI.
         *
         * stream_frame_number:
         *      Number of frames received from MJPEG stream.
         *
         * dropped_frames:
         *      Number of older frames intentionally discarded
         *      because a newer frame arrived before AI could
         *      process the old one.
         *
         * frame_receive_ms:
         *      Time spent waiting for / reading this JPEG
         *      from the persistent stream.
         *
         * processing_ms:
         *      Local decode + AI + metric processing time.
         *
         * frame_age_ms:
         *      Time from network receipt of this JPEG until
         *      AI processing finishes.
         */
        writer?.println(
            "timestamp_ms," +
                    "elapsed_ms," +
                    "processed_frame_number," +
                    "stream_frame_number," +
                    "frame_bytes," +
                    "cumulative_rx_bytes," +
                    "cumulative_tx_bytes," +
                    "frame_receive_ms," +
                    "inference_ms," +
                    "processing_ms," +
                    "frame_age_ms," +
                    "interarrival_ms," +
                    "detections," +
                    "avg_confidence," +
                    "success," +
                    "reconnect_count," +
                    "dropped_frames," +
                    "error"
        )

        writer?.flush()
    }

    @Synchronized
    fun log(
        processedFrameNumber: Int,
        streamFrameNumber: Int,
        frameBytes: Int,
        cumulativeRxBytes: Long,
        cumulativeTxBytes: Long,
        frameReceiveMs: Long,
        inferenceMs: Long,
        processingMs: Long,
        frameAgeMs: Long,
        interarrivalMs: Long,
        detections: Int,
        avgConfidence: Double,
        success: Boolean,
        reconnectCount: Int,
        droppedFrames: Int,
        error: String
    ) {

        val timestamp =
            System.currentTimeMillis()

        val elapsed =
            SystemClock.elapsedRealtime() -
                    experimentStartTime

        val safeError =
            error
                .replace(",", ";")
                .replace("\n", " ")
                .replace("\r", " ")

        writer?.println(
            "$timestamp," +
                    "$elapsed," +
                    "$processedFrameNumber," +
                    "$streamFrameNumber," +
                    "$frameBytes," +
                    "$cumulativeRxBytes," +
                    "$cumulativeTxBytes," +
                    "$frameReceiveMs," +
                    "$inferenceMs," +
                    "$processingMs," +
                    "$frameAgeMs," +
                    "$interarrivalMs," +
                    "$detections," +
                    "$avgConfidence," +
                    "$success," +
                    "$reconnectCount," +
                    "$droppedFrames," +
                    safeError
        )

        writer?.flush()
    }

    @Synchronized
    fun stop() {

        writer?.flush()
        writer?.close()

        writer = null
    }

    fun getCurrentFile(): File? {
        return currentFile
    }
}