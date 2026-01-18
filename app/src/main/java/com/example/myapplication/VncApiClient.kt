package com.example.myapplication

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class VncInfo(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val pid: Int? = null,
    val host: String? = null,
    val port: Int? = null,
    val display: String? = null,
    @SerializedName("localhost_only")
    val localhostOnly: Boolean? = null
)

data class VncStatusResponse(
    val status: String? = null,
    val vnc: VncInfo? = null
)

data class VncApiResult<T>(
    val data: T? = null,
    val error: String? = null,
    val httpCode: Int? = null
)

class VncApiClient(
    private val baseUrl: String,
    private val client: OkHttpClient = NetworkUtils.createTrustAllClient()
) {
    private val gson = Gson()

    fun fetchHealth(): VncApiResult<VncStatusResponse> {
        return executeRequest("$baseUrl/health", "GET")
    }

    fun fetchStatus(): VncApiResult<VncStatusResponse> {
        return executeRequest("$baseUrl/vnc/status", "GET")
    }

    fun startVnc(): VncApiResult<VncStatusResponse> {
        return executeRequest("$baseUrl/vnc/start", "POST")
    }

    fun stopVnc(): VncApiResult<VncStatusResponse> {
        return executeRequest("$baseUrl/vnc/stop", "POST")
    }

    private fun executeRequest(url: String, method: String): VncApiResult<VncStatusResponse> {
        val requestBuilder = Request.Builder().url(url)
        if (method == "POST") {
            val emptyBody = "".toRequestBody("application/json".toMediaTypeOrNull())
            requestBuilder.post(emptyBody)
        }
        val request = requestBuilder.build()
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return VncApiResult(
                        error = "HTTP ${response.code}",
                        httpCode = response.code
                    )
                }
                val parsed = try {
                    gson.fromJson(responseBody, VncStatusResponse::class.java)
                } catch (e: JsonSyntaxException) {
                    null
                }
                if (parsed == null) {
                    VncApiResult(
                        error = "Respuesta inválida del servidor",
                        httpCode = response.code
                    )
                } else {
                    VncApiResult(data = parsed, httpCode = response.code)
                }
            }
        } catch (e: Exception) {
            VncApiResult(error = e.message ?: "Error de red")
        }
    }
}
