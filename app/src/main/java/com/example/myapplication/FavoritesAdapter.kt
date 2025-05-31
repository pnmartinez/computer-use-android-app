package com.example.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Locale

class FavoritesAdapter(
    private var favorites: List<FavoriteEntry>,
    private val actionListener: OnFavoriteActionListener? = null
) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

    /**
     * Interface for favorite item actions
     */
    interface OnFavoriteActionListener {
        fun onRunFavorite(favorite: FavoriteEntry)
        fun onDeleteFavorite(favorite: FavoriteEntry)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val favoriteNameText: TextView = view.findViewById(R.id.favoriteNameText)
        val commandText: TextView = view.findViewById(R.id.favoriteCommandText)
        val timestampText: TextView = view.findViewById(R.id.favoriteTimestampText)
        val stepsText: TextView = view.findViewById(R.id.favoriteStepsText)
        val stepsLabel: TextView = view.findViewById(R.id.favoriteStepsLabel)
        val runButton: MaterialButton = view.findViewById(R.id.runFavoriteButton)
        val deleteButton: MaterialButton = view.findViewById(R.id.deleteFavoriteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = favorites.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val favorite = favorites[position]
        
        // Set favorite name/command text
        holder.favoriteNameText.text = favorite.name
        holder.commandText.text = favorite.command
        
        // Format timestamp
        try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = inputFormat.parse(favorite.timestamp)
            val outputFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            holder.timestampText.text = date?.let { outputFormat.format(it) } ?: favorite.timestamp
        } catch (e: Exception) {
            // If timestamp parsing fails, use the raw timestamp
            holder.timestampText.text = favorite.timestamp
        }
        
        // Format steps list
        if (favorite.steps.isEmpty()) {
            holder.stepsText.visibility = View.GONE
            holder.stepsLabel.visibility = View.GONE
        } else {
            holder.stepsText.visibility = View.VISIBLE
            holder.stepsLabel.visibility = View.VISIBLE
            
            // Build the steps text with numbering
            val stepsBuilder = StringBuilder()
            favorite.steps.forEachIndexed { index, step ->
                if (index > 0) stepsBuilder.append("\n")
                stepsBuilder.append("${index + 1}. $step")
            }
            holder.stepsText.text = stepsBuilder.toString()
        }
        
        // Set up action buttons
        holder.runButton.setOnClickListener {
            actionListener?.onRunFavorite(favorite)
        }
        
        holder.deleteButton.setOnClickListener {
            actionListener?.onDeleteFavorite(favorite)
        }
    }

    /**
     * Updates the favorites list and refreshes the adapter
     */
    fun updateFavorites(newFavorites: List<FavoriteEntry>) {
        favorites = newFavorites
        notifyDataSetChanged()
    }
} 