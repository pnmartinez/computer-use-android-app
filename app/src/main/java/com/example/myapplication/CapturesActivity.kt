package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.myapplication.AudioService.Companion.KEY_SERVER_IP
import com.example.myapplication.AudioService.Companion.KEY_SERVER_PORT
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CapturesActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var loadingProgress: ProgressBar

    private val okHttpClient = OkHttpClient()
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val bitmapCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024).toInt() / 8) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    private val captureAdapter = CapturesAdapter(::loadCaptureInto)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captures)

        toolbar = findViewById(R.id.capturesToolbar)
        swipeRefresh = findViewById(R.id.capturesSwipeRefresh)
        recyclerView = findViewById(R.id.capturesRecyclerView)
        emptyText = findViewById(R.id.capturesEmptyText)
        loadingProgress = findViewById(R.id.capturesLoadingProgress)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = captureAdapter

        swipeRefresh.setOnRefreshListener { fetchCaptures() }

        fetchCaptures()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun fetchCaptures() {
        showLoadingState()
        swipeRefresh.isRefreshing = true
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { requestCaptures() }
            }
            swipeRefresh.isRefreshing = false
            result.onSuccess { items ->
                updateCaptureList(items)
            }.onFailure { error ->
                showError(error)
            }
        }
    }

    private fun requestCaptures(): List<ScreenshotItem> {
        val serverUrl = getServerUrl()
        val request = Request.Builder()
            .url("$serverUrl/screenshots/latest?limit=10")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val responseBody = response.body?.string().orEmpty()
            if (responseBody.isBlank()) {
                return emptyList()
            }

            val json = JSONObject(responseBody)
            if (!json.has("screenshots")) {
                return emptyList()
            }

            val screenshotsArray = json.getJSONArray("screenshots")
            val items = mutableListOf<ScreenshotItem>()
            for (index in 0 until screenshotsArray.length()) {
                val entry = screenshotsArray.get(index)
                when (entry) {
                    is String -> {
                        val parsedMillis = parseTimestampMillis(entry)
                        items.add(createItem(entry, parsedMillis, index))
                    }
                    is JSONObject -> {
                        val filename = entry.optString("filename", entry.optString("name", ""))
                        if (filename.isBlank()) {
                            continue
                        }
                        val parsedMillis = parseTimestampMillis(filename)
                        val timestampMillis = entry.optLong("timestamp", 0L).takeIf { it > 0L }
                            ?.let { normalizeTimestamp(it) }
                            ?: parsedMillis
                        items.add(createItem(filename, timestampMillis, index))
                    }
                }
            }
            return items.sortedWith(
                compareByDescending<ScreenshotItem> { it.timestampMillis ?: Long.MIN_VALUE }
                    .thenBy { it.originalIndex }
            )
        }
    }

    private fun createItem(filename: String, timestampMillis: Long?, index: Int): ScreenshotItem {
        val displayTimestamp = timestampMillis?.let { formatTimestamp(it) }
            ?: getString(R.string.timestamp_unavailable)
        return ScreenshotItem(filename, timestampMillis, displayTimestamp, index)
    }

    private fun updateCaptureList(items: List<ScreenshotItem>) {
        loadingProgress.visibility = View.GONE
        emptyText.text = getString(R.string.captures_empty)
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        captureAdapter.submit(items)
    }

    private fun showLoadingState() {
        loadingProgress.visibility = View.VISIBLE
        emptyText.text = getString(R.string.captures_loading)
        emptyText.visibility = View.VISIBLE
    }

    private fun showError(error: Throwable) {
        loadingProgress.visibility = View.GONE
        emptyText.visibility = View.VISIBLE
        emptyText.text = getString(R.string.captures_empty)
        val message = error.message ?: getString(R.string.error_generic, \"\")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun loadCaptureInto(item: ScreenshotItem, imageView: ImageView, filenameView: TextView, timestampView: TextView) {
        filenameView.text = item.filename
        timestampView.text = item.displayTimestamp
        imageView.contentDescription = getString(R.string.capture_image_description, item.displayTimestamp)
        imageView.tag = item.filename
        imageView.setImageBitmap(null)

        val cached = bitmapCache.get(item.filename)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        uiScope.launch {
            val bitmapResult = withContext(Dispatchers.IO) {
                runCatching { fetchBitmap(item.filename) }
            }
            val bitmap = bitmapResult.getOrNull() ?: return@launch
            bitmapCache.put(item.filename, bitmap)
            if (imageView.tag == item.filename) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetchBitmap(filename: String): Bitmap {
        val serverUrl = getServerUrl()
        val timestamp = System.currentTimeMillis()
        val imageUrl = "$serverUrl/screenshots/$filename?t=$timestamp"
        val request = Request.Builder().url(imageUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val bytes = response.body?.bytes() ?: throw IllegalStateException("Empty body")
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val serverIp = prefs.getString(KEY_SERVER_IP, AudioService.DEFAULT_SERVER_IP)
            ?: AudioService.DEFAULT_SERVER_IP
        val serverPort = prefs.getInt(KEY_SERVER_PORT, AudioService.DEFAULT_SERVER_PORT)
        return "https://$serverIp:$serverPort"
    }

    private fun normalizeTimestamp(value: Long): Long {
        return if (value > 1000000000000L) value else value * 1000
    }

    private fun formatTimestamp(timestampMillis: Long): String {
        val instant = Instant.ofEpochMilli(timestampMillis)
        val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    private fun parseTimestampMillis(filename: String): Long? {
        val match = FILENAME_TIMESTAMP_REGEX.find(filename) ?: return null
        val (year, month, day, hour, minute, second) = match.destructured
        val dateTime = LocalDateTime.of(
            year.toInt(),
            month.toInt(),
            day.toInt(),
            hour.toInt(),
            minute.toInt(),
            second.toInt()
        )
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private class CapturesAdapter(
        private val onBindImage: (ScreenshotItem, ImageView, TextView, TextView) -> Unit
    ) : RecyclerView.Adapter<CapturesAdapter.CaptureViewHolder>() {

        private var items: List<ScreenshotItem> = emptyList()

        fun submit(newItems: List<ScreenshotItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CaptureViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_capture, parent, false)
            return CaptureViewHolder(view)
        }

        override fun onBindViewHolder(holder: CaptureViewHolder, position: Int) {
            val item = items[position]
            onBindImage(item, holder.imageView, holder.filenameView, holder.timestampView)
        }

        override fun getItemCount(): Int = items.size

        class CaptureViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(R.id.captureImageView)
            val timestampView: TextView = itemView.findViewById(R.id.captureTimestamp)
            val filenameView: TextView = itemView.findViewById(R.id.captureFilename)
        }
    }

    private data class ScreenshotItem(
        val filename: String,
        val timestampMillis: Long?,
        val displayTimestamp: String,
        val originalIndex: Int
    )

    companion object {
        private val FILENAME_TIMESTAMP_REGEX = Regex("(\\d{4})[-_]?([01]\\d)[-_]?([0-3]\\d)[T_ -]?([0-2]\\d)[-_:]?([0-5]\\d)[-_:]?([0-5]\\d)")
    }
}
