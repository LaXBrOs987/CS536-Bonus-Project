package com.example.cs536

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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private lateinit var runNameInput: EditText
    private lateinit var experimentLogger: ExperimentLogger

    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var streamImage: ImageView
    private lateinit var overlayView: OverlayView

    private lateinit var detector: ObjectDetectorHelper

    /*
     * Separate network and AI threads.
     *
     * Network thread:
     * Continuously drains MJPEG stream.
     *
     * Processing thread:
     * Processes only newest available frame.
     */
    private val networkExecutor =
        Executors.newSingleThreadExecutor()

    private val processingExecutor =
        Executors.newSingleThreadExecutor()

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    @Volatile
    private var running = false

    /*
     * Air2 stream currently being used.
     *
     * Closing this interrupts a blocking read
     * when Stop is pressed.
     */
    @Volatile
    private var activeStreamReader:
            MjpegStreamReader? = null

    /*
     * Network counters at beginning of experiment.
     */
    private var startRxBytes = 0L
    private var startTxBytes = 0L

    /*
     * Part D counters.
     */
    private val receivedFrames =
        AtomicInteger(0)

    private val processedFrames =
        AtomicInteger(0)

    private val droppedFrames =
        AtomicInteger(0)

    private val reconnectCount =
        AtomicInteger(0)

    /*
     * Most recently received frame.
     *
     * There is NEVER a queue of old frames.
     *
     * A newly received frame replaces an older
     * unprocessed frame.
     */
    private val latestFrame =
        AtomicReference<FramePacket?>(null)

    /*
     * Arrival time of last frame processed by AI.
     */
    private var lastProcessedArrivalTime = 0L

    data class FramePacket(
        val streamFrameNumber: Int,
        val imageBytes: ByteArray,
        val receivedAtMs: Long,
        val frameReceiveMs: Long
    )

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_main
        )

        ipInput =
            findViewById(
                R.id.ipInput
            )

        runNameInput =
            findViewById(
                R.id.runNameInput
            )

        connectButton =
            findViewById(
                R.id.connectButton
            )

        statusText =
            findViewById(
                R.id.statusText
            )

        streamImage =
            findViewById(
                R.id.streamImage
            )

        overlayView =
            findViewById(
                R.id.overlayView
            )

        detector =
            ObjectDetectorHelper(
                this
            )

        experimentLogger =
            ExperimentLogger(
                this
            )

        connectButton.setOnClickListener {

            if (!running) {

                startExperiment()

            } else {

                stopExperiment()
            }
        }
    }

    private fun startExperiment() {

        running = true

        receivedFrames.set(0)
        processedFrames.set(0)
        droppedFrames.set(0)
        reconnectCount.set(0)

        latestFrame.set(null)

        lastProcessedArrivalTime = 0L

        /*
         * Capture initial Android UID traffic counters.
         */
        val uid =
            applicationInfo.uid

        startRxBytes =
            TrafficStats.getUidRxBytes(
                uid
            )

        startTxBytes =
            TrafficStats.getUidTxBytes(
                uid
            )

        val runName =
            runNameInput.text
                .toString()
                .trim()

        experimentLogger.start(
            if (runName.isEmpty()) {

                "partd_unnamed_run"

            } else {

                runName
            }
        )

        connectButton.text =
            "Stop AI Detection"

        statusText.text =
            "Part D latest-frame MJPEG running..."

        val ip =
            ipInput.text
                .toString()
                .trim()

        startNetworkThread(ip)

        startProcessingThread()
    }

    private fun stopExperiment() {

        running = false

        /*
         * Interrupt blocking MJPEG read.
         */
        activeStreamReader?.close()

        activeStreamReader = null

        latestFrame.set(null)

        experimentLogger.stop()

        connectButton.text =
            "Start AI Detection"

        statusText.text =
            "Experiment stopped"

        overlayView.clear()
    }

    /*
     * ============================================================
     * NETWORK THREAD
     * ============================================================
     *
     * Reads the MJPEG stream continuously.
     *
     * If AI is still processing a previous frame,
     * a new frame REPLACES the waiting frame.
     *
     * This prevents stale-frame backlog.
     */
    private fun startNetworkThread(
        ip: String
    ) {

        val streamUrl =
            "http://$ip:8080/stream.mjpg"

        networkExecutor.execute {

            var streamReader:
                    MjpegStreamReader? = null

            while (running) {

                val receiveStart =
                    SystemClock.elapsedRealtime()

                try {

                    /*
                     * Connect only when necessary.
                     */
                    if (streamReader == null) {

                        val newReader =
                            MjpegStreamReader(
                                streamUrl
                            )

                        newReader.connect()

                        streamReader =
                            newReader

                        activeStreamReader =
                            newReader
                    }

                    /*
                     * Receive next JPEG from persistent
                     * MJPEG stream.
                     */
                    val imageBytes =
                        streamReader.readFrame()

                    val receivedAt =
                        SystemClock.elapsedRealtime()

                    val frameReceiveMs =
                        receivedAt -
                                receiveStart

                    val streamFrameNumber =
                        receivedFrames.incrementAndGet()

                    val newFrame =
                        FramePacket(
                            streamFrameNumber =
                                streamFrameNumber,
                            imageBytes =
                                imageBytes,
                            receivedAtMs =
                                receivedAt,
                            frameReceiveMs =
                                frameReceiveMs
                        )

                    /*
                     * Atomically replace any frame that
                     * AI has not processed yet.
                     */
                    val replacedFrame =
                        latestFrame.getAndSet(
                            newFrame
                        )

                    /*
                     * If something was already waiting,
                     * that old frame has intentionally
                     * been dropped.
                     */
                    if (replacedFrame != null) {

                        droppedFrames.incrementAndGet()
                    }

                } catch (e: Exception) {

                    if (!running) {
                        break
                    }

                    val failureReceiveMs =
                        SystemClock.elapsedRealtime() -
                                receiveStart

                    val reconnects =
                        reconnectCount.incrementAndGet()

                    try {

                        streamReader?.close()

                    } catch (_: Exception) {
                    }

                    streamReader = null
                    activeStreamReader = null

                    val traffic =
                        getTrafficBytes()

                    /*
                     * Log the network failure.
                     *
                     * Logger is synchronized because
                     * network and AI threads can both
                     * write rows.
                     */
                    experimentLogger.log(
                        processedFrameNumber =
                            processedFrames.get(),
                        streamFrameNumber =
                            receivedFrames.get(),
                        frameBytes =
                            0,
                        cumulativeRxBytes =
                            traffic.first,
                        cumulativeTxBytes =
                            traffic.second,
                        frameReceiveMs =
                            failureReceiveMs,
                        inferenceMs =
                            0,
                        processingMs =
                            0,
                        frameAgeMs =
                            0,
                        interarrivalMs =
                            0,
                        detections =
                            0,
                        avgConfidence =
                            0.0,
                        success =
                            false,
                        reconnectCount =
                            reconnects,
                        droppedFrames =
                            droppedFrames.get(),
                        error =
                            e.message
                                ?: "Unknown MJPEG stream error"
                    )

                    mainHandler.post {

                        statusText.text =
                            "Stream error: ${e.message} | " +
                                    "Reconnects: $reconnects"
                    }

                    /*
                     * Prevent tight reconnect loop.
                     */
                    try {

                        Thread.sleep(
                            500
                        )

                    } catch (_: InterruptedException) {

                        break
                    }
                }
            }

            try {

                streamReader?.close()

            } catch (_: Exception) {
            }

            activeStreamReader = null
        }
    }

    /*
     * ============================================================
     * AI PROCESSING THREAD
     * ============================================================
     *
     * Processes only the newest frame currently available.
     */
    private fun startProcessingThread() {

        processingExecutor.execute {

            while (running) {

                /*
                 * Take newest frame and clear slot.
                 *
                 * Network thread can immediately place
                 * another newest frame into latestFrame.
                 */
                val frame =
                    latestFrame.getAndSet(
                        null
                    )

                if (frame == null) {

                    /*
                     * No new frame available yet.
                     *
                     * Small sleep prevents busy spinning.
                     */
                    try {

                        Thread.sleep(
                            2
                        )

                    } catch (_: InterruptedException) {

                        break
                    }

                    continue
                }

                val processingStart =
                    SystemClock.elapsedRealtime()

                try {

                    /*
                     * -------------------------
                     * DECODE JPEG
                     * -------------------------
                     */
                    val bitmap =
                        BitmapFactory.decodeByteArray(
                            frame.imageBytes,
                            0,
                            frame.imageBytes.size
                        )
                            ?: throw Exception(
                                "Could not decode MJPEG frame"
                            )

                    /*
                     * -------------------------
                     * FRAME INTERARRIVAL
                     * -------------------------
                     *
                     * Uses NETWORK ARRIVAL timestamps
                     * of the frames actually processed.
                     */
                    val interarrivalMs =
                        if (
                            lastProcessedArrivalTime == 0L
                        ) {

                            0L

                        } else {

                            frame.receivedAtMs -
                                    lastProcessedArrivalTime
                        }

                    lastProcessedArrivalTime =
                        frame.receivedAtMs

                    /*
                     * -------------------------
                     * AI INFERENCE
                     * -------------------------
                     */
                    val inferenceStart =
                        SystemClock.elapsedRealtime()

                    val result =
                        detector.detect(
                            bitmap
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
                        detections.mapNotNull {
                                detection ->

                            detection.categories()
                                .firstOrNull()
                                ?.score()
                                ?.toDouble()
                        }

                    val avgConfidence =
                        if (
                            confidenceValues.isEmpty()
                        ) {

                            0.0

                        } else {

                            confidenceValues.average()
                        }

                    val processingEnd =
                        SystemClock.elapsedRealtime()

                    /*
                     * Local time for decode + AI +
                     * metric processing.
                     */
                    val processingMs =
                        processingEnd -
                                processingStart

                    /*
                     * How old was the network-received
                     * frame when processing completed?
                     *
                     * With latest-frame dropping this
                     * should remain small rather than
                     * increasing throughout the run.
                     */
                    val frameAgeMs =
                        processingEnd -
                                frame.receivedAtMs

                    val processedFrameNumber =
                        processedFrames.incrementAndGet()

                    /*
                     * -------------------------
                     * NETWORK BYTE COUNTERS
                     * -------------------------
                     */
                    val traffic =
                        getTrafficBytes()

                    /*
                     * -------------------------
                     * LOG SUCCESSFUL FRAME
                     * -------------------------
                     */
                    experimentLogger.log(
                        processedFrameNumber =
                            processedFrameNumber,
                        streamFrameNumber =
                            frame.streamFrameNumber,
                        frameBytes =
                            frame.imageBytes.size,
                        cumulativeRxBytes =
                            traffic.first,
                        cumulativeTxBytes =
                            traffic.second,
                        frameReceiveMs =
                            frame.frameReceiveMs,
                        inferenceMs =
                            inferenceMs,
                        processingMs =
                            processingMs,
                        frameAgeMs =
                            frameAgeMs,
                        interarrivalMs =
                            interarrivalMs,
                        detections =
                            detectionCount,
                        avgConfidence =
                            avgConfidence,
                        success =
                            true,
                        reconnectCount =
                            reconnectCount.get(),
                        droppedFrames =
                            droppedFrames.get(),
                        error =
                            ""
                    )

                    /*
                     * -------------------------
                     * UPDATE DISPLAY
                     * -------------------------
                     */
                    mainHandler.post {

                        streamImage.setImageBitmap(
                            bitmap
                        )

                        overlayView.setResults(
                            result,
                            bitmap.width,
                            bitmap.height
                        )

                        statusText.text =
                            "Processed: $processedFrameNumber | " +
                                    "Received: ${receivedFrames.get()} | " +
                                    "Dropped: ${droppedFrames.get()} | " +
                                    "AI: $inferenceMs ms | " +
                                    "Age: $frameAgeMs ms"
                    }

                } catch (e: Exception) {

                    if (!running) {
                        break
                    }

                    mainHandler.post {

                        statusText.text =
                            "Processing error: ${e.message}"
                    }
                }
            }
        }
    }

    /*
     * Returns network traffic generated by this
     * application since experiment start.
     *
     * first  = cumulative RX bytes
     * second = cumulative TX bytes
     */
    private fun getTrafficBytes():
            Pair<Long, Long> {

        val uid =
            applicationInfo.uid

        val currentRx =
            TrafficStats.getUidRxBytes(
                uid
            )

        val currentTx =
            TrafficStats.getUidTxBytes(
                uid
            )

        val cumulativeRxBytes =
            if (
                currentRx !=
                TrafficStats.UNSUPPORTED.toLong() &&
                startRxBytes !=
                TrafficStats.UNSUPPORTED.toLong() &&
                currentRx >=
                startRxBytes
            ) {

                currentRx -
                        startRxBytes

            } else {

                0L
            }

        val cumulativeTxBytes =
            if (
                currentTx !=
                TrafficStats.UNSUPPORTED.toLong() &&
                startTxBytes !=
                TrafficStats.UNSUPPORTED.toLong() &&
                currentTx >=
                startTxBytes
            ) {

                currentTx -
                        startTxBytes

            } else {

                0L
            }

        return Pair(
            cumulativeRxBytes,
            cumulativeTxBytes
        )
    }

    override fun onDestroy() {

        running = false

        activeStreamReader?.close()

        activeStreamReader = null

        latestFrame.set(null)

        experimentLogger.stop()

        detector.close()

        networkExecutor.shutdownNow()

        processingExecutor.shutdownNow()

        super.onDestroy()
    }
}