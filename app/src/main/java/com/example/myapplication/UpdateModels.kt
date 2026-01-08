package com.example.myapplication

/**
 * Response from the /pending-updates endpoint
 */
data class PendingUpdatesResponse(
    val status: String,
    val updates: List<ServerUpdate>,
    val has_more: Boolean,
    val timeout: Boolean? = null
)

/**
 * Individual update from the server
 */
data class ServerUpdate(
    val id: String,
    val timestamp: String,
    val type: String,
    val summary: String,
    val changes: List<String>,
    val metadata: Map<String, Any>?
)
