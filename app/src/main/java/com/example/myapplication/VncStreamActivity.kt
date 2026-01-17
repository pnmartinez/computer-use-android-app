package com.example.myapplication

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class VncStreamActivity : AppCompatActivity() {
    private lateinit var container: FrameLayout
    private lateinit var statusText: TextView
    private var vncViewInstance: Any? = null
    private var connectionInfo: ConnectionInfo? = null

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
        connectionInfo = ConnectionInfo(host, port, password)

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
            val viewClass = Class.forName("android.vnc.VncCanvasView")
            val view = viewClass.getConstructor(android.content.Context::class.java).newInstance(this)
            vncViewInstance = view
            container.addView(view as android.view.View)

            invokeIfExists(view, "setConnectionInfo", arrayOf(String::class.java, Int::class.java, String::class.java), host, port, password)
            invokeIfExists(view, "setHost", arrayOf(String::class.java), host)
            invokeIfExists(view, "setPort", arrayOf(Int::class.java), port)
            if (password.isNotBlank()) {
                invokeIfExists(view, "setPassword", arrayOf(String::class.java), password)
            }

            val started = invokeIfExists(view, "connect", emptyArray()) ||
                invokeIfExists(view, "startConnection", emptyArray())

            if (!started) {
                statusText.text = getString(R.string.vnc_stream_connected)
            }
        } catch (e: ClassNotFoundException) {
            showMissingViewerDialog()
        } catch (e: Exception) {
            showError(getString(R.string.vnc_stream_failed, e.message ?: "Error"))
        }
    }

    private fun shutdownVnc() {
        vncViewInstance?.let { view ->
            invokeIfExists(view, "shutdown", emptyArray())
            invokeIfExists(view, "disconnect", emptyArray())
        }
        vncViewInstance = null
    }

    private fun invokeIfExists(
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any
    ): Boolean {
        return try {
            val method = target.javaClass.getMethod(methodName, *parameterTypes)
            method.invoke(target, *args)
            true
        } catch (e: NoSuchMethodException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun showError(message: String) {
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showMissingViewerDialog() {
        statusText.text = getString(R.string.vnc_stream_missing_library)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vnc_stream_missing_title)
            .setMessage(R.string.vnc_stream_missing_body)
            .setPositiveButton(R.string.vnc_stream_open_external) { _, _ ->
                openExternalVncClient()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openExternalVncClient() {
        val info = connectionInfo ?: return
        val uri = if (info.password.isNotBlank()) {
            android.net.Uri.parse("vnc://${info.password}@${info.host}:${info.port}")
        } else {
            android.net.Uri.parse("vnc://${info.host}:${info.port}")
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            showError(getString(R.string.vnc_client_missing))
        }
    }

    private data class ConnectionInfo(
        val host: String,
        val port: Int,
        val password: String
    )

    companion object {
        const val EXTRA_HOST = "extra_vnc_host"
        const val EXTRA_PORT = "extra_vnc_port"
        const val EXTRA_PASSWORD = "extra_vnc_password"
        private const val DEFAULT_VNC_PORT = 5901
    }
}
