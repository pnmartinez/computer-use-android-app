package android.vnc

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.TextView

class VncCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val statusView = TextView(context)
    private var host: String = ""
    private var port: Int = 0
    private var password: String = ""

    init {
        statusView.text = "VNC viewer inicializado."
        addView(statusView)
    }

    fun setConnectionInfo(host: String, port: Int, password: String) {
        this.host = host
        this.port = port
        this.password = password
        updateStatus("Preparado para conectar a $host:$port")
    }

    fun setHost(host: String) {
        this.host = host
        updateStatus("Host configurado: $host")
    }

    fun setPort(port: Int) {
        this.port = port
        updateStatus("Puerto configurado: $port")
    }

    fun setPassword(password: String) {
        this.password = password
        updateStatus("Contraseña configurada.")
    }

    fun connect() {
        updateStatus("Conexión VNC no implementada en el módulo local.")
    }

    fun startConnection() {
        connect()
    }

    fun disconnect() {
        updateStatus("Conexión VNC detenida.")
    }

    fun shutdown() {
        disconnect()
    }

    private fun updateStatus(message: String) {
        statusView.text = message
    }
}
