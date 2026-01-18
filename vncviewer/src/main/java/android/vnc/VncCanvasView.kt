package android.vnc

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
    attrs: AttributeSet? = null
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

    init {
        statusView.text = "VNC viewer inicializado."
        statusView.setPadding(24, 16, 24, 16)
        statusView.setBackgroundColor(0x66000000)
        statusView.setTextColor(0xFFFFFFFF.toInt())
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val statusParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        statusParams.gravity = Gravity.BOTTOM or Gravity.START
        addView(statusView, statusParams)
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
            post { imageView.setImageBitmap(framebuffer) }
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
        post { imageView.invalidate() }
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
            types.contains(SECURITY_NONE) -> SECURITY_NONE
            types.contains(SECURITY_VNC_AUTH) && password.isNotBlank() -> SECURITY_VNC_AUTH
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
