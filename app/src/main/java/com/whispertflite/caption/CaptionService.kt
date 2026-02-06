package com.whispertflite.caption

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.whispertflite.R
import com.whispertflite.asr.RecordBuffer
import com.whispertflite.asr.Whisper
import com.whispertflite.asr.WhisperResult
import java.io.File
import java.util.concurrent.Executors
import android.os.PowerManager
import com.whispertflite.utils.HistoryManager

/**
 * Foreground service for background video transcription
 * Supports strictly local file transcription
 */
class CaptionService : Service(), Whisper.WhisperListener {
    
    companion object {
        private const val TAG = "CaptionService"
        const val CHANNEL_ID = "caption_service_channel"
        const val NOTIFICATION_ID = 2001
        
        const val ACTION_START = "com.whispertflite.caption.START"
        const val ACTION_STOP = "com.whispertflite.caption.STOP"
        const val ACTION_PROGRESS_UPDATE = "com.whispertflite.caption.PROGRESS"
        const val ACTION_ESTIMATE = "com.whispertflite.caption.ESTIMATE"
        const val ACTION_COMPLETED = "com.whispertflite.caption.COMPLETED"
        
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_ITEM_ID = "item_id"
        
        var isRunning = false
            private set
    }
    
    private val executor = Executors.newSingleThreadExecutor()
    private var whisper: Whisper? = null
    
    // Chunked processing state
    private var currentSampleOffset = 0
    private var totalSamples = 0
    private var accumulatedResult = StringBuilder()
    private val CHUNK_SIZE_SAMPLES = 16000 * 30 // 30 seconds
    
    private var mFilePath: String? = null
    private var isTranscriptionCancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Whisper:ProcessingLock")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                if (filePath != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, createProgressNotification("Starting transcription..."), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, createProgressNotification("Starting transcription..."))
                    }
                    startTranscription(filePath)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        // Clean up temp file if still exists
        mFilePath?.let { path ->
            val file = File(path)
            if (file.exists() && file.name.startsWith("temp_audio_")) {
                file.delete()
                Log.d(TAG, "Cleaned up temp file in onDestroy")
            }
        }

        whisper?.stop()
        whisper?.unloadModel()
        whisper = null
        executor.shutdown()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Transcription",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows transcription progress"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createProgressNotification(status: String, progress: Int? = null): Notification {
        val stopIntent = Intent(this, CaptionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transcribing Video")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_mic_foreground)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            
        if (progress != null) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
            
        return builder.build()
    }
    
    private fun updateProgress(status: String, progress: Int? = null) {
        if (!isRunning || isTranscriptionCancelled) return
        
        // Update notification
        val notification = createProgressNotification(status, progress)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        
        // Broadcast to Activity
        val intent = Intent(ACTION_PROGRESS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            if (progress != null) {
                putExtra(EXTRA_PROGRESS, progress)
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastCompletion(result: String) {
        // Save result to storage for recovery
        getSharedPreferences("prefs_caption", Context.MODE_PRIVATE).edit()
            .putString("last_result", result)
            .putLong("last_result_time", System.currentTimeMillis())
            .apply()
            
        // Save to History and get the ID
        val fileName = File(mFilePath ?: "Unknown").name
        val itemId = com.whispertflite.utils.HistoryManager.save(this, fileName, result)

        val intent = Intent(ACTION_COMPLETED).apply {
            putExtra(EXTRA_RESULT, result)
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        
        // Release lock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        showResultNotification(result, itemId)
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTranscription(filePath: String) {
        mFilePath = filePath
        isTranscriptionCancelled = false
        
        // Acquire lock
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
        }

        executor.execute {
            try {
                // Initialize Whisper
                updateProgress("Loading Whisper model...")
                initWhisper()
                
                val file = File(filePath)
                if (file.exists()) {
                    transcribeFile(file)
                } else {
                    updateProgress("Error: File not found")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                showResultNotification("Error: ${e.message}")
                stopSelf()
            }
        }
    }
    
    private fun broadcastEstimate(durationMs: Long) {
        Log.d(TAG, "Broadcasting estimate: $durationMs ms")
        val intent = Intent(ACTION_ESTIMATE).apply {
            putExtra(EXTRA_DURATION, durationMs)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    
    private fun showResultNotification(text: String, itemId: String? = null) {
        val intent = Intent(this, com.whispertflite.history.HistoryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (itemId != null) {
                putExtra("EXTRA_ITEM_ID", itemId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✅ Transcription Complete!")
            .setContentText(text.take(100) + if (text.length > 100) "..." else "")
            .setSmallIcon(R.drawable.ic_mic_foreground)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(500)))
            .build()
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    private fun initWhisper() {
        val modelPath = getModelPath()
        val vocabPath = getVocabPath()
        
        if (modelPath != null && vocabPath != null) {
            whisper = Whisper(this)
            whisper?.setListener(this)
            val isMultilingual = !modelPath.contains("en_")
            whisper?.loadModel(modelPath, vocabPath, isMultilingual)
            whisper?.setAction(Whisper.ACTION_TRANSCRIBE)
        } else {
            throw Exception("Model not found")
        }
    }
    
    private fun getModelPath(): String? {
        val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val selectedModel = sp.getString("modelName", "whisper-small.tflite")
        val externalDir = getExternalFilesDir(null) ?: return null
        
        // Try to find the exact selected model
        val modelFile = File(externalDir, selectedModel!!)
        if (modelFile.exists()) return modelFile.absolutePath
        
        // Fallback: look for ANY "small" model first (High Precision)
        val files = externalDir.listFiles { _, name -> name.endsWith(".tflite") } ?: return null
        val bestModel = files.find { it.name.contains("small") } ?: files.firstOrNull()
        
        return bestModel?.absolutePath
    }
    
    private fun getVocabPath(): String? {
        val modelPath = getModelPath() ?: return null
        val externalDir = getExternalFilesDir(null) ?: return null
        val isMultilingual = !modelPath.contains(".en.tflite")
        
        val vocabName = if (isMultilingual) "filters_vocab_multilingual.bin" else "filters_vocab_en.bin"
        val vocabFile = File(externalDir, vocabName)
        
        return if (vocabFile.exists()) vocabFile.absolutePath else {
            // Fallback: find any .bin file
            externalDir.listFiles { _, name -> name.endsWith(".bin") }?.firstOrNull()?.absolutePath
        }
    }
    
    private fun transcribeFile(file: File) {
        try {
            updateProgress("Extracting audio stream...")
            val audioData = AudioExtractor.extractAudio(file)
            
            if (audioData == null || audioData.isEmpty()) {
                updateProgress("Failed to extract audio")
                file.delete()
                stopSelf()
                return
            }
            
            updateProgress("Extracted ${audioData.size} audio samples")
            // Calculate duration and broadcast estimate
            // 16000 samples per second
            val durationMs = (audioData.size.toDouble() / 16000.0 * 1000.0).toLong()
            broadcastEstimate(durationMs)
            
            updateProgress("Preparing RecordBuffer for inference...")
            
            // Fill RecordBuffer with byte array once
            val byteBuffer = java.nio.ByteBuffer.allocate(audioData.size * 2)
            byteBuffer.order(java.nio.ByteOrder.nativeOrder())
            for (sample in audioData) {
                byteBuffer.putShort((sample * 32767).toInt().toShort())
            }
            RecordBuffer.setOutputBuffer(byteBuffer.array())
            updateProgress("Audio data buffered.")
            
            // Start first chunk
            currentSampleOffset = 0
            totalSamples = audioData.size
            accumulatedResult.setLength(0)
            
            Log.d(TAG, "Starting transcription loop: $totalSamples samples total")
            whisper?.start(0)
            
            // Delete temp file after processing is started (buffer is now in RecordBuffer)
            if (file.name.startsWith("temp_audio_")) {
                file.delete()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            showResultNotification("Error: ${e.message}")
            broadcastCompletion("Error during transcription") // Ensure UI is signaled
            stopSelf()
        } finally {
            // Final safety cleanup: ensure the temp file is gone after buffering or error
            mFilePath?.let { path ->
                val file = File(path)
                if (file.exists() && file.name.startsWith("temp_audio_")) {
                    file.delete()
                    Log.d(TAG, "Cleaned up temp file in finally block")
                }
            }
        }
    }
    
    // ==================== WHISPER LISTENER ====================
    
    override fun onUpdateReceived(message: String) {
        // Ignore generic Whisper engine messages to avoid overwriting our chunk progress
        if (message == Whisper.MSG_PROCESSING || message == Whisper.MSG_PROCESSING_DONE) {
            return
        }
        updateProgress(message)
    }
    
    override fun onResultReceived(result: WhisperResult?) {
        val text = result?.result?.trim() ?: ""
        Log.d(TAG, "Chunk result received: '$text'")
        
        if (text.isNotEmpty() && text != "!!!" && !text.startsWith("[")) {
            if (accumulatedResult.isNotEmpty()) accumulatedResult.append(" ")
            accumulatedResult.append(text)
        }
        
        currentSampleOffset += CHUNK_SIZE_SAMPLES
        
        if (currentSampleOffset < totalSamples) {
            val progress = (currentSampleOffset.toDouble() / totalSamples * 100).toInt()
            Log.d(TAG, "Requesting next chunk at $currentSampleOffset (Progress: $progress%)")
            updateProgress("Starting chunk at offset $currentSampleOffset (${progress}%)", progress)
            whisper?.start(currentSampleOffset)
        } else {
            val finalResult = accumulatedResult.toString().trim()
            Log.d(TAG, "Transcription loop completed. Final length: ${finalResult.length}")
            updateProgress("Finalizing transcription...")
            
            if (finalResult.isEmpty()) {
                Log.w(TAG, "Final transcription result is empty")
                showResultNotification("No speech detected")
                broadcastCompletion("No speech detected")
            } else {
                showResultNotification(finalResult)
                broadcastCompletion(finalResult)
            }
            stopSelf()
        }
    }
}
