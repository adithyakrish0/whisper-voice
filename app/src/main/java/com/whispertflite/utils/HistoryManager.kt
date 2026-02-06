package com.whispertflite.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val transcription: String,
    val title: String, // New field
    val timestamp: Long = System.currentTimeMillis()
)

object HistoryManager {
    private const val FILE_NAME = "history.json"

    fun save(context: Context, fileName: String, transcription: String): String {
        val title = generateSemanticTitle(transcription)
        val newItem = HistoryItem(fileName = fileName, transcription = transcription, title = title)
        val items = load(context).toMutableList()
        items.add(0, newItem) // Add to top
        saveList(context, items)
        return newItem.id
    }

    private fun generateSemanticTitle(text: String): String {
        if (text.isBlank()) return "Empty Transcription"
        
        // Clean text: remove special tokens, brackets, and newlines
        val cleanText = text.replace(Regex("\\[_\\w+_\\]"), "")
            .replace("\n", " ")
            .trim()
            
        if (cleanText.isEmpty()) return "Untitled"

        // Tokenize and filter
        val stopWords = setOf("the", "and", "a", "an", "is", "of", "to", "in", "it", "that", "was", "for", "on", "are", "with")
        val words = cleanText.split(Regex("\\s+"))
            .filter { it.length > 2 && !stopWords.contains(it.lowercase()) }
            .take(3) // User requested 2-3 words
            
        return if (words.isEmpty()) {
            cleanText.split(Regex("\\s+")).take(3).joinToString(" ").capitalizeWords()
        } else {
            words.joinToString(" ").capitalizeWords()
        }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

    fun load(context: Context): List<HistoryItem> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        
        return try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<HistoryItem>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = obj.optString("id"),
                        fileName = obj.optString("fileName"),
                        transcription = obj.optString("transcription"),
                        title = obj.optString("title", obj.optString("fileName")), // Fallback to fileName for compatibility
                        timestamp = obj.optLong("timestamp")
                    )
                )
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun delete(context: Context, id: String) {
        val items = load(context).toMutableList()
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().id == id) {
                iterator.remove()
                break
            }
        }
        saveList(context, items)
    }

    private fun saveList(context: Context, items: List<HistoryItem>) {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("fileName", item.fileName)
            obj.put("transcription", item.transcription)
            obj.put("title", item.title)
            obj.put("timestamp", item.timestamp)
            jsonArray.put(obj)
        }
        
        try {
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
