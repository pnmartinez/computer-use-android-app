package com.example.myapplication

/**
 * Data class for favorite entries
 */
data class FavoriteEntry(
    val scriptId: String,
    val name: String,
    val command: String,
    val timestamp: String,
    val originalTimestamp: String,
    val steps: List<String>,
    val success: Boolean
) 