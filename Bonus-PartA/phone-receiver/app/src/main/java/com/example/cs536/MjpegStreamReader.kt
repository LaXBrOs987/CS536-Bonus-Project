package com.example.cs536

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MjpegStreamReader(
    private val streamUrl: String
) {

    private var connection: HttpURLConnection? = null

    private var inputStream: BufferedInputStream? = null

    /*
     * Open ONE HTTP connection to the Air2 MJPEG stream.
     *
     * Unlike Part C, this connection remains open
     * while many JPEG frames are received.
     */
    @Throws(IOException::class)
    fun connect() {

        close()

        val url =
            URL(streamUrl)

        val newConnection =
            url.openConnection()
                    as HttpURLConnection

        newConnection.requestMethod = "GET"

        /*
         * Keep the same basic timeout scale that
         * was used during Part C.
         */
        newConnection.connectTimeout = 2000
        newConnection.readTimeout = 2000

        newConnection.useCaches = false

        newConnection.setRequestProperty(
            "Connection",
            "keep-alive"
        )

        newConnection.connect()

        if (
            newConnection.responseCode !=
            HttpURLConnection.HTTP_OK
        ) {

            val responseCode =
                newConnection.responseCode

            newConnection.disconnect()

            throw IOException(
                "HTTP $responseCode"
            )
        }

        connection =
            newConnection

        inputStream =
            BufferedInputStream(
                newConnection.inputStream,
                64 * 1024
            )
    }

    /*
     * Read ONE JPEG image from the existing
     * multipart MJPEG connection.
     *
     * Expected stream format:
     *
     * --frame
     * Content-Type: image/jpeg
     * Content-Length: XXXXX
     *
     * <JPEG bytes>
     *
     * --frame
     * ...
     */
    @Throws(IOException::class)
    fun readFrame(): ByteArray {

        val input =
            inputStream
                ?: throw IOException(
                    "MJPEG stream is not connected"
                )

        /*
         * Find the next multipart boundary.
         */
        var line =
            readLine(input)

        while (
            line != null &&
            !line.startsWith("--")
        ) {

            line =
                readLine(input)
        }

        if (line == null) {

            throw EOFException(
                "MJPEG stream ended before frame boundary"
            )
        }

        /*
         * Read multipart headers.
         */
        var contentLength = -1

        while (true) {

            val headerLine =
                readLine(input)
                    ?: throw EOFException(
                        "MJPEG stream ended while reading headers"
                    )

            /*
             * Empty line separates headers
             * from JPEG body.
             */
            if (headerLine.isEmpty()) {
                break
            }

            val separator =
                headerLine.indexOf(':')

            if (separator > 0) {

                val headerName =
                    headerLine
                        .substring(
                            0,
                            separator
                        )
                        .trim()

                val headerValue =
                    headerLine
                        .substring(
                            separator + 1
                        )
                        .trim()

                if (
                    headerName.equals(
                        "Content-Length",
                        ignoreCase = true
                    )
                ) {

                    contentLength =
                        headerValue.toIntOrNull()
                            ?: -1
                }
            }
        }

        if (contentLength <= 0) {

            throw IOException(
                "MJPEG frame missing Content-Length header"
            )
        }

        /*
         * Protect against a corrupt header allocating
         * an unreasonable amount of memory.
         *
         * 10 MB is far larger than our normal
         * 640x480 JPEG frame.
         */
        if (contentLength > 10 * 1024 * 1024) {

            throw IOException(
                "Invalid MJPEG frame size: $contentLength bytes"
            )
        }

        val imageBytes =
            ByteArray(contentLength)

        readFully(
            input,
            imageBytes
        )

        return imageBytes
    }

    /*
     * Read exactly buffer.size bytes.
     */
    @Throws(IOException::class)
    private fun readFully(
        input: BufferedInputStream,
        buffer: ByteArray
    ) {

        var offset = 0

        while (offset < buffer.size) {

            val bytesRead =
                input.read(
                    buffer,
                    offset,
                    buffer.size - offset
                )

            if (bytesRead < 0) {

                throw EOFException(
                    "MJPEG stream ended during JPEG frame"
                )
            }

            offset += bytesRead
        }
    }

    /*
     * Small HTTP/MJPEG line reader.
     *
     * Reads until \n and removes \r.
     */
    @Throws(IOException::class)
    private fun readLine(
        input: BufferedInputStream
    ): String? {

        val output =
            ByteArrayOutputStream()

        while (true) {

            val value =
                input.read()

            if (value == -1) {

                if (output.size() == 0) {
                    return null
                }

                break
            }

            if (value == '\n'.code) {
                break
            }

            if (value != '\r'.code) {

                output.write(value)
            }

            /*
             * Multipart headers should only be
             * tens/hundreds of bytes.
             *
             * This protects against malformed streams.
             */
            if (output.size() > 8192) {

                throw IOException(
                    "MJPEG header line too long"
                )
            }
        }

        return output.toString(
            Charsets.UTF_8.name()
        )
    }

    /*
     * Closing the connection also interrupts
     * a blocking stream read.
     */
    fun close() {

        try {
            inputStream?.close()
        } catch (_: Exception) {
        }

        inputStream = null

        try {
            connection?.disconnect()
        } catch (_: Exception) {
        }

        connection = null
    }
}