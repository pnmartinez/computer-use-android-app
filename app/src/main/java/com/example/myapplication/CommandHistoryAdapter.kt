package com.example.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.CommandHistoryUtils.CommandHistoryEntry
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Locale

class CommandHistoryAdapter(
    private var commands: List<CommandHistoryEntry>,
    private val retryListener: OnRetryCommandListener? = null,
    private val saveAsFavoriteListener: OnSaveAsFavoriteListener? = null
) : RecyclerView.Adapter<CommandHistoryAdapter.ViewHolder>() {

    /**
     * Interface for retry command actions
     */
    interface OnRetryCommandListener {
        fun onRetryCommand(command: CommandHistoryEntry)
    }
    
    /**
     * Interface for save as favorite actions
     */
    interface OnSaveAsFavoriteListener {
        fun onSaveAsFavorite(command: CommandHistoryEntry)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val commandText: TextView = view.findViewById(R.id.commandText)
        val statusText: TextView = view.findViewById(R.id.statusText)
        val timestampText: TextView = view.findViewById(R.id.timestampText)
        val stepsText: TextView = view.findViewById(R.id.stepsText)
        val stepsLabel: TextView = view.findViewById(R.id.stepsLabel)
        val showCodeButton: TextView = view.findViewById(R.id.showCodeButton)
        val codeText: TextView = view.findViewById(R.id.codeText)
        val retryButton: MaterialButton = view.findViewById(R.id.retryButton)
        val saveAsFavoriteButton: MaterialButton = view.findViewById(R.id.saveAsFavoriteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_command_history, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = commands.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val command = commands[position]
        
        // Set command text
        holder.commandText.text = command.command
        
        // Set status with appropriate color
        if (command.success) {
            holder.statusText.text = "Success"
            holder.statusText.setTextColor(holder.itemView.context.getColor(android.R.color.holo_green_dark))
            holder.statusText.setBackgroundResource(R.drawable.status_tag_background)
            // Show retry button for successful commands if listener is available
            holder.retryButton.visibility = if (retryListener != null) View.VISIBLE else View.GONE
        } else {
            holder.statusText.text = "Failed"
            holder.statusText.setTextColor(holder.itemView.context.getColor(android.R.color.holo_red_dark))
            holder.statusText.setBackgroundResource(R.drawable.status_tag_background_error)
            // Show retry button for failed commands if listener is available
            holder.retryButton.visibility = if (retryListener != null) View.VISIBLE else View.GONE
        }
        
        // Set retry button click listener
        holder.retryButton.setOnClickListener {
            retryListener?.onRetryCommand(command)
        }
        
        // Set save as favorite button visibility and click listener
        holder.saveAsFavoriteButton.visibility = if (saveAsFavoriteListener != null) View.VISIBLE else View.GONE
        holder.saveAsFavoriteButton.setOnClickListener {
            saveAsFavoriteListener?.onSaveAsFavorite(command)
        }
        
        // Format timestamp
        try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())
            val date = inputFormat.parse(command.timestamp)
            val outputFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            holder.timestampText.text = date?.let { outputFormat.format(it) } ?: command.timestamp
        } catch (e: Exception) {
            // If timestamp parsing fails, use the raw timestamp
            holder.timestampText.text = command.timestamp
        }
        
        // Format steps list
        if (command.steps.isEmpty()) {
            holder.stepsText.visibility = View.GONE
            holder.stepsLabel.visibility = View.GONE
        } else {
            holder.stepsText.visibility = View.VISIBLE
            holder.stepsLabel.visibility = View.VISIBLE
            
            // Build the steps text with numbering
            val stepsBuilder = StringBuilder()
            command.steps.forEachIndexed { index, step ->
                if (index > 0) stepsBuilder.append("\n")
                stepsBuilder.append("${index + 1}. $step")
            }
            holder.stepsText.text = stepsBuilder.toString()
        }
        
        // Handle code visibility toggling
        if (command.code.isBlank()) {
            holder.showCodeButton.visibility = View.GONE
            holder.codeText.visibility = View.GONE
        } else {
            holder.showCodeButton.visibility = View.VISIBLE
            holder.codeText.text = command.code
            
            // Toggle code visibility on button click
            holder.showCodeButton.setOnClickListener {
                if (holder.codeText.visibility == View.VISIBLE) {
                    holder.codeText.visibility = View.GONE
                    holder.showCodeButton.text = "Mostrar código"
                } else {
                    holder.codeText.visibility = View.VISIBLE
                    holder.showCodeButton.text = "Ocultar código"
                }
            }
        }
    }

    /**
     * Updates the command list and refreshes the adapter
     */
    fun updateCommands(newCommands: List<CommandHistoryEntry>) {
        commands = newCommands
        notifyDataSetChanged()
    }
} 