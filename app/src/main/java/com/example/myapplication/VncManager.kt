package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.widget.FrameLayout

/**
 * Manages VNC stream connections and UI updates.
 * Extracted from MainActivity to reduce complexity.
 */
class VncManager(
    private val context: Context,
    private val vncStreamContainer: FrameLayout,
    private val onFullscreenRequested: () -> Unit
) {
    private var vncView: android.vnc.VncCanvasView? = null

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)

    val isConnected: Boolean
        get() = vncView != null

    /**
     * Gets current VNC connection info from preferences
     */
    fun getConnectionInfo(): VncConnectionInfo {
        val host = prefs.getString(AudioService.KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP)
            ?: AudioService.DEFAULT_SERVER_IP
        val port = prefs.getInt(VncPreferences.KEY_VNC_PORT, VncPreferences.DEFAULT_VNC_PORT)
        val password = prefs.getString(VncPreferences.KEY_VNC_PASSWORD, "").orEmpty()
        return VncConnectionInfo(host, port, password)
    }

    /**
     * Starts VNC stream in the container (small preview, zoom disabled)
     */
    fun startStream() {
        val info = getConnectionInfo()

        if (vncView == null) {
            vncView = android.vnc.VncCanvasView(context, null, false) // Zoom disabled in small container
            vncStreamContainer.removeAllViews()
            vncStreamContainer.addView(vncView)

            // Add click listener to open fullscreen
            vncStreamContainer.setOnClickListener {
                onFullscreenRequested()
            }
        }

        vncView?.setConnectionInfo(info.host, info.port, info.password)
        vncView?.connect()
    }

    /**
     * Stops VNC stream and cleans up
     */
    fun stopStream() {
        vncView?.disconnect()
        vncView?.shutdown()
        vncView = null
        vncStreamContainer.removeAllViews()
    }

    /**
     * Reconnects VNC stream (stop + start)
     */
    fun reconnect() {
        stopStream()
        startStream()
    }

    data class VncConnectionInfo(
        val host: String,
        val port: Int,
        val password: String
    )
}
