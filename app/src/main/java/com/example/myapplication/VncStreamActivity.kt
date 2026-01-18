package com.example.myapplication

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class VncStreamActivity : AppCompatActivity() {
    private lateinit var container: FrameLayout
    private lateinit var statusText: TextView
    private var vncViewInstance: android.vnc.VncCanvasView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vnc_stream)

        findViewById<MaterialToolbar>(R.id.vncStreamToolbar).setNavigationOnClickListener {
            finish()
        }

        container = findViewById(R.id.vncStreamContainer)
        statusText = findViewById(R.id.vncStreamStatus)

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_VNC_PORT)
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        if (host.isBlank()) {
            showError(getString(R.string.vnc_stream_missing_host))
            return
        }

        connectVnc(host, port, password)
    }


    override fun onDestroy() {
        super.onDestroy()
        shutdownVnc()
    }

    private fun connectVnc(host: String, port: Int, password: String) {
        statusText.text = getString(R.string.vnc_stream_connecting, host, port)
        try {
            val view = android.vnc.VncCanvasView(this)
            vncViewInstance = view
            container.addView(view)
            view.setConnectionInfo(host, port, password)
            view.setHost(host)
            view.setPort(port)
            if (password.isNotBlank()) {
                view.setPassword(password)
            }
            view.connect()
            statusText.text = getString(R.string.vnc_stream_connected)
        } catch (e: Exception) {
            showError(getString(R.string.vnc_stream_failed, e.message ?: "Error"))
        }
    }

    private fun shutdownVnc() {
        vncViewInstance?.let { view ->
            view.shutdown()
            view.disconnect()
        }
        vncViewInstance = null
    }

    private fun showError(message: String) {
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }


    companion object {
        const val EXTRA_HOST = "extra_vnc_host"
        const val EXTRA_PORT = "extra_vnc_port"
        const val EXTRA_PASSWORD = "extra_vnc_password"
        private const val DEFAULT_VNC_PORT = 5901
    }
}
