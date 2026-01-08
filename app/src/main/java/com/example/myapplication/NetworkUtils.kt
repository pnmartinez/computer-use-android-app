package com.example.myapplication

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit

/**
 * Utility class for network operations including SSL trust configuration
 */
object NetworkUtils {
    
    /**
     * Creates an OkHttpClient configured to trust all certificates
     * WARNING: This is for development only and should be replaced with proper certificate validation in production
     */
    fun createTrustAllClient(
        connectTimeout: Long = 30, 
        readTimeout: Long = 30, 
        writeTimeout: Long = 30
    ): OkHttpClient {
        return createTrustAllClientInternal(connectTimeout, readTimeout, writeTimeout)
    }
    
    /**
     * Creates an OkHttpClient optimized for long polling with extended timeouts
     * The read timeout should be longer than the server's polling timeout (typically 30-60s)
     */
    fun createLongPollingClient(
        connectTimeout: Long = 10,
        readTimeout: Long = 70,  // Longer than server timeout (60s max)
        writeTimeout: Long = 10
    ): OkHttpClient {
        return createTrustAllClientInternal(connectTimeout, readTimeout, writeTimeout)
    }
    
    private fun createTrustAllClientInternal(
        connectTimeout: Long,
        readTimeout: Long,
        writeTimeout: Long
    ): OkHttpClient {
        
        // Create a trust manager that accepts all certificates
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        
        // Install the trust manager
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        
        // Create OkHttpClient with the custom SSL socket factory
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true } // Disable hostname verification
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .build()
    }
} 