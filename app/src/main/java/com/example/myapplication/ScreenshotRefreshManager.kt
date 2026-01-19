package com.example.myapplication

import android.os.Handler
import android.os.Looper

/**
 * Manages auto-refresh timer for screenshots.
 * Extracted from MainActivity to reduce complexity.
 */
class ScreenshotRefreshManager(
    private val onRefreshTriggered: () -> Unit
) {
    private var refreshHandler: Handler? = null
    private var refreshRunnable: Runnable? = null

    var refreshPeriodMs: Long = DEFAULT_REFRESH_PERIOD
        private set

    var isEnabled: Boolean = true
        private set

    /**
     * Starts auto-refresh with the current period
     */
    fun start() {
        stop()

        if (!isEnabled) return

        refreshHandler = Handler(Looper.getMainLooper())
        refreshRunnable = object : Runnable {
            override fun run() {
                onRefreshTriggered()
                refreshHandler?.postDelayed(this, refreshPeriodMs)
            }
        }

        refreshHandler?.postDelayed(refreshRunnable!!, refreshPeriodMs)
    }

    /**
     * Stops auto-refresh
     */
    fun stop() {
        refreshRunnable?.let { runnable ->
            refreshHandler?.removeCallbacks(runnable)
        }
        refreshRunnable = null
    }

    /**
     * Updates the refresh period and restarts if enabled
     */
    fun updatePeriod(periodMs: Long) {
        refreshPeriodMs = periodMs
        isEnabled = true
        start()
    }

    /**
     * Disables auto-refresh
     */
    fun disable() {
        isEnabled = false
        stop()
    }

    /**
     * Enables auto-refresh with current period
     */
    fun enable() {
        isEnabled = true
        start()
    }

    /**
     * Formats the refresh period for display
     */
    fun formatPeriod(): String {
        return when (refreshPeriodMs) {
            5000L -> "5s"
            10000L -> "10s"
            30000L -> "30s"
            60000L -> "60s"
            else -> "${refreshPeriodMs / 1000}s"
        }
    }

    companion object {
        const val DEFAULT_REFRESH_PERIOD = 30000L // 30 seconds
        const val PERIOD_5S = 5000L
        const val PERIOD_10S = 10000L
        const val PERIOD_30S = 30000L
        const val PERIOD_60S = 60000L
    }
}
