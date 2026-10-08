package com.example.cs536

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var runNameInput: EditText
    private lateinit var experimentLogger: ExperimentLogger

    private var frameNumber = 0
    private var lastFrameTime = 0L

    /*
     * Network traffic counters measured when
     * an experiment starts.
     */
    private var startRxBytes = 0L
    private var startTxBytes = 0L

    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var streamImage: ImageView
    private lateinit var overlayView: OverlayView

    private lateinit var detector: ObjectDetectorHelper

    private val executor =
        Executors.newSingleThreadExecutor()

    private val mainHandler =
        Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    data class FrameData(
        val bitmap: Bitmap,
        val bytes: Int,
        val fetchMs: Long
    )

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        ipInput =
            findViewById(R.id.ipInput)

        runNameInput =
            findViewById(R.id.runNameInput)

        connectButton =
            findViewById(R.id.connectButton)

        statusText =
            findViewById(R.id.statusText)

        streamImage =
            findViewById(R.id.streamImage)

        overlayView =
            findViewById(R.id.overlayView)

        detector =
            ObjectDetectorHelper(this)

        experimentLogger =
            ExperimentLogger(this)

        connectButton.setOnClickListener {

            if (!running) {

                /*
                 * START EXPERIMENT
                 */

                running = true

                frameNumber = 0
                lastFrameTime = 0L

                /*
                 * Save the Pixel application's
                 * starting network byte counters.
                 *
                 * RX = bytes received by Pixel app
                 * TX = bytes transmitted by Pixel app
                 */
                val uid =
                    applicationInfo.uid

                startRxBytes =
                    TrafficStats.getUidRxBytes(uid)

                startTxBytes =
                    TrafficStats.getUidTxBytes(uid)

                val runName =
                    runNameInput.text
                        .toString()
                        .trim()

                experimentLogger.start(
                    if (runName.isEmpty()) {
                        "unnamed_run"
                    } else {
                        runName
                    }
                )

                connectButton.text =
                    "Stop AI Detection"

                statusText.text =
                    "Experiment running..."

                startDetection()

            } else {

                /*
                 * STOP EXPERIMENT
                 */

                running = false

                experimentLogger.stop()

                connectButton.text =
                    "Start AI Detection"

                statusText.text =
                    "Experiment stopped"

                overlayView.clear()
            }
        }
    }

    private fun startDetection() {

        val ip =
            ipInput.text
                .toString()
                .trim()

        executor.execute {

            while (running) {

                val cycleStart =
                    SystemClock.elapsedRealtime()

                try {

                    /*
                     * -------------------------
                     * NETWORK FETCH
                     * -------------------------
                     */

                    val frame =
                        getFrame(ip)

                    val now =
                        SystemClock.elapsedRealtime()

                    val interarrivalMs =
                        if (lastFrameTime == 0L) {

                            0L

                        } else {

                            now - lastFrameTime
                        }

                    lastFrameTime = now

                    frameNumber++

                    /*
                     * -------------------------
                     * AI INFERENCE
                     * -------------------------
                     */

                    val inferenceStart =
                        SystemClock.elapsedRealtime()

                    val result =
                        detector.detect(
                            frame.bitmap
                        )

                    val inferenceMs =
                        SystemClock.elapsedRealtime() -
                                inferenceStart

                    /*
                     * -------------------------
                     * DETECTION METRICS
                     * -------------------------
                     */

                    val detections =
                        result.detections()

                    val detectionCount =
                        detections.size

                    val confidenceValues =
                        detections.mapNotNull { detection ->

                            detection.categories()
                                .firstOrNull()
                                ?.score()
                                ?.toDouble()
                        }

                    val avgConfidence =
                        if (confidenceValues.isEmpty()) {

                            0.0

                        } else {

                            confidenceValues.average()
                        }

                    val totalMs =
                        SystemClock.elapsedRealtime() -
                                cycleStart

                    /*
                     * -------------------------
                     * NETWORK BYTE COUNTERS
                     * -------------------------
                     */

                    val traffic =
                        getTrafficBytes()

                    val cumulativeRxBytes =
                        traffic.first

                    val cumulativeTxBytes =
                        traffic.second

                    /*
                     * -------------------------
                     * WRITE SUCCESSFUL CSV ROW
                     * -------------------------
                     */

                    experimentLogger.log(
                        frameNumber = frameNumber,
                        frameBytes = frame.bytes,
                        cumulativeRxBytes = cumulativeRxBytes,
                        cumulativeTxBytes = cumulativeTxBytes,
                        fetchMs = frame.fetchMs,
                        inferenceMs = inferenceMs,
                        totalMs = totalMs,
                        interarrivalMs = interarrivalMs,
                        detections = detectionCount,
                        avgConfidence = avgConfidence,
                        success = true,
                        error = ""
                    )

                    /*
                     * -------------------------
                     * UPDATE DISPLAY
                     * -------------------------
                     */

                    mainHandler.post {

                        streamImage.setImageBitmap(
                            frame.bitmap
                        )

                        overlayView.setResults(
                            result,
                            frame.bitmap.width,
                            frame.bitmap.height
                        )

                        statusText.text =
                            "Frame: $frameNumber | " +
                                    "Network: ${frame.fetchMs} ms | " +
                                    "AI: $inferenceMs ms | " +
                                    "Objects: $detectionCount"
                    }

                } catch (e: Exception) {

                    val totalMs =
                        SystemClock.elapsedRealtime() -
                                cycleStart

                    /*
                     * Even when a frame fails,
                     * record current network traffic.
                     */

                    val traffic =
                        getTrafficBytes()

                    val cumulativeRxBytes =
                        traffic.first

                    val cumulativeTxBytes =
                        traffic.second

                    /*
                     * -------------------------
                     * LOG FAILED FRAME
                     * -------------------------
                     */

                    experimentLogger.log(
                        frameNumber = frameNumber,
                        frameBytes = 0,
                        cumulativeRxBytes = cumulativeRxBytes,
                        cumulativeTxBytes = cumulativeTxBytes,
                        fetchMs = 0,
                        inferenceMs = 0,
                        totalMs = totalMs,
                        interarrivalMs = 0,
                        detections = 0,
                        avgConfidence = 0.0,
                        success = false,
                        error = e.message
                            ?: "Unknown error"
                    )

                    mainHandler.post {

                        statusText.text =
                            "Error: ${e.message}"
                    }
                }

                /*
                 * Current Part C baseline:
                 * wait 200 ms between cycles.
                 */

                try {

                    Thread.sleep(200)

                } catch (_: InterruptedException) {

                    break
                }
            }
        }
    }

    /*
     * Returns network traffic generated by this app
     * since the current experiment started.
     *
     * first  = cumulative RX bytes
     * second = cumulative TX bytes
     */
    private fun getTrafficBytes(): Pair<Long, Long> {

        val uid =
            applicationInfo.uid

        val currentRx =
            TrafficStats.getUidRxBytes(uid)

        val currentTx =
            TrafficStats.getUidTxBytes(uid)

        val cumulativeRxBytes =
            if (
                currentRx != TrafficStats.UNSUPPORTED.toLong() &&
                startRxBytes != TrafficStats.UNSUPPORTED.toLong() &&
                currentRx >= startRxBytes
            ) {
                currentRx - startRxBytes
            } else {
                0L
            }

        val cumulativeTxBytes =
            if (
                currentTx != TrafficStats.UNSUPPORTED.toLong() &&
                startTxBytes != TrafficStats.UNSUPPORTED.toLong() &&
                currentTx >= startTxBytes
            ) {
                currentTx - startTxBytes
            } else {
                0L
            }

        return Pair(
            cumulativeRxBytes,
            cumulativeTxBytes
        )
    }

    private fun getFrame(
        ip: String
    ): FrameData {

        var connection:
                HttpURLConnection? = null

        val fetchStart =
            SystemClock.elapsedRealtime()

        try {

            val url =
                URL(
                    "http://$ip:8080/frame.jpg"
                )

            connection =
                url.openConnection()
                        as HttpURLConnection

            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.useCaches = false

            connection.connect()

            /*
             * If the Air2 HTTP server returns an
             * error code, treat this frame as failed.
             */
            if (
                connection.responseCode !=
                HttpURLConnection.HTTP_OK
            ) {

                throw Exception(
                    "HTTP ${connection.responseCode}"
                )
            }

            val imageBytes =
                connection.inputStream.use {
                    it.readBytes()
                }

            val fetchMs =
                SystemClock.elapsedRealtime() -
                        fetchStart

            val bitmap =
                BitmapFactory.decodeByteArray(
                    imageBytes,
                    0,
                    imageBytes.size
                )
                    ?: throw Exception(
                        "Could not decode camera frame"
                    )

            return FrameData(
                bitmap = bitmap,
                bytes = imageBytes.size,
                fetchMs = fetchMs
            )

        } finally {

            connection?.disconnect()
        }
    }

    override fun onDestroy() {

        running = false

        experimentLogger.stop()

        detector.close()

        executor.shutdownNow()

        super.onDestroy()
    }
}