package com.whispertflite.caption

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.whispertflite.R
import java.io.File
import java.util.concurrent.TimeUnit

class CaptionActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "CaptionActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }
    
    // UI Elements
    private lateinit var cardSelectVideo: View
    private lateinit var cardProgress: View
    
    private lateinit var tvSelectedFile: TextView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvLog: TextView
    private lateinit var logScrollView: androidx.core.widget.NestedScrollView
    
    // State
    private var selectedFileUri: Uri? = null
    private var isProcessing = false
    private var startTime: Long = 0
    private var totalEstimateMsValue: Long = 0
    private var isCountdown = false
    private var timerHandler: Handler? = null
    
    // File picker
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedFileUri = uri
                val fileName = getFileName(uri)
                tvSelectedFile.text = fileName ?: "File selected"
                startTranscriptionService()
            }
        }
    }
    
    // Broadcast Receiver for Service updates
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptionService.ACTION_PROGRESS_UPDATE -> {
                    val status = intent.getStringExtra(CaptionService.EXTRA_STATUS) ?: "Processing..."
                    val progress = intent.getIntExtra(CaptionService.EXTRA_PROGRESS, -1)
                    updateProgress(status, progress)
                    appendLog(status)
                }
                CaptionService.ACTION_ESTIMATE -> {
                    val durationMs = intent.getLongExtra(CaptionService.EXTRA_DURATION, 0L)
                    Log.d(TAG, "Received estimate from service: $durationMs ms")
                    if (durationMs > 0) {
                        startCountdown(durationMs)
                    }
                }
                CaptionService.ACTION_COMPLETED -> {
                    val itemId = intent.getStringExtra(CaptionService.EXTRA_ITEM_ID)
                    redirectToHistory(itemId)
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_caption)
        
        initViews()
        setupListeners()
        checkPermissions()
        
        // Restore state if service is running
        if (CaptionService.isRunning) {
            showProgress("Resuming...")
        }
    }
    
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(CaptionService.ACTION_PROGRESS_UPDATE)
            addAction(CaptionService.ACTION_ESTIMATE)
            addAction(CaptionService.ACTION_COMPLETED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, filter)
        
        // Check for result in Intent (Notification click)
        if (intent.hasExtra(CaptionService.EXTRA_RESULT)) {
            val result = intent.getStringExtra(CaptionService.EXTRA_RESULT) ?: ""
            intent.removeExtra(CaptionService.EXTRA_RESULT) // Consume it
            showResult(result)
            return
        }

        // Check for persisted result if Service died while we were paused
        if (!CaptionService.isRunning && isProcessing) {
            val prefs = getSharedPreferences("prefs_caption", Context.MODE_PRIVATE)
            val lastResult = prefs.getString("last_result", null)
            val lastTime = prefs.getLong("last_result_time", 0)
            
            // Only use if recent (< 5 min)
            if (lastResult != null && System.currentTimeMillis() - lastTime < 5 * 60 * 1000) {
                // If we have a result, just go to history
                redirectToHistory(null)
                prefs.edit().remove("last_result").apply()
                return
            }
        }
        
        if (CaptionService.isRunning && !isProcessing) {
            showProgress("Synchronization...")
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update current intent
        
        if (intent.hasExtra(CaptionService.EXTRA_RESULT)) {
            val itemId = intent.getStringExtra(CaptionService.EXTRA_ITEM_ID)
            redirectToHistory(itemId)
        }
    }
    
    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver)
    }
    
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.caption_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_history -> {
                startActivity(Intent(this, com.whispertflite.history.HistoryActivity::class.java))
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun initViews() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        // Back navigation is handled by onOptionsItemSelected(android.R.id.home) or default behavior
        
        cardSelectVideo = findViewById(R.id.cardSelectVideo)
        cardProgress = findViewById(R.id.cardProgress)
        findViewById<View>(R.id.btnCancelTranscription).setOnClickListener {
            stopTranscription()
        }
        
        tvSelectedFile = findViewById<TextView>(R.id.tvSelectedFile)
        tvStatusTitle = findViewById<TextView>(R.id.tvStatusTitle)
        tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvTimer = findViewById<TextView>(R.id.tvTimer)
        progressBar = findViewById(R.id.progressBar)
        tvLog = findViewById(R.id.tvLog)
        logScrollView = findViewById(R.id.logScrollView)
        
        tvLog.text = "Initializing logs...\n"
        
        findViewById<View>(R.id.btnViewHistory).setOnClickListener {
            startActivity(Intent(this, com.whispertflite.history.HistoryActivity::class.java))
        }
    }
    
    private fun setupListeners() {
        cardSelectVideo.setOnClickListener {
            pickFile()
        }
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
            }
        }
    }
    
    private fun stopTranscription() {
        val intent = Intent(this, CaptionService::class.java).apply {
            action = CaptionService.ACTION_STOP
        }
        startService(intent)
        
        cardProgress.visibility = View.GONE
        cardSelectVideo.visibility = View.VISIBLE
        findViewById<View>(R.id.btnViewHistory).visibility = View.VISIBLE
        tvLog.text = ""
    }
    
    private fun pickFile() {
        if (CaptionService.isRunning) {
            Toast.makeText(this, "Transcription already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }
        filePickerLauncher.launch(Intent.createChooser(intent, "Select Video/Audio"))
    }
    
    private fun startTranscriptionService() {
        val uri = selectedFileUri ?: return
        
        // Copy to temp file accessible by Service
        val tempFile = copyUriToTempFile(uri)
        if (tempFile == null) {
            Toast.makeText(this, "Failed to access file", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, CaptionService::class.java).apply {
            action = CaptionService.ACTION_START
            putExtra(CaptionService.EXTRA_FILE_PATH, tempFile.absolutePath)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Extract duration and start countdown immediately
        val duration = getVideoDuration(uri)
        Log.d(TAG, "Selected file duration: $duration ms")
        if (duration > 0) {
            showProgress("Loading...")
            startCountdown(duration)
        } else {
            showProgress("Starting service...")
        }
    }
    
    private fun getVideoDuration(uri: Uri): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val time = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            time?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video duration", e)
            0L
        }
    }
    
    private fun showProgress(status: String) {
        if (!isProcessing) {
            isProcessing = true
            cardSelectVideo.visibility = View.GONE
            findViewById<View>(R.id.btnViewHistory).visibility = View.GONE
            cardProgress.visibility = View.VISIBLE
            startTimer()
        }
        tvStatus.text = status
        progressBar.isIndeterminate = true
    }
    
    private fun updateProgress(status: String, progress: Int = -1) {
        if (!isProcessing) showProgress(status)
        tvStatus.text = status
        
        if (progress >= 0) {
            if (progressBar.isIndeterminate) {
                progressBar.isIndeterminate = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                progressBar.setProgress(progress, true)
            } else {
                progressBar.progress = progress
            }
        }
    }
    
    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = "[$timestamp] $message\n"
        tvLog.append(logLine)
        
        // Auto-scroll to bottom
        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
    
    private fun redirectToHistory(itemId: String?) {
        isProcessing = false
        stopTimer()
        
        val intent = Intent(this, com.whispertflite.history.HistoryActivity::class.java).apply {
            if (itemId != null) {
                putExtra("EXTRA_ITEM_ID", itemId)
            }
        }
        startActivity(intent)
        finish()
    }
    
    private fun showResult(text: String) {
        // Fallback or legacy, now mostly replaced by redirectToHistory
        redirectToHistory(null)
    }
    
    private fun startTimer() {
        if (timerHandler != null || isCountdown) return
        startTime = System.currentTimeMillis()
        isCountdown = false
        timerHandler = Handler(Looper.getMainLooper())
        timerHandler?.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed) - TimeUnit.MINUTES.toSeconds(minutes)
                tvTimer.text = String.format("%02d:%02d", minutes, seconds)
                timerHandler?.postDelayed(this, 1000)
            }
        })
    }

    private fun startCountdown(durationMs: Long) {
        // If already counting down and the new estimate is similar, don't restart
        if (isCountdown && Math.abs(totalEstimateMsValue - (durationMs + 3000)) < 2000) {
            return
        }
        
        // Stop current timer if running
        stopTimer()
        
        Log.d(TAG, "Starting countdown for $durationMs ms")
        // Estimate: 1.0x audio duration for transcription + some buffer
        totalEstimateMsValue = durationMs + 3000 // 3s buffer
        startTime = System.currentTimeMillis()
        isCountdown = true
        
        timerHandler = Handler(Looper.getMainLooper())
        timerHandler?.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = totalEstimateMsValue - elapsed
                
                if (remaining > 0) {
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining)
                    val seconds = TimeUnit.MILLISECONDS.toSeconds(remaining) - TimeUnit.MINUTES.toSeconds(minutes)
                    tvTimer.text = String.format("%02d:%02d", minutes, seconds)
                    timerHandler?.postDelayed(this, 1000)
                } else {
                    tvTimer.text = "00:01" // Stay at 1s until finished
                    tvStatus.text = "Finishing up..."
                }
            }
        })
    }
    
    private fun stopTimer() {
        timerHandler?.removeCallbacksAndMessages(null)
        timerHandler = null
    }
    
    private fun copyUriToTempFile(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(cacheDir, "temp_audio_${System.currentTimeMillis()}")
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file", e)
            null
        }
    }
    
    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }
}
