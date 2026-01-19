package android.vnc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.sqrt
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class VncCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val enableZoom: Boolean = false
) : FrameLayout(context, attrs) {
    private val imageView = ImageView(context)
    private val statusView = TextView(context)
    private var host: String = ""
    private var port: Int = 0
    private var password: String = ""
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var readerThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private var framebuffer: Bitmap? = null
    private var pixelFormat: PixelFormat? = null

    // Zoom state variables
    private var scaleFactor = 1f
    private val matrix = Matrix()
    private val scaleGestureDetector: ScaleGestureDetector
    
    // Variables for drag functionality
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var currentTranslateX = 0f
    private var currentTranslateY = 0f
    
    // Variables for touch detection
    private var isInPinchGesture = false
    private var touchStartTime = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val tapThreshold = 200L // milliseconds
    private val moveThreshold = 50f // pixels
    
    // Store initial fit-to-screen scale for toggling
    private var fitToScreenScale = 1f
    
    // Zoom levels for tap-to-zoom functionality
    private val zoomLevels = arrayOf(1f, 2f, 4f) // fit-to-screen, 2x, 4x
    private var currentZoomLevelIndex = 0
    
    // Store container dimensions for zoom calculations
    private var containerWidth = 0f
    private var containerHeight = 0f

    init {
        statusView.text = "VNC viewer inicializado."
        statusView.setPadding(24, 16, 24, 16)
        statusView.setBackgroundColor(0x66000000)
        statusView.setTextColor(0xFFFFFFFF.toInt())
        
        // Configure ImageView based on zoom mode
        if (enableZoom) {
            imageView.scaleType = ImageView.ScaleType.MATRIX
            // Initialize scale gesture detector
            scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
            // Set up touch listener for zoom and drag
            imageView.setOnTouchListener { _, event ->
                handleTouchEvent(event)
            }
        } else {
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            // Initialize scale gesture detector (needed for compilation, but won't be used)
            scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {})
            // When zoom is disabled, don't intercept touch events so parent can handle clicks
            imageView.isClickable = false
            imageView.isFocusable = false
        }
        
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val statusParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        statusParams.gravity = Gravity.BOTTOM or Gravity.START
        addView(statusView, statusParams)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (enableZoom && w > 0 && h > 0) {
            containerWidth = w.toFloat()
            containerHeight = h.toFloat()
            // Recalculate fit-to-screen scale when size changes
            framebuffer?.let { 
                // Use a post to ensure the view is fully laid out
                post { centerImage() }
            }
        }
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
        if (host.isBlank() || port == 0) {
            updateStatus("Host o puerto inválidos.")
            return
        }
        if (isRunning.get()) {
            updateStatus("Conexión ya en curso.")
            return
        }
        isRunning.set(true)
        readerThread = Thread { runConnection() }.apply { start() }
    }

    fun startConnection() {
        connect()
    }

    fun disconnect() {
        isRunning.set(false)
        readerThread?.interrupt()
        readerThread = null
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        input = null
        output = null
        socket = null
        updateStatus("Conexión VNC detenida.")
    }

    fun shutdown() {
        disconnect()
    }

    private fun updateStatus(message: String) {
        post { statusView.text = message }
    }
    
    // Zoom functionality methods
    private fun centerImage() {
        val bitmap = framebuffer ?: return
        // Use screen dimensions as fallback if container dimensions not available
        if (containerWidth == 0f || containerHeight == 0f) {
            val displayMetrics = context.resources.displayMetrics
            containerWidth = displayMetrics.widthPixels.toFloat()
            containerHeight = displayMetrics.heightPixels.toFloat()
        }
        if (containerWidth == 0f || containerHeight == 0f) return
        
        // Calculate scale to fit container while maintaining aspect ratio
        val scaleX = containerWidth / bitmap.width.toFloat()
        val scaleY = containerHeight / bitmap.height.toFloat()
        scaleFactor = minOf(scaleX, scaleY)
        fitToScreenScale = scaleFactor // Store the fit-to-screen scale
        currentZoomLevelIndex = 0 // Reset to first zoom level
        
        // Calculate translation to center the image
        currentTranslateX = (containerWidth - bitmap.width * scaleFactor) / 2f
        currentTranslateY = (containerHeight - bitmap.height * scaleFactor) / 2f
        
        updateImageMatrix()
    }
    
    private fun updateImageMatrix() {
        if (!enableZoom) return
        matrix.reset()
        matrix.postScale(scaleFactor, scaleFactor)
        matrix.postTranslate(currentTranslateX, currentTranslateY)
        post { imageView.imageMatrix = matrix }
    }
    
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        if (!enableZoom) return false
        scaleGestureDetector.onTouchEvent(event)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchStartX = event.x
                touchStartY = event.y
                touchStartTime = System.currentTimeMillis()
                isDragging = false
                isInPinchGesture = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && !isInPinchGesture) {
                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY
                    val totalMoveDistance = sqrt(
                        (event.x - touchStartX) * (event.x - touchStartX) + 
                        (event.y - touchStartY) * (event.y - touchStartY)
                    )
                    
                    // Only start dragging if we've moved enough and we're zoomed in
                    if (totalMoveDistance > moveThreshold && scaleFactor > fitToScreenScale) {
                        isDragging = true
                    }
                    
                    if (isDragging && scaleFactor > fitToScreenScale) {
                        val bitmap = framebuffer ?: return true
                        val scaledWidth = bitmap.width * scaleFactor
                        val scaledHeight = bitmap.height * scaleFactor
                        
                        // Calculate new translation
                        var newTranslateX = currentTranslateX + deltaX
                        var newTranslateY = currentTranslateY + deltaY
                        
                        // Apply bounds checking
                        if (scaledWidth > containerWidth) {
                            val minTranslateX = -(scaledWidth - containerWidth)
                            newTranslateX = newTranslateX.coerceIn(minTranslateX, 0f)
                        } else {
                            newTranslateX = (containerWidth - scaledWidth) / 2f
                        }
                        
                        if (scaledHeight > containerHeight) {
                            val minTranslateY = -(scaledHeight - containerHeight)
                            newTranslateY = newTranslateY.coerceIn(minTranslateY, 0f)
                        } else {
                            newTranslateY = (containerHeight - scaledHeight) / 2f
                        }
                        
                        currentTranslateX = newTranslateX
                        currentTranslateY = newTranslateY
                        updateImageMatrix()
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging && !isInPinchGesture) {
                    val touchDuration = System.currentTimeMillis() - touchStartTime
                    val moveDistance = sqrt(
                        (event.x - touchStartX) * (event.x - touchStartX) + 
                        (event.y - touchStartY) * (event.y - touchStartY)
                    )
                    
                    // Detect tap (short duration and small movement)
                    if (touchDuration < tapThreshold && moveDistance < moveThreshold) {
                        handleTap(event.x, event.y)
                    }
                }
                isDragging = false
            }
        }
        return true
    }
    
    private fun handleTap(tapX: Float, tapY: Float) {
        // Cycle through zoom levels on tap
        currentZoomLevelIndex = (currentZoomLevelIndex + 1) % zoomLevels.size
        val targetZoomLevel = zoomLevels[currentZoomLevelIndex]
        
        if (currentZoomLevelIndex == 0) {
            // Return to fit-to-screen
            centerImage()
        } else {
            // Apply zoom level and position based on tap location
            applyZoomLevel(targetZoomLevel, tapX, tapY)
        }
    }
    
    private fun applyZoomLevel(zoomLevel: Float, tapX: Float, tapY: Float) {
        val bitmap = framebuffer ?: return
        if (containerWidth == 0f || containerHeight == 0f) return
        
        // Calculate the target scale (fit-to-screen scale * zoom level)
        val targetScale = fitToScreenScale * zoomLevel
        scaleFactor = targetScale
        
        // Calculate the scaled dimensions
        val scaledWidth = bitmap.width * scaleFactor
        val scaledHeight = bitmap.height * scaleFactor
        
        // Calculate the tap position relative to the current image bounds
        val imageLeft = currentTranslateX
        val imageTop = currentTranslateY
        val imageRight = imageLeft + (bitmap.width * fitToScreenScale)
        val imageBottom = imageTop + (bitmap.height * fitToScreenScale)
        
        // Convert tap coordinates to image coordinates
        val tapRatioX = if (imageRight > imageLeft) {
            ((tapX - imageLeft) / (imageRight - imageLeft)).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val tapRatioY = if (imageBottom > imageTop) {
            ((tapY - imageTop) / (imageBottom - imageTop)).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        
        // Calculate new translation to center the tapped point
        val targetX = tapX - (scaledWidth * tapRatioX)
        val targetY = tapY - (scaledHeight * tapRatioY)
        
        // Apply bounds checking
        if (scaledWidth > containerWidth) {
            val minTranslateX = -(scaledWidth - containerWidth)
            currentTranslateX = targetX.coerceIn(minTranslateX, 0f)
        } else {
            currentTranslateX = (containerWidth - scaledWidth) / 2f
        }
        
        if (scaledHeight > containerHeight) {
            val minTranslateY = -(scaledHeight - containerHeight)
            currentTranslateY = targetY.coerceIn(minTranslateY, 0f)
        } else {
            currentTranslateY = (containerHeight - scaledHeight) / 2f
        }
        
        updateImageMatrix()
    }
    
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isInPinchGesture = true
            return true
        }
        
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val bitmap = framebuffer ?: return false
            if (containerWidth == 0f || containerHeight == 0f) return false
            
            val previousScale = scaleFactor
            scaleFactor *= detector.scaleFactor
            
            // Limit the scale factor
            scaleFactor = scaleFactor.coerceIn(0.1f, 10f)
            
            // Adjust translation to keep the image centered at the focal point
            if (scaleFactor != previousScale) {
                val focusX = detector.focusX
                val focusY = detector.focusY
                
                // Calculate the change in scale
                val scaleChange = scaleFactor / previousScale
                
                // Calculate new dimensions
                val scaledWidth = bitmap.width * scaleFactor
                val scaledHeight = bitmap.height * scaleFactor
                
                // Calculate new translation
                val newTranslateX = focusX - (focusX - currentTranslateX) * scaleChange
                val newTranslateY = focusY - (focusY - currentTranslateY) * scaleChange
                
                // Apply bounds checking for the new translation
                if (scaledWidth > containerWidth) {
                    val minTranslateX = -(scaledWidth - containerWidth)
                    currentTranslateX = newTranslateX.coerceIn(minTranslateX, 0f)
                } else {
                    currentTranslateX = (containerWidth - scaledWidth) / 2
                }
                
                if (scaledHeight > containerHeight) {
                    val minTranslateY = -(scaledHeight - containerHeight)
                    currentTranslateY = newTranslateY.coerceIn(minTranslateY, 0f)
                } else {
                    currentTranslateY = (containerHeight - scaledHeight) / 2
                }
                
                updateImageMatrix()
            }
            return true
        }
        
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isInPinchGesture = false
            // Update fitToScreenScale if we zoomed out below it
            val bitmap = framebuffer ?: return
            if (containerWidth == 0f || containerHeight == 0f) return
            
            val scaleX = containerWidth / bitmap.width.toFloat()
            val scaleY = containerHeight / bitmap.height.toFloat()
            val minScale = minOf(scaleX, scaleY)
            
            if (scaleFactor < minScale) {
                scaleFactor = minScale
                fitToScreenScale = minScale
                centerImage()
            } else if (scaleFactor == minScale) {
                fitToScreenScale = minScale
                currentZoomLevelIndex = 0
            }
        }
    }

    private fun runConnection() {
        try {
            updateStatus("Conectando a $host:$port ...")
            val newSocket = Socket()
            newSocket.connect(InetSocketAddress(host, port), 8000)
            socket = newSocket
            input = DataInputStream(BufferedInputStream(newSocket.getInputStream()))
            output = DataOutputStream(BufferedOutputStream(newSocket.getOutputStream()))

            val serverVersion = ByteArray(12)
            input?.readFully(serverVersion)
            output?.write(serverVersion)
            output?.flush()

            val securityTypesCount = input?.readUnsignedByte() ?: 0
            if (securityTypesCount == 0) {
                val reasonLength = input?.readInt() ?: 0
                val reasonBytes = ByteArray(reasonLength)
                input?.readFully(reasonBytes)
                updateStatus("Servidor rechazó: ${String(reasonBytes)}")
                disconnect()
                return
            }

            val securityTypes = ByteArray(securityTypesCount)
            input?.readFully(securityTypes)
            val selected = selectSecurityType(securityTypes)
            output?.writeByte(selected)
            output?.flush()

            if (selected == SECURITY_VNC_AUTH) {
                val challenge = ByteArray(16)
                input?.readFully(challenge)
                val response = vncAuthResponse(password, challenge)
                output?.write(response)
                output?.flush()
            }

            val securityResult = input?.readInt() ?: 1
            if (securityResult != 0) {
                updateStatus("Autenticación fallida.")
                disconnect()
                return
            }

            output?.writeByte(1)
            output?.flush()

            val width = input?.readUnsignedShort() ?: 0
            val height = input?.readUnsignedShort() ?: 0
            val format = readPixelFormat()
            pixelFormat = format
            val nameLength = input?.readInt() ?: 0
            if (nameLength > 0) {
                val nameBytes = ByteArray(nameLength)
                input?.readFully(nameBytes)
            }

            framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            post { 
                imageView.setImageBitmap(framebuffer)
                // Initialize zoom state when framebuffer is first created (only if zoom is enabled)
                if (enableZoom) {
                    // Initialize dimensions immediately with screen size as fallback
                    val displayMetrics = context.resources.displayMetrics
                    if (containerWidth == 0f || containerHeight == 0f) {
                        containerWidth = displayMetrics.widthPixels.toFloat()
                        containerHeight = displayMetrics.heightPixels.toFloat()
                    }
                    // Ensure matrix is set even if dimensions are not perfect yet
                    // This will make the image visible immediately
                    updateImageMatrix()
                    // Apply proper centering
                    centerImage()
                    // Then update with actual view dimensions when available
                    post {
                        val viewWidth = this@VncCanvasView.width.toFloat()
                        val viewHeight = this@VncCanvasView.height.toFloat()
                        if (viewWidth > 0 && viewHeight > 0 && (viewWidth != containerWidth || viewHeight != containerHeight)) {
                            containerWidth = viewWidth
                            containerHeight = viewHeight
                            centerImage()
                        }
                    }
                } else {
                    // When zoom is disabled, ensure image is visible
                    imageView.invalidate()
                }
            }
            updateStatus("Conectado. ${width}x$height")

            sendSetEncodings()
            sendFramebufferUpdateRequest(false, 0, 0, width, height)

            while (isRunning.get()) {
                val messageType = input?.readUnsignedByte() ?: break
                when (messageType) {
                    0 -> handleFramebufferUpdate()
                    2 -> input?.readUnsignedByte()
                    3 -> handleServerCutText()
                    else -> {
                        updateStatus("Mensaje no soportado: $messageType")
                        disconnect()
                        return
                    }
                }
            }
        } catch (e: Exception) {
            updateStatus("Error VNC: ${e.message}")
            disconnect()
        }
    }

    private fun readPixelFormat(): PixelFormat {
        val bitsPerPixel = input?.readUnsignedByte() ?: 32
        val depth = input?.readUnsignedByte() ?: 24
        val bigEndian = (input?.readUnsignedByte() ?: 0) != 0
        val trueColor = (input?.readUnsignedByte() ?: 1) != 0
        val redMax = input?.readUnsignedShort() ?: 255
        val greenMax = input?.readUnsignedShort() ?: 255
        val blueMax = input?.readUnsignedShort() ?: 255
        val redShift = input?.readUnsignedByte() ?: 16
        val greenShift = input?.readUnsignedByte() ?: 8
        val blueShift = input?.readUnsignedByte() ?: 0
        input?.skipBytes(3)
        return PixelFormat(
            bitsPerPixel,
            depth,
            bigEndian,
            trueColor,
            redMax,
            greenMax,
            blueMax,
            redShift,
            greenShift,
            blueShift
        )
    }

    private fun sendSetEncodings() {
        output?.writeByte(2)
        output?.writeByte(0)
        output?.writeShort(1)
        output?.writeInt(0)
        output?.flush()
    }

    private fun sendFramebufferUpdateRequest(incremental: Boolean, x: Int, y: Int, width: Int, height: Int) {
        output?.writeByte(3)
        output?.writeByte(if (incremental) 1 else 0)
        output?.writeShort(x)
        output?.writeShort(y)
        output?.writeShort(width)
        output?.writeShort(height)
        output?.flush()
    }

    private fun handleFramebufferUpdate() {
        input?.skipBytes(1)
        val numberOfRectangles = input?.readUnsignedShort() ?: 0
        val format = pixelFormat ?: return
        val bitmap = framebuffer ?: return
        
        repeat(numberOfRectangles) {
            val x = input?.readUnsignedShort() ?: 0
            val y = input?.readUnsignedShort() ?: 0
            val width = input?.readUnsignedShort() ?: 0
            val height = input?.readUnsignedShort() ?: 0
            val encoding = input?.readInt() ?: 0
            if (encoding != 0) {
                updateStatus("Encoding no soportado: $encoding")
                disconnect()
                return
            }
            val bytesPerPixel = format.bitsPerPixel / 8
            val dataLength = width * height * bytesPerPixel
            val data = ByteArray(dataLength)
            input?.readFully(data)
            val pixels = IntArray(width * height)
            decodePixels(data, pixels, format, bytesPerPixel)
            bitmap.setPixels(pixels, 0, width, x, y, width, height)
        }
        post { 
            imageView.invalidate()
            // Maintain zoom state during framebuffer updates (only if zoom is enabled)
            if (enableZoom) {
                updateImageMatrix()
            }
        }
        sendFramebufferUpdateRequest(true, 0, 0, bitmap.width, bitmap.height)
    }

    private fun decodePixels(data: ByteArray, pixels: IntArray, format: PixelFormat, bytesPerPixel: Int) {
        val order = if (format.bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val buffer = ByteBuffer.wrap(data).order(order)
        for (i in pixels.indices) {
            val value = when (bytesPerPixel) {
                4 -> buffer.int
                2 -> buffer.short.toInt() and 0xFFFF
                1 -> buffer.get().toInt() and 0xFF
                else -> buffer.int
            }
            val red = ((value shr format.redShift) and format.redMax) * 255 / format.redMax
            val green = ((value shr format.greenShift) and format.greenMax) * 255 / format.greenMax
            val blue = ((value shr format.blueShift) and format.blueMax) * 255 / format.blueMax
            pixels[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun handleServerCutText() {
        input?.skipBytes(3)
        val length = input?.readInt() ?: 0
        if (length > 0) {
            input?.skipBytes(length)
        }
    }

    private fun selectSecurityType(types: ByteArray): Int {
        return when {
            types.contains(SECURITY_NONE.toByte()) -> SECURITY_NONE
            types.contains(SECURITY_VNC_AUTH.toByte()) && password.isNotBlank() -> SECURITY_VNC_AUTH
            else -> SECURITY_NONE
        }
    }

    private fun vncAuthResponse(password: String, challenge: ByteArray): ByteArray {
        val key = ByteArray(8)
        val passBytes = password.toByteArray()
        for (i in 0 until 8) {
            val b = if (i < passBytes.size) passBytes[i] else 0
            key[i] = reverseBits(b)
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }

    private fun reverseBits(value: Byte): Byte {
        var v = value.toInt()
        var result = 0
        for (i in 0 until 8) {
            result = (result shl 1) or (v and 1)
            v = v shr 1
        }
        return result.toByte()
    }

    private data class PixelFormat(
        val bitsPerPixel: Int,
        val depth: Int,
        val bigEndian: Boolean,
        val trueColor: Boolean,
        val redMax: Int,
        val greenMax: Int,
        val blueMax: Int,
        val redShift: Int,
        val greenShift: Int,
        val blueShift: Int
    )

    private companion object {
        const val SECURITY_NONE = 1
        const val SECURITY_VNC_AUTH = 2
    }
}
