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

    fun start(runName: String) {

        // Close an existing experiment if one is still open.
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

        writer?.println(
            "timestamp_ms," +
                    "elapsed_ms," +
                    "frame_number," +
                    "frame_bytes," +
                    "cumulative_rx_bytes," +
                    "cumulative_tx_bytes," +
                    "fetch_ms," +
                    "inference_ms," +
                    "total_ms," +
                    "interarrival_ms," +
                    "detections," +
                    "avg_confidence," +
                    "success," +
                    "error"
        )

        writer?.flush()
    }

    fun log(
        frameNumber: Int,
        frameBytes: Int,
        cumulativeRxBytes: Long,
        cumulativeTxBytes: Long,
        fetchMs: Long,
        inferenceMs: Long,
        totalMs: Long,
        interarrivalMs: Long,
        detections: Int,
        avgConfidence: Double,
        success: Boolean,
        error: String
    ) {

        val timestamp =
            System.currentTimeMillis()

        val elapsed =
            SystemClock.elapsedRealtime() -
                    experimentStartTime

        // Prevent commas/newlines in an error message
        // from breaking the CSV format.
        val safeError =
            error
                .replace(",", ";")
                .replace("\n", " ")
                .replace("\r", " ")

        writer?.println(
            "$timestamp," +
                    "$elapsed," +
                    "$frameNumber," +
                    "$frameBytes," +
                    "$cumulativeRxBytes," +
                    "$cumulativeTxBytes," +
                    "$fetchMs," +
                    "$inferenceMs," +
                    "$totalMs," +
                    "$interarrivalMs," +
                    "$detections," +
                    "$avgConfidence," +
                    "$success," +
                    safeError
        )

        writer?.flush()
    }

    fun stop() {

        writer?.flush()
        writer?.close()

        writer = null
    }

    fun getCurrentFile(): File? {
        return currentFile
    }
}