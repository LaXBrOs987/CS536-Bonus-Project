package com.example.cs536

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var streamWebView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        ipInput =
            findViewById(R.id.ipInput)

        connectButton =
            findViewById(R.id.connectButton)

        statusText =
            findViewById(R.id.statusText)

        streamWebView =
            findViewById(R.id.streamWebView)

        streamWebView.settings.javaScriptEnabled = true

        streamWebView.webViewClient =
            object : WebViewClient() {

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(view, url)

                    statusText.text =
                        "Connected to Air2"
                }
            }

        connectButton.setOnClickListener {

            val ip =
                ipInput.text
                    .toString()
                    .trim()

            if (ip.isEmpty()) {

                statusText.text =
                    "Enter Air2 IP address"

                return@setOnClickListener
            }

            val streamUrl =
                "http://$ip:8080"

            statusText.text =
                "Connecting to $ip..."

            streamWebView.loadUrl(
                streamUrl
            )
        }
    }

    override fun onDestroy() {

        streamWebView.destroy()

        super.onDestroy()
    }
}