package com.example.cs536

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private lateinit var receiverRoot: FrameLayout
    private lateinit var captureStage: FrameLayout
    private lateinit var setupScreen: View
    private lateinit var setupPanel: View
    private lateinit var setupHeading: View
    private lateinit var presentationControls: View
    private lateinit var presentationNotice: TextView
    private lateinit var fullscreenButton: Button
    private lateinit var fillButton: Button
    private var presentationMode = false
    private var fillScreen = false
    private var receivedFirstDisplayFrame = false
    private var helpShown = false
    private var hasStreamProblem = false
    @Volatile private var sessionId = 0L
    private val hidePresentationControls = Runnable {
        if (presentationMode) presentationControls.visibility = View.GONE
    }

    // Access only on processingExecutor: GPU setup, detection, and close
    // must all happen on the same worker thread.
    private var detector: ObjectDetectorHelper? = null

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

        configurePresentationUi()

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
        val ip = ipInput.text.toString().trim()
        if (ip.isBlank() || ip.contains("://") || ip.contains("/") || ip.contains(":") ||
            ip.any { it.isWhitespace() }) {
            ipInput.error = "Enter the glasses' IP only, without http:// or a port"
            ipInput.requestFocus()
            return
        }
        getSharedPreferences("receiver_display", MODE_PRIVATE).edit()
            .putString("ip", ip)
            .putString("run_name", runNameInput.text.toString().trim())
            .apply()
        sessionId += 1L
        running = true
        receivedFirstDisplayFrame = false
        hasStreamProblem = false
        streamImage.setImageDrawable(null)
        overlayView.clear()
        presentationNotice.text = "Connecting to glasses…"
        presentationNotice.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ipInput.isEnabled = false
        runNameInput.isEnabled = false
        fullscreenButton.visibility = View.VISIBLE
        setPresentationMode(true)

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
        streamImage.setImageDrawable(null)
        receivedFirstDisplayFrame = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ipInput.isEnabled = true
        runNameInput.isEnabled = true
        fullscreenButton.visibility = View.GONE
        presentationNotice.visibility = View.GONE
        setPresentationMode(false)
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

        val networkSession = sessionId
        networkExecutor.execute {
            if (!running || sessionId != networkSession) return@execute

            var streamReader:
                    MjpegStreamReader? = null

            while (running && sessionId == networkSession) {

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

                    if (!running || sessionId != networkSession) break
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

                    if (!running || sessionId != networkSession) {
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
                        if (!running || sessionId != networkSession) return@post
                        statusText.text = "Stream error: ${e.message} | Reconnects: $reconnects"
                        hasStreamProblem = true
                        presentationNotice.text = "Connection lost. Reconnecting…"
                        presentationNotice.visibility = if (presentationMode) View.VISIBLE else View.GONE
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
        val processingSession = sessionId
        processingExecutor.execute {
            if (!running || sessionId != processingSession) return@execute

            val workerDetector = try {
                detector ?: ObjectDetectorHelper(applicationContext).also {
                    detector = it
                }
            } catch (e: RuntimeException) {
                mainHandler.post {
                    if (running && sessionId == processingSession) {
                        stopExperiment()
                        statusText.text = "AI initialization error: ${e.message}"
                    }
                }
                return@execute
            }

            while (running && sessionId == processingSession) {

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
                        workerDetector.detect(
                            bitmap
                        )

                    val inferenceMs =
                        SystemClock.elapsedRealtime() -
                                inferenceStart
                    val detectorBackend = workerDetector.backendLabel
                    if (!running || sessionId != processingSession) break

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
                        if (!running || sessionId != processingSession) return@post
                        receivedFirstDisplayFrame = true
                        hasStreamProblem = false
                        presentationNotice.visibility = View.GONE
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
                                    "AI ($detectorBackend): $inferenceMs ms | " +
                                    "Age: $frameAgeMs ms"
                    }

                } catch (e: Exception) {

                    if (!running || sessionId != processingSession) break
                    mainHandler.post {
                        if (!running || sessionId != processingSession) return@post
                        statusText.text = "Processing error: ${e.message}"
                        hasStreamProblem = true
                        presentationNotice.text = "Detection error. Tap the video for controls."
                        presentationNotice.visibility = if (presentationMode) View.VISIBLE else View.GONE
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

    // Presentation mode only changes the UI: frame selection and CSV fields
    // stay the same, and GPU operations remain on processingExecutor.
    private fun configurePresentationUi() {
        receiverRoot = findViewById(R.id.receiverRoot)
        captureStage = findViewById(R.id.captureStage)
        setupScreen = findViewById(R.id.setupScreen)
        setupPanel = findViewById(R.id.setupPanel)
        setupHeading = findViewById(R.id.setupHeading)
        presentationControls = findViewById(R.id.presentationControls)
        presentationNotice = findViewById(R.id.presentationNotice)
        fullscreenButton = findViewById(R.id.fullscreenButton)
        fillButton = findViewById(R.id.fillButton)

        val preferences = getSharedPreferences("receiver_display", MODE_PRIVATE)
        ipInput.setText(preferences.getString("ip", ""))
        runNameInput.setText(preferences.getString("run_name", "class_demo"))
        fillScreen = preferences.getBoolean("fill_screen", false)
        applyImageFit()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(receiverRoot) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboardHeight =
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom

            if (presentationMode) {
                view.setPadding(0, 0, 0, 0)
            } else {
                view.setPadding(
                    safe.left,
                    safe.top,
                    safe.right,
                    maxOf(safe.bottom, keyboardHeight)
                )

                // Hide the heading to give the input card more room.
                val landscape =
                    resources.configuration.orientation ==
                            Configuration.ORIENTATION_LANDSCAPE

                setupHeading.visibility =
                    if (keyboardHeight > 0 || landscape) View.GONE
                    else View.VISIBLE

                // Scroll the focused input into view on smaller screens.
                if (keyboardHeight > 0) {
                    val focusedInput = currentFocus as? EditText
                    focusedInput?.post {
                        if (focusedInput.hasFocus()) {
                            focusedInput.requestRectangleOnScreen(
                                android.graphics.Rect(
                                    0, 0,
                                    focusedInput.width,
                                    focusedInput.height
                                ),
                                true
                            )
                        }
                    }
                }
            }


            val controlInsets = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            presentationControls.setPadding(
                dp(12) + controlInsets.left, dp(12),
                dp(12) + controlInsets.right, dp(12) + controlInsets.bottom
            )
            insets
        }
        fullscreenButton.setOnClickListener { if (running) setPresentationMode(true) }
        findViewById<Button>(R.id.showControlsButton).setOnClickListener {
            setPresentationMode(false)
        }
        findViewById<Button>(R.id.stopPresentationButton).setOnClickListener {
            stopExperiment()
        }
        fillButton.setOnClickListener {
            fillScreen = !fillScreen
            preferences.edit().putBoolean("fill_screen", fillScreen).apply()
            applyImageFit()
            scheduleControlsHide()
        }
        captureStage.setOnClickListener {
            if (presentationMode) {
                val show = presentationControls.visibility != View.VISIBLE
                presentationControls.visibility = if (show) View.VISIBLE else View.GONE
                mainHandler.removeCallbacks(hidePresentationControls)
                if (show) scheduleControlsHide()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    presentationMode -> setPresentationMode(false)
                    running -> stopExperiment()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        resizeSetupPanel()
        ViewCompat.requestApplyInsets(receiverRoot)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyImageFit() {
        streamImage.scaleType = if (fillScreen) ImageView.ScaleType.CENTER_CROP
        else ImageView.ScaleType.FIT_CENTER
        overlayView.setFillScreen(fillScreen)
        fillButton.text = if (fillScreen) "Fit whole frame" else "Fill screen"
    }

    private fun scheduleControlsHide() {
        mainHandler.removeCallbacks(hidePresentationControls)
        mainHandler.postDelayed(hidePresentationControls, 4000L)
    }

    private fun setPresentationMode(enabled: Boolean) {
        presentationMode = enabled
        mainHandler.removeCallbacks(hidePresentationControls)
        setupScreen.visibility = if (enabled) View.GONE else View.VISIBLE
        presentationControls.visibility = View.GONE
        presentationNotice.visibility = if (enabled && (!receivedFirstDisplayFrame || hasStreamProblem))
            View.VISIBLE else View.GONE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            currentFocus?.clearFocus()
            controller.hide(WindowInsetsCompat.Type.ime())
            supportActionBar?.hide()
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            if (!helpShown) {
                Toast.makeText(this, "Tap video for controls. Back returns to settings.",
                    Toast.LENGTH_LONG).show()
                helpShown = true
            }
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(receiverRoot)
    }

    private fun resizeSetupPanel() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        setupHeading.visibility = if (landscape) View.GONE else View.VISIBLE
        val params = setupPanel.layoutParams as FrameLayout.LayoutParams
        val available = resources.displayMetrics.widthPixels - dp(40)
        params.width = if (landscape) minOf(dp(380), available)
        else minOf(dp(560), available)
        params.gravity = if (landscape) Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        setupPanel.layoutParams = params
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resizeSetupPanel()
        ViewCompat.requestApplyInsets(receiverRoot)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && presentationMode) {
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {

        running = false
        mainHandler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        activeStreamReader?.close()

        activeStreamReader = null

        latestFrame.set(null)

        experimentLogger.stop()

        networkExecutor.shutdownNow()

        // Let in-flight detection finish, then release it on its owner thread.
        processingExecutor.execute {
            detector?.close()
            detector = null
        }
        processingExecutor.shutdown()

        super.onDestroy()
    }
}