package com.whispertflite.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.whispertflite.R
import com.whispertflite.utils.HistoryItem
import com.whispertflite.utils.HistoryManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvEmpty = findViewById(R.id.tvEmpty)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = HistoryAdapter(
            items = emptyList(),
            onItemClick = { item -> showDetailDialog(item) },
            onDelete = { item ->
                HistoryManager.delete(this, item.id)
                loadData()
            }
        )
        recyclerView.adapter = adapter
        
        loadData()

        // Handle Deep Link from Notification
        intent.getStringExtra("EXTRA_ITEM_ID")?.let { itemId ->
            HistoryManager.load(this).find { it.id == itemId }?.let { item ->
                showDetailDialog(item)
            }
        }
    }

    private fun loadData() {
        val items = HistoryManager.load(this)
        adapter.updateList(items)
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDetailDialog(item: HistoryItem) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_history_detail, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        view.findViewById<TextView>(R.id.tvDetailTitle).text = item.title
        view.findViewById<TextView>(R.id.tvDetailDate).text = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(item.timestamp))
        view.findViewById<TextView>(R.id.tvDetailContent).text = item.transcription

        view.findViewById<MaterialButton>(R.id.btnDetailCopy).setOnClickListener {
            copyToClipboard(item.transcription)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnDetailShare).setOnClickListener {
            shareTranscription(item.transcription)
        }

        view.findViewById<MaterialButton>(R.id.btnDetailExport).setOnClickListener {
            saveToDownloads(item.title, item.transcription)
        }

        view.findViewById<MaterialButton>(R.id.btnDetailClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcription", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun shareTranscription(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share Transcription"))
    }

    private fun saveToDownloads(title: String, content: String) {
        val fileName = "${title.replace(" ", "_")}_${System.currentTimeMillis()}.txt"
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    }
                    Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                } ?: run {
                    Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
