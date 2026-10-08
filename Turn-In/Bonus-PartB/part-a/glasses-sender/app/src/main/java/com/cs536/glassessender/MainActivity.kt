package com.cs536.glassessender

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Bundle
import android.view.TextureView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs

class MainActivity : AppCompatActivity(),
    TextureView.SurfaceTextureListener {

    private lateinit var cameraPreview: TextureView
    private lateinit var statusText: TextView

    private var camera: Camera? = null
    private var server: MjpegServer? = null

    @Volatile
    private var latestJpeg: ByteArray? = null

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
        private const val AIR2_CAMERA_ID = 0

        private const val SERVER_PORT = 8080
        private const val JPEG_QUALITY = 70
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.cameraPreview)
        statusText = findViewById(R.id.statusText)

        cameraPreview.surfaceTextureListener = this

        startServer()
        checkCameraPermission()
    }

    private fun startServer() {
        try {
            server = MjpegServer(SERVER_PORT)

            server?.start(
                NanoHTTPD.SOCKET_READ_TIMEOUT,
                false
            )

        } catch (e: Exception) {
            statusText.text = "Server error: ${e.message}"
            e.printStackTrace()
        }
    }

    private fun checkCameraPermission() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (cameraPreview.isAvailable) {
                startCamera(cameraPreview.surfaceTexture!!)
            }

        } else {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        try {

            statusText.text = "Opening Air2 camera..."

            camera = Camera.open(AIR2_CAMERA_ID)

            camera?.apply {

                val params = parameters

                params.previewFormat = ImageFormat.NV21

                val targetWidth = 640
                val targetHeight = 480

                val bestSize =
                    params.supportedPreviewSizes.minByOrNull { size ->
                        abs(
                            (size.width * size.height) -
                                    (targetWidth * targetHeight)
                        )
                    }

                if (bestSize != null) {
                    params.setPreviewSize(
                        bestSize.width,
                        bestSize.height
                    )
                }

                parameters = params

                setPreviewTexture(surfaceTexture)

                setPreviewCallback { data, activeCamera ->

                    try {

                        val cameraParameters =
                            activeCamera.parameters

                        val size =
                            cameraParameters.previewSize

                        val yuvImage = YuvImage(
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

                        latestJpeg =
                            output.toByteArray()

                        output.close()

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                startPreview()
            }

            statusText.text =
                "Air2 Camera Connected\nStream Port: $SERVER_PORT"

        } catch (e: Exception) {

            statusText.text =
                "Camera error: ${e.message}"

            e.printStackTrace()
        }
    }

    private fun stopCamera() {

        camera?.apply {

            setPreviewCallback(null)

            try {
                stopPreview()
            } catch (_: Exception) {
            }

            release()
        }

        camera = null
    }

    inner class MjpegServer(
        port: Int
    ) : NanoHTTPD(port) {

        override fun serve(
            session: IHTTPSession
        ): Response {

            return when (session.uri) {

                "/" -> {

                    val html = """
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

                "/frame.jpg" -> {

                    val frame = latestJpeg

                    if (frame == null) {

                        newFixedLengthResponse(
                            Response.Status.SERVICE_UNAVAILABLE,
                            "text/plain",
                            "Camera frame not available yet."
                        )

                    } else {

                        newFixedLengthResponse(
                            Response.Status.OK,
                            "image/jpeg",
                            ByteArrayInputStream(frame),
                            frame.size.toLong()
                        )
                    }
                }

                "/stream.mjpg" -> {

                    val response =
                        newChunkedResponse(
                            Response.Status.OK,
                            "multipart/x-mixed-replace; boundary=frame",
                            MjpegInputStream()
                        )

                    response.addHeader(
                        "Cache-Control",
                        "no-cache"
                    )

                    response.addHeader(
                        "Pragma",
                        "no-cache"
                    )

                    response
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

    inner class MjpegInputStream : InputStream() {

        private var currentData =
            ByteArray(0)

        private var position = 0

        private fun loadNextFrame() {

            while (true) {

                val frame = latestJpeg

                if (frame != null) {

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
                        header + frame + ending

                    position = 0

                    return
                }

                try {

                    Thread.sleep(30)

                } catch (_: InterruptedException) {

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

            if (count <= 0) {
                return -1
            }

            return oneByte[0].toInt() and 0xFF
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {

            if (length == 0) {
                return 0
            }

            if (position >= currentData.size) {
                loadNextFrame()
            }

            if (currentData.isEmpty()) {
                return -1
            }

            val bytesRemaining =
                currentData.size - position

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

            position += bytesToCopy

            return bytesToCopy
        }
    }

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int
    ) {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            startCamera(surface)
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

        stopCamera()

        return true
    }

    override fun onSurfaceTextureUpdated(
        surface: SurfaceTexture
    ) {
    }

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
            requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] ==
            PackageManager.PERMISSION_GRANTED
        ) {

            if (cameraPreview.isAvailable) {
                startCamera(
                    cameraPreview.surfaceTexture!!
                )
            }

        } else {

            statusText.text =
                "Camera permission denied"
        }
    }

    override fun onPause() {
        super.onPause()

        stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()

        server?.stop()
        server = null
    }
}