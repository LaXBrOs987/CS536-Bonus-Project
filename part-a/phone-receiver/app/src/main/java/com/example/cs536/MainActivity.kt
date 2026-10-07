package com.example.cs536

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var streamImage: ImageView
    private lateinit var overlayView: OverlayView

    private lateinit var detector:
            ObjectDetectorHelper

    private val executor =
        Executors.newSingleThreadExecutor()

    private val mainHandler =
        Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        ipInput =
            findViewById(R.id.ipInput)

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

        connectButton.setOnClickListener {

            if (!running) {

                running = true

                connectButton.text =
                    "Stop AI Detection"

                statusText.text =
                    "Starting..."

                startDetection()

            } else {

                running = false

                connectButton.text =
                    "Start AI Detection"

                statusText.text =
                    "Stopped"

                overlayView.clear()
            }
        }
    }

    private fun startDetection() {

        val ip =
            ipInput.text.toString().trim()

        executor.execute {

            while (running) {

                try {

                    val bitmap =
                        getFrame(ip)

                    if (bitmap != null) {

                        val result =
                            detector.detect(bitmap)

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
                                "Detected: " +
                                        result.detections().size +
                                        " object(s)"
                        }
                    }

                } catch (e: Exception) {

                    mainHandler.post {

                        statusText.text =
                            "Error: ${e.message}"
                    }
                }

                try {

                    Thread.sleep(200)

                } catch (_: InterruptedException) {

                    break
                }
            }
        }
    }

    private fun getFrame(
        ip: String
    ): Bitmap? {

        var connection:
                HttpURLConnection? = null

        return try {

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

            connection.inputStream.use {
                BitmapFactory.decodeStream(it)
            }

        } finally {

            connection?.disconnect()
        }
    }

    override fun onDestroy() {

        running = false

        detector.close()

        executor.shutdownNow()

        super.onDestroy()
    }
}