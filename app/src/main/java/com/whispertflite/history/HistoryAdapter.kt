package com.whispertflite.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.whispertflite.R
import com.whispertflite.utils.HistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private var items: List<HistoryItem>,
    private val onItemClick: (HistoryItem) -> Unit,
    private val onDelete: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvPreview: TextView = view.findViewById(R.id.tvPreview)
        val btnCopy: MaterialButton = view.findViewById(R.id.btnCopy)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvFileName.text = item.title
        holder.tvDate.text = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(item.timestamp))
        holder.tvPreview.text = item.transcription

        holder.itemView.setOnClickListener { onItemClick(item) }

        holder.btnCopy.setOnClickListener {
            val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Transcription", item.transcription)
            clipboard.setPrimaryClip(clip)
            
            // Android 13+ shows its own toast
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Toast.makeText(it.context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        holder.btnDelete.setOnClickListener {
            onDelete(item)
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newItems: List<HistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
