package com.cs536.glassessender

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(),
    TextureView.SurfaceTextureListener {

    private lateinit var cameraPreview: TextureView
    private lateinit var statusText: TextView

    private var camera: Camera? = null
    private var server: MjpegServer? = null

    /*
     * Latest JPEG produced by the actual camera callback.
     */
    @Volatile
    private var latestJpeg: ByteArray? = null

    /*
     * elapsedRealtime() timestamp of the last REAL camera frame.
     *
     * This lets the HTTP server detect when the camera itself
     * has frozen even if the network/server is still running.
     */
    @Volatile
    private var latestFrameTimeMs = 0L

    /*
     * Number of actual camera preview frames converted to JPEG.
     */
    private val frameSequence =
        AtomicLong(0)

    /*
     * Camera preview dimensions selected at startup.
     *
     * These are also used if we need to create an off-screen
     * SurfaceTexture after the visible TextureView disappears.
     */
    private var previewWidth = 640
    private var previewHeight = 480

    /*
     * When Android destroys the visible TextureView surface,
     * we switch the camera to this off-screen SurfaceTexture
     * rather than stopping the camera.
     */
    private var fallbackSurfaceTexture:
            SurfaceTexture? = null

    /*
     * Surface currently being used by Camera.setPreviewTexture().
     */
    private var activePreviewSurface:
            SurfaceTexture? = null

    /*
     * Keep CPU and Wi-Fi awake during untethered experiments.
     */
    private var wakeLock:
            PowerManager.WakeLock? = null

    private var wifiLock:
            WifiManager.WifiLock? = null

    companion object {

        private const val CAMERA_PERMISSION_REQUEST = 100

        private const val AIR2_CAMERA_ID = 0

        private const val SERVER_PORT = 8080

        private const val JPEG_QUALITY = 70

        /*
         * If the camera has not produced a NEW frame for
         * two seconds, do not pretend that an old JPEG is live.
         *
         * The Pixel will receive HTTP 503 and correctly record
         * the frame as a failure.
         */
        private const val MAX_FRAME_AGE_MS = 2000L
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        /*
         * Critical for the Air2:
         *
         * Prevent the glasses from turning the display off while
         * this Activity is actively being used.
         */
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(
            R.layout.activity_main
        )

        cameraPreview =
            findViewById(
                R.id.cameraPreview
            )

        statusText =
            findViewById(
                R.id.statusText
            )

        cameraPreview.surfaceTextureListener =
            this

        /*
         * Keep CPU/Wi-Fi active even after USB power is removed.
         */
        acquireWakeLocks()

        /*
         * Start HTTP server independently from the camera.
         */
        startServer()

        /*
         * Start camera when permission/surface are available.
         */
        checkCameraPermission()
    }

    /*
     * ---------------------------------------------------------
     * POWER / WIFI
     * ---------------------------------------------------------
     */

    private fun acquireWakeLocks() {

        try {

            val powerManager =
                getSystemService(
                    Context.POWER_SERVICE
                ) as PowerManager

            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:CameraWakeLock"
                ).apply {

                    setReferenceCounted(false)

                    if (!isHeld) {
                        acquire()
                    }
                }

        } catch (e: Exception) {

            e.printStackTrace()
        }

        try {

            val wifiManager =
                applicationContext
                    .getSystemService(
                        Context.WIFI_SERVICE
                    ) as WifiManager

            wifiLock =
                wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "$packageName:StreamingWifiLock"
                ).apply {

                    setReferenceCounted(false)

                    if (!isHeld) {
                        acquire()
                    }
                }

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    private fun releaseWakeLocks() {

        try {

            if (
                wakeLock?.isHeld == true
            ) {

                wakeLock?.release()
            }

        } catch (_: Exception) {
        }

        wakeLock = null

        try {

            if (
                wifiLock?.isHeld == true
            ) {

                wifiLock?.release()
            }

        } catch (_: Exception) {
        }

        wifiLock = null
    }

    /*
     * ---------------------------------------------------------
     * HTTP SERVER
     * ---------------------------------------------------------
     */

    private fun startServer() {

        if (server != null) {
            return
        }

        try {

            server =
                MjpegServer(
                    SERVER_PORT
                )

            server?.start(
                NanoHTTPD.SOCKET_READ_TIMEOUT,
                false
            )

        } catch (e: Exception) {

            statusText.text =
                "Server error: ${e.message}"

            e.printStackTrace()
        }
    }

    /*
     * ---------------------------------------------------------
     * CAMERA PERMISSION
     * ---------------------------------------------------------
     */

    private fun checkCameraPermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            /*
             * If the TextureView already exists,
             * start the camera immediately.
             */
            if (
                cameraPreview.isAvailable
            ) {

                startCamera(
                    cameraPreview.surfaceTexture!!
                )
            }

        } else {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA
                ),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    /*
     * ---------------------------------------------------------
     * CAMERA
     * ---------------------------------------------------------
     */

    private fun startCamera(
        surfaceTexture: SurfaceTexture
    ) {

        /*
         * If the camera is already alive, we do NOT reopen it.
         *
         * We only move its preview output to the new surface.
         */
        if (camera != null) {

            switchPreviewSurface(
                surfaceTexture
            )

            return
        }

        try {

            statusText.text =
                "Opening Air2 camera..."

            val openedCamera =
                Camera.open(
                    AIR2_CAMERA_ID
                )

            camera =
                openedCamera

            val params =
                openedCamera.parameters

            /*
             * Preview callback format.
             */
            params.previewFormat =
                ImageFormat.NV21

            val targetWidth = 640
            val targetHeight = 480

            /*
             * Pick supported preview resolution nearest 640x480.
             */
            val bestSize =
                params.supportedPreviewSizes
                    .minByOrNull { size ->

                        abs(
                            (
                                    size.width *
                                            size.height
                                    ) -
                                    (
                                            targetWidth *
                                                    targetHeight
                                            )
                        )
                    }

            if (bestSize != null) {

                previewWidth =
                    bestSize.width

                previewHeight =
                    bestSize.height

                params.setPreviewSize(
                    previewWidth,
                    previewHeight
                )
            }

            openedCamera.parameters =
                params

            /*
             * Connect camera preview to the TextureView.
             */
            openedCamera.setPreviewTexture(
                surfaceTexture
            )

            activePreviewSurface =
                surfaceTexture

            /*
             * Every time the physical camera produces a new
             * preview frame, convert it into JPEG.
             */
            openedCamera.setPreviewCallback {
                    data,
                    activeCamera ->

                try {

                    val cameraParameters =
                        activeCamera.parameters

                    val size =
                        cameraParameters.previewSize

                    val yuvImage =
                        YuvImage(
                            data,
                            ImageFormat.NV21,
                            size.width,
                            size.height,
                            null
                        )

                    val output =
                        ByteArrayOutputStream()

                    yuvImage.compressToJpeg(
                        Rect(
                            0,
                            0,
                            size.width,
                            size.height
                        ),
                        JPEG_QUALITY,
                        output
                    )

                    /*
                     * This is the important part:
                     *
                     * latestJpeg only changes when the CAMERA
                     * actually gives us another frame.
                     */
                    latestJpeg =
                        output.toByteArray()

                    latestFrameTimeMs =
                        SystemClock.elapsedRealtime()

                    val sequence =
                        frameSequence.incrementAndGet()

                    output.close()

                    /*
                     * Update sender UI occasionally.
                     *
                     * This also gives us a visible indication
                     * that the camera callback itself is alive.
                     */
                    if (
                        sequence % 30L == 0L
                    ) {

                        statusText.post {

                            statusText.text =
                                "Air2 Camera Connected\n" +
                                        "Stream Port: $SERVER_PORT\n" +
                                        "Camera Frames: $sequence"
                        }
                    }

                } catch (e: Exception) {

                    e.printStackTrace()
                }
            }

            openedCamera.startPreview()

            statusText.text =
                "Air2 Camera Connected\n" +
                        "Stream Port: $SERVER_PORT"

        } catch (e: Exception) {

            statusText.text =
                "Camera error: ${e.message}"

            e.printStackTrace()

            stopCamera()
        }
    }

    /*
     * Move the already-open camera to another preview surface.
     *
     * This is what lets us survive the TextureView being
     * destroyed/recreated without closing the camera.
     */
    private fun switchPreviewSurface(
        surfaceTexture: SurfaceTexture
    ) {

        val activeCamera =
            camera ?: return

        if (
            activePreviewSurface ===
            surfaceTexture
        ) {

            return
        }

        try {

            try {

                activeCamera.stopPreview()

            } catch (_: Exception) {
            }

            activeCamera.setPreviewTexture(
                surfaceTexture
            )

            activePreviewSurface =
                surfaceTexture

            activeCamera.startPreview()

            /*
             * If we were previously using the off-screen
             * fallback surface and now have a real TextureView
             * again, the fallback can be released.
             */
            val fallback =
                fallbackSurfaceTexture

            if (
                fallback != null &&
                fallback !== surfaceTexture
            ) {

                fallback.release()

                fallbackSurfaceTexture =
                    null
            }

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }

    /*
     * If the visible TextureView disappears, create an
     * off-screen SurfaceTexture and keep Camera preview running.
     *
     * Air2 uses Android 9 / API 28, so the detached
     * SurfaceTexture(boolean) constructor is supported.
     */
    private fun switchToFallbackSurface() {

        val activeCamera =
            camera ?: return

        try {

            val fallback =
                SurfaceTexture(
                    false
                )

            fallback.setDefaultBufferSize(
                previewWidth,
                previewHeight
            )

            /*
             * Release any older fallback first.
             */
            try {

                fallbackSurfaceTexture
                    ?.release()

            } catch (_: Exception) {
            }

            fallbackSurfaceTexture =
                fallback

            try {

                activeCamera.stopPreview()

            } catch (_: Exception) {
            }

            activeCamera.setPreviewTexture(
                fallback
            )

            activePreviewSurface =
                fallback

            activeCamera.startPreview()

        } catch (e: Exception) {

            e.printStackTrace()

            statusText.post {

                statusText.text =
                    "Fallback preview error: ${e.message}"
            }
        }
    }

    private fun stopCamera() {

        val activeCamera =
            camera

        camera = null

        if (activeCamera != null) {

            try {

                activeCamera.setPreviewCallback(
                    null
                )

            } catch (_: Exception) {
            }

            try {

                activeCamera.stopPreview()

            } catch (_: Exception) {
            }

            try {

                activeCamera.release()

            } catch (_: Exception) {
            }
        }

        activePreviewSurface =
            null

        try {

            fallbackSurfaceTexture
                ?.release()

        } catch (_: Exception) {
        }

        fallbackSurfaceTexture =
            null
    }

    /*
     * ---------------------------------------------------------
     * HTTP SERVER
     * ---------------------------------------------------------
     */

    inner class MjpegServer(
        port: Int
    ) : NanoHTTPD(port) {

        override fun serve(
            session: IHTTPSession
        ): Response {

            return when (
                session.uri
            ) {

                /*
                 * Browser debugging page.
                 */
                "/" -> {

                    val html =
                        """
                        <html>
                        <head>
                            <title>CS536 Air2 Stream</title>
                        </head>

                        <body style="background:black;color:white;text-align:center;">

                            <h2>INMO Air2 Live Stream</h2>

                            <img
                                src="/stream.mjpg"
                                style="max-width:100%;height:auto;"
                            />

                        </body>
                        </html>
                        """.trimIndent()

                    newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html",
                        html
                    )
                }

                /*
                 * Pixel uses this endpoint.
                 */
                "/frame.jpg" -> {

                    val frame =
                        latestJpeg

                    val frameTime =
                        latestFrameTimeMs

                    val now =
                        SystemClock.elapsedRealtime()

                    val frameAge =
                        if (
                            frameTime > 0L
                        ) {

                            now - frameTime

                        } else {

                            Long.MAX_VALUE
                        }

                    /*
                     * Important:
                     *
                     * If the camera callback has frozen, do not
                     * continue serving an old image and claiming
                     * everything succeeded.
                     */
                    if (
                        frame == null ||
                        frameAge >
                        MAX_FRAME_AGE_MS
                    ) {

                        val response =
                            newFixedLengthResponse(
                                Response.Status.SERVICE_UNAVAILABLE,
                                "text/plain",
                                "Camera frame stale or unavailable."
                            )

                        response.addHeader(
                            "Cache-Control",
                            "no-store, no-cache, must-revalidate, max-age=0"
                        )

                        response.addHeader(
                            "Pragma",
                            "no-cache"
                        )

                        response

                    } else {

                        val response =
                            newFixedLengthResponse(
                                Response.Status.OK,
                                "image/jpeg",
                                ByteArrayInputStream(
                                    frame
                                ),
                                frame.size.toLong()
                            )

                        /*
                         * Prevent browser/HTTP caching.
                         */
                        response.addHeader(
                            "Cache-Control",
                            "no-store, no-cache, must-revalidate, max-age=0"
                        )

                        response.addHeader(
                            "Pragma",
                            "no-cache"
                        )

                        response.addHeader(
                            "Expires",
                            "0"
                        )

                        /*
                         * Useful for debugging later.
                         */
                        response.addHeader(
                            "X-Frame-Sequence",
                            frameSequence
                                .get()
                                .toString()
                        )

                        response.addHeader(
                            "X-Frame-Age-Ms",
                            frameAge
                                .toString()
                        )

                        response
                    }
                }

                /*
                 * Browser MJPEG stream.
                 */
                "/stream.mjpg" -> {

                    val response =
                        newChunkedResponse(
                            Response.Status.OK,
                            "multipart/x-mixed-replace; boundary=frame",
                            MjpegInputStream()
                        )

                    response.addHeader(
                        "Cache-Control",
                        "no-store, no-cache, must-revalidate, max-age=0"
                    )

                    response.addHeader(
                        "Pragma",
                        "no-cache"
                    )

                    response
                }

                /*
                 * Diagnostic endpoint.
                 *
                 * You can open:
                 *
                 * http://AIR2_IP:8080/health
                 *
                 * and see whether camera frames are still
                 * being generated.
                 */
                "/health" -> {

                    val sequence =
                        frameSequence.get()

                    val frame =
                        latestJpeg

                    val age =
                        if (
                            latestFrameTimeMs > 0L
                        ) {

                            SystemClock.elapsedRealtime() -
                                    latestFrameTimeMs

                        } else {

                            -1L
                        }

                    val text =
                        "camera_frames=$sequence\n" +
                                "frame_age_ms=$age\n" +
                                "jpeg_bytes=${frame?.size ?: 0}\n"

                    newFixedLengthResponse(
                        Response.Status.OK,
                        "text/plain",
                        text
                    )
                }

                else -> {

                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "Not found"
                    )
                }
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * MJPEG INPUT STREAM
     * ---------------------------------------------------------
     */

    inner class MjpegInputStream :
        InputStream() {

        private var currentData =
            ByteArray(0)

        private var position =
            0

        /*
         * Prevent /stream.mjpg from repeatedly sending the exact
         * same JPEG faster than the camera can generate frames.
         */
        private var lastSequence =
            -1L

        private fun loadNextFrame() {

            while (true) {

                val sequence =
                    frameSequence.get()

                val frame =
                    latestJpeg

                val age =
                    if (
                        latestFrameTimeMs > 0L
                    ) {

                        SystemClock.elapsedRealtime() -
                                latestFrameTimeMs

                    } else {

                        Long.MAX_VALUE
                    }

                /*
                 * Only transmit a NEW and FRESH camera frame.
                 */
                if (
                    frame != null &&
                    sequence != lastSequence &&
                    age <= MAX_FRAME_AGE_MS
                ) {

                    lastSequence =
                        sequence

                    val header =
                        (
                                "--frame\r\n" +
                                        "Content-Type: image/jpeg\r\n" +
                                        "Content-Length: ${frame.size}\r\n" +
                                        "\r\n"
                                ).toByteArray()

                    val ending =
                        "\r\n".toByteArray()

                    currentData =
                        header +
                                frame +
                                ending

                    position =
                        0

                    return
                }

                try {

                    Thread.sleep(
                        15
                    )

                } catch (_: InterruptedException) {

                    currentData =
                        ByteArray(0)

                    return
                }
            }
        }

        override fun read(): Int {

            val oneByte =
                ByteArray(1)

            val count =
                read(
                    oneByte,
                    0,
                    1
                )

            if (
                count <= 0
            ) {

                return -1
            }

            return (
                    oneByte[0]
                        .toInt()
                            and 0xFF
                    )
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {

            if (
                length == 0
            ) {

                return 0
            }

            if (
                position >=
                currentData.size
            ) {

                loadNextFrame()
            }

            if (
                currentData.isEmpty()
            ) {

                return -1
            }

            val bytesRemaining =
                currentData.size -
                        position

            val bytesToCopy =
                minOf(
                    length,
                    bytesRemaining
                )

            System.arraycopy(
                currentData,
                position,
                buffer,
                offset,
                bytesToCopy
            )

            position +=
                bytesToCopy

            return bytesToCopy
        }
    }

    /*
     * ---------------------------------------------------------
     * TEXTUREVIEW LIFECYCLE
     * ---------------------------------------------------------
     */

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int
    ) {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            if (
                camera == null
            ) {

                startCamera(
                    surface
                )

            } else {

                /*
                 * Camera may currently be using the fallback
                 * SurfaceTexture. Move it back to the visible
                 * TextureView.
                 */
                switchPreviewSurface(
                    surface
                )
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int
    ) {
    }

    override fun onSurfaceTextureDestroyed(
        surface: SurfaceTexture
    ): Boolean {

        /*
         * OLD VERSION:
         *
         * stopCamera()
         *
         * That is exactly what we do NOT want during
         * untethered experiments.
         *
         * Instead, move the camera to an off-screen preview
         * target so preview callbacks can continue.
         */
        if (
            camera != null &&
            activePreviewSurface === surface
        ) {

            switchToFallbackSurface()
        }

        /*
         * true means Android may release the destroyed
         * TextureView's SurfaceTexture.
         *
         * The camera is no longer using it because we switched
         * to our fallback surface above.
         */
        return true
    }

    override fun onSurfaceTextureUpdated(
        surface: SurfaceTexture
    ) {
    }

    /*
     * ---------------------------------------------------------
     * PERMISSIONS
     * ---------------------------------------------------------
     */

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode ==
            CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] ==
            PackageManager.PERMISSION_GRANTED
        ) {

            if (
                cameraPreview.isAvailable
            ) {

                startCamera(
                    cameraPreview.surfaceTexture!!
                )
            }

        } else {

            statusText.text =
                "Camera permission denied"
        }
    }

    /*
     * ---------------------------------------------------------
     * ACTIVITY LIFECYCLE
     * ---------------------------------------------------------
     */

    override fun onResume() {

        super.onResume()

        /*
         * Do not assume camera is still open.
         *
         * If Android destroyed/recreated the Activity state,
         * restart it when possible.
         */
        if (
            camera == null &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) ==
            PackageManager.PERMISSION_GRANTED &&
            cameraPreview.isAvailable
        ) {

            startCamera(
                cameraPreview.surfaceTexture!!
            )
        }
    }

    override fun onPause() {

        /*
         * IMPORTANT:
         *
         * DO NOT stop the camera here.
         *
         * The sender's job is to keep streaming while the Air2
         * is being worn untethered.
         */
        super.onPause()
    }

    override fun onDestroy() {

        /*
         * Now we actually are done with the sender.
         */
        stopCamera()

        server?.stop()
        server = null

        releaseWakeLocks()

        super.onDestroy()
    }
}