package com.example.myapplication

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Utility class for command history operations
 */
object CommandHistoryUtils {

    /**
     * Retrieves the command history from the server
     *
     * @param serverUrl The base URL of the server
     * @param client OkHttpClient instance to use for the request
     * @return List of command history entries
     */
    suspend fun getCommandHistory(serverUrl: String, client: OkHttpClient): List<CommandHistoryEntry> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("CommandHistory", "Retrieving command history from server: $serverUrl")
                
                // Build the request to get command history
                val request = Request.Builder()
                    .url("$serverUrl/command-history")
                    .get()
                    .header("User-Agent", "Android Voice Client")
                    .build()
                
                try {
                    // Execute the request
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            parseCommandHistoryResponse(responseBody)
                        } else {
                            Log.e("CommandHistory", "Empty response body from server")
                            emptyList()
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "No error details"
                        Log.e("CommandHistory", "Failed to get command history: ${response.code}, Error: $errorBody")
                        emptyList()
                    }
                } catch (e: IOException) {
                    Log.e("CommandHistory", "Network error fetching command history: ${e.message}", e)
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e("CommandHistory", "Unexpected error fetching command history: ${e.message}", e)
                emptyList()
            }
        }
    }
    
    /**
     * Sends a text command directly to the server's /command endpoint
     *
     * @param serverUrl The base URL of the server
     * @param client OkHttpClient instance to use for the request
     * @param commandText The command text to send
     * @param captureScreenshot Whether to capture a screenshot (default: true)
     * @return Result of the command execution
     */
    suspend fun sendTextCommand(
        serverUrl: String, 
        client: OkHttpClient, 
        commandText: String,
        captureScreenshot: Boolean = true
    ): CommandResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("CommandHistory", "Sending command to server: $commandText")
                
                // Create JSON payload
                val jsonObject = JSONObject().apply {
                    put("command", commandText)
                    put("capture_screenshot", captureScreenshot)
                }
                
                // Create request body
                val requestBody = jsonObject.toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                
                // Build the request to send command
                val request = Request.Builder()
                    .url("$serverUrl/command")
                    .post(requestBody)
                    .header("User-Agent", "Android Voice Client")
                    .build()
                
                try {
                    // Execute the request
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            Log.d("CommandHistory", "Command response: $responseBody")
                            parseCommandResponse(responseBody)
                        } else {
                            Log.e("CommandHistory", "Empty response body from server")
                            CommandResult(false, "Empty response from server")
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "No error details"
                        Log.e("CommandHistory", "Failed to execute command: ${response.code}, Error: $errorBody")
                        CommandResult(false, "HTTP Error: ${response.code} - $errorBody")
                    }
                } catch (e: IOException) {
                    Log.e("CommandHistory", "Network error sending command: ${e.message}", e)
                    CommandResult(false, "Network error: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e("CommandHistory", "Unexpected error sending command: ${e.message}", e)
                CommandResult(false, "Error: ${e.message}")
            }
        }
    }
    
    /**
     * Parses the response from the command endpoint
     */
    private fun parseCommandResponse(responseBody: String): CommandResult {
        return try {
            val jsonObject = JSONObject(responseBody)
            
            val success = jsonObject.optString("status") == "success"
            val message = if (success) {
                jsonObject.optString("result", "Command executed successfully")
            } else {
                jsonObject.optString("error", "Unknown error")
            }
            
            CommandResult(success, message)
        } catch (e: Exception) {
            Log.e("CommandHistory", "Error parsing command response", e)
            CommandResult(false, "Error parsing response: ${e.message}")
        }
    }
    
    /**
     * Parses the JSON response from the server
     */
    private fun parseCommandHistoryResponse(responseBody: String): List<CommandHistoryEntry> {
        return try {
            // Log the raw response for debugging
            Log.d("CommandHistory", "Raw response: $responseBody")
            
            val jsonObject = JSONObject(responseBody)
            val commandList = mutableListOf<CommandHistoryEntry>()
            
            // Check if the response has a success status
            if (jsonObject.optString("status") == "success" && jsonObject.has("history")) {
                // Get the history array from the response
                val historyArray = jsonObject.getJSONArray("history")
                
                // Iterate through each command in the history array
                for (i in 0 until historyArray.length()) {
                    val item = historyArray.getJSONObject(i)
                    
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
                    
                    commandList.add(
                        CommandHistoryEntry(
                            timestamp = item.optString("timestamp", ""),
                            command = item.optString("command", ""),
                            steps = steps,
                            code = item.optString("code", ""),
                            success = item.optBoolean("success", false)
                        )
                    )
                }
                
                // Sort the command list by timestamp in descending order (most recent first)
                commandList.sortByDescending { it.timestamp }
            } else {
                Log.e("CommandHistory", "Invalid response format or error status")
            }
            
            commandList
        } catch (e: Exception) {
            Log.e("CommandHistory", "Error parsing command history", e)
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
     * Data class for command history entries
     */
    data class CommandHistoryEntry(
        val timestamp: String,
        val command: String,
        val steps: List<String>,
        val code: String,
        val success: Boolean
    )
    
    /**
     * Data class for command execution results
     */
    data class CommandResult(
        val success: Boolean,
        val message: String
    )
} 