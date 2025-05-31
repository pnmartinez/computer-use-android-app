package com.example.myapplication

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Utility class for favorites operations
 */
object FavoritesUtils {

    private const val TAG = "FavoritesUtils"

    /**
     * Retrieves the favorites list from the server
     *
     * @param serverUrl The base URL of the server
     * @param client OkHttpClient instance to use for the request
     * @param limit Optional limit for number of favorites to retrieve
     * @return List of favorite entries
     */
    suspend fun getFavorites(
        serverUrl: String,
        client: OkHttpClient,
        limit: Int? = null
    ): List<FavoriteEntry> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Retrieving favorites from server: $serverUrl")
                
                // Build URL with optional limit parameter
                val url = if (limit != null) {
                    "$serverUrl/favorites?limit=$limit"
                } else {
                    "$serverUrl/favorites"
                }
                
                // Build the request to get favorites
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "Android Voice Client")
                    .build()
                
                try {
                    // Execute the request
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            parseFavoritesResponse(responseBody)
                        } else {
                            Log.e(TAG, "Empty response body from server")
                            emptyList()
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "No error details"
                        Log.e(TAG, "Failed to get favorites: ${response.code}, Error: $errorBody")
                        emptyList()
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Network error fetching favorites: ${e.message}", e)
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching favorites: ${e.message}", e)
                emptyList()
            }
        }
    }
    
    /**
     * Saves a command as a favorite
     *
     * @param serverUrl The base URL of the server
     * @param client OkHttpClient instance to use for the request
     * @param command The command to save
     * @param name Optional name for the favorite
     * @param code Optional code for the favorite
     * @param steps Optional steps for the favorite
     * @param success Optional success status
     * @return Result of the save operation
     */
    suspend fun saveFavorite(
        serverUrl: String,
        client: OkHttpClient,
        command: String,
        name: String? = null,
        code: String? = null,
        steps: List<String>? = null,
        success: Boolean? = null
    ): FavoriteResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Saving favorite: $command")
                
                // Create JSON payload
                val jsonObject = JSONObject().apply {
                    put("command", command)
                    name?.let { put("name", it) }
                    code?.let { put("code", it) }
                    steps?.let { 
                        val stepsArray = JSONArray()
                        steps.forEach { step -> stepsArray.put(step) }
                        put("steps", stepsArray) 
                    }
                    success?.let { put("success", it) }
                }
                
                // Create request body
                val requestBody = jsonObject.toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                
                // Build the request to save favorite
                val request = Request.Builder()
                    .url("$serverUrl/save-favorite")
                    .post(requestBody)
                    .header("User-Agent", "Android Voice Client")
                    .build()
                
                try {
                    // Execute the request
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            parseSaveFavoriteResponse(responseBody)
                        } else {
                            Log.e(TAG, "Empty response body from server")
                            FavoriteResult(false, "Empty response from server")
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "No error details"
                        Log.e(TAG, "Failed to save favorite: ${response.code}, Error: $errorBody")
                        FavoriteResult(false, "HTTP Error: ${response.code} - $errorBody")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Network error saving favorite: ${e.message}", e)
                    FavoriteResult(false, "Network error: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error saving favorite: ${e.message}", e)
                FavoriteResult(false, "Error: ${e.message}")
            }
        }
    }
    
    /**
     * Deletes a favorite
     *
     * @param serverUrl The base URL of the server
     * @param client OkHttpClient instance to use for the request
     * @param scriptId The ID of the script to delete
     * @return Result of the delete operation
     */
    suspend fun deleteFavorite(
        serverUrl: String,
        client: OkHttpClient,
        scriptId: String
    ): FavoriteResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Deleting favorite: $scriptId")
                
                // Build the request to delete favorite
                val request = Request.Builder()
                    .url("$serverUrl/delete-favorite/$scriptId")
                    .delete()
                    .header("User-Agent", "Android Voice Client")
                    .build()
                
                try {
                    // Execute the request
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            parseDeleteFavoriteResponse(responseBody)
                        } else {
                            Log.e(TAG, "Empty response body from server")
                            FavoriteResult(false, "Empty response from server")
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "No error details"
                        Log.e(TAG, "Failed to delete favorite: ${response.code}, Error: $errorBody")
                        FavoriteResult(false, "HTTP Error: ${response.code} - $errorBody")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Network error deleting favorite: ${e.message}", e)
                    FavoriteResult(false, "Network error: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error deleting favorite: ${e.message}", e)
                FavoriteResult(false, "Error: ${e.message}")
            }
        }
    }
    
    /**
     * Runs a favorite
     *
     * @param serverUrl The base URL of the server
     * @param client OkHttpClient instance to use for the request
     * @param scriptId The ID of the script to run
     * @return Result of the run operation
     */
    suspend fun runFavorite(
        serverUrl: String,
        client: OkHttpClient,
        scriptId: String
    ): FavoriteResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Running favorite: $scriptId")
                
                // Create an empty JSON payload (POST with no body)
                val requestBody = "{}".toRequestBody("application/json".toMediaTypeOrNull())
                
                // Build the request to run favorite
                val request = Request.Builder()
                    .url("$serverUrl/run-favorite/$scriptId")
                    .post(requestBody)
                    .header("User-Agent", "Android Voice Client")
                    .build()
                
                try {
                    // Execute the request
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            parseRunFavoriteResponse(responseBody)
                        } else {
                            Log.e(TAG, "Empty response body from server")
                            FavoriteResult(false, "Empty response from server")
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "No error details"
                        Log.e(TAG, "Failed to run favorite: ${response.code}, Error: $errorBody")
                        FavoriteResult(false, "HTTP Error: ${response.code} - $errorBody")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Network error running favorite: ${e.message}", e)
                    FavoriteResult(false, "Network error: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error running favorite: ${e.message}", e)
                FavoriteResult(false, "Error: ${e.message}")
            }
        }
    }
    
    /**
     * Parses the favorites response
     */
    private fun parseFavoritesResponse(responseBody: String): List<FavoriteEntry> {
        return try {
            val jsonObject = JSONObject(responseBody)
            val favoritesList = mutableListOf<FavoriteEntry>()
            
            // Check if the response has a success status
            if (jsonObject.optString("status") == "success" && jsonObject.has("favorites")) {
                // Get the favorites array from the response
                val favoritesArray = jsonObject.getJSONArray("favorites")
                
                // Iterate through each favorite in the array
                for (i in 0 until favoritesArray.length()) {
                    val item = favoritesArray.getJSONObject(i)
                    
                    // Parse steps either as an array or as a string
                    val steps = if (item.has("steps")) {
                        if (item.get("steps") is JSONArray) {
                            parseStepsArray(item.optJSONArray("steps"))
                        } else {
                            // Handle steps as a string (convert to list with a single item if not empty)
                            val stepsStr = item.optString("steps", "")
                            if (stepsStr.isNotEmpty()) listOf(stepsStr) else emptyList()
                        }
                    } else {
                        emptyList()
                    }
                    
                    // Extract scriptId from filepath or use name
                    val scriptPath = item.optString("script_path", "")
                    val scriptId = if (scriptPath.isNotEmpty()) {
                        scriptPath.substringAfterLast("/").substringBeforeLast(".")
                    } else {
                        item.optString("name", "unknown")
                    }
                    
                    favoritesList.add(
                        FavoriteEntry(
                            scriptId = scriptId,
                            name = item.optString("name", ""),
                            command = item.optString("command", ""),
                            timestamp = item.optString("timestamp", ""),
                            originalTimestamp = item.optString("original_timestamp", ""),
                            steps = steps,
                            success = item.optBoolean("success", true)
                        )
                    )
                }
            } else {
                Log.e(TAG, "Invalid response format or error status")
            }
            
            favoritesList
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing favorites", e)
            emptyList()
        }
    }
    
    /**
     * Parses a JSONArray of steps into a list of strings
     */
    private fun parseStepsArray(stepsArray: JSONArray?): List<String> {
        val steps = mutableListOf<String>()
        
        if (stepsArray != null) {
            for (i in 0 until stepsArray.length()) {
                steps.add(stepsArray.optString(i, ""))
            }
        }
        
        return steps
    }
    
    /**
     * Parses the save favorite response
     */
    private fun parseSaveFavoriteResponse(responseBody: String): FavoriteResult {
        return try {
            val jsonObject = JSONObject(responseBody)
            
            val success = jsonObject.optString("status") == "success"
            val message = if (success) {
                "Favorite saved successfully: ${jsonObject.optString("name", "")}"
            } else {
                jsonObject.optString("error", "Unknown error")
            }
            
            FavoriteResult(success, message)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing save favorite response", e)
            FavoriteResult(false, "Error parsing response: ${e.message}")
        }
    }
    
    /**
     * Parses the delete favorite response
     */
    private fun parseDeleteFavoriteResponse(responseBody: String): FavoriteResult {
        return try {
            val jsonObject = JSONObject(responseBody)
            
            val success = jsonObject.optString("status") == "success"
            val message = if (success) {
                jsonObject.optString("message", "Favorite deleted successfully")
            } else {
                jsonObject.optString("error", "Unknown error")
            }
            
            FavoriteResult(success, message)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing delete favorite response", e)
            FavoriteResult(false, "Error parsing response: ${e.message}")
        }
    }
    
    /**
     * Parses the run favorite response
     */
    private fun parseRunFavoriteResponse(responseBody: String): FavoriteResult {
        return try {
            val jsonObject = JSONObject(responseBody)
            
            val success = jsonObject.optString("status") == "success"
            val message = if (success) {
                jsonObject.optString("message", "Favorite executed successfully")
            } else {
                jsonObject.optString("error", "Unknown error")
            }
            
            // Include stdout/stderr in message if available
            val detailedMessage = if (success && jsonObject.has("stdout")) {
                val stdout = jsonObject.optString("stdout", "")
                val stderr = jsonObject.optString("stderr", "")
                
                if (stdout.isNotEmpty() || stderr.isNotEmpty()) {
                    "$message\nOutput: $stdout${if (stderr.isNotEmpty()) "\nErrors: $stderr" else ""}"
                } else {
                    message
                }
            } else {
                message
            }
            
            FavoriteResult(success, detailedMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing run favorite response", e)
            FavoriteResult(false, "Error parsing response: ${e.message}")
        }
    }

    /**
     * Data class for favorite operation results
     */
    data class FavoriteResult(
        val success: Boolean,
        val message: String
    )
} 