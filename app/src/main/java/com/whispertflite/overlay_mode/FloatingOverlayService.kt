package com.whispertflite.overlay_mode

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.whispertflite.MainActivity
import com.whispertflite.R
import com.whispertflite.asr.Recorder
import com.whispertflite.asr.Whisper
import com.whispertflite.asr.WhisperResult
import com.whispertflite.utils.InputLang
import java.io.File
import kotlin.math.abs

/**
 * FloatingOverlayService - Manual tap-to-toggle recording
 * NO VAD - user controls when to stop
 */
class FloatingOverlayService : Service() {
    
    companion object {
        private const val TAG = "FloatingOverlay"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_overlay_channel"
        private const val PREF_OVERLAY_X = "overlay_position_x"
        private const val PREF_OVERLAY_Y = "overlay_position_y"
        const val PREF_OVERLAY_SIZE = "overlay_size"  // 0=Small, 1=Medium, 2=Large
        private const val CLICK_THRESHOLD = 15
    }
    
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var sp: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    
    private lateinit var btnMic: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var pulseRing: View
    
    private var mRecorder: Recorder? = null
    private var mWhisper: Whisper? = null
    private var sdcardDataFolder: File? = null
    
    private var isRecording = false
    private var isProcessing = false
    private var isOverlayAdded = false
    private var pulseAnimator: AnimatorSet? = null
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        
        sp = PreferenceManager.getDefaultSharedPreferences(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sdcardDataFolder = getExternalFilesDir(null)
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        initOverlayView()
        initRecorder()
        initWhisperModel()
        
        BridgeManager.registerOverlayService(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy - FULL CLEANUP")
        
        // Unregister from bridge
        BridgeManager.unregisterOverlayService()
        
        // Stop animations
        pulseAnimator?.cancel()
        pulseAnimator = null
        
        // Stop and cleanup recorder (on background thread to avoid blocking)
        Thread {
            try {
                mRecorder?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder", e)
            }
        }.start()
        mRecorder = null
        
        // Stop Whisper and UNLOAD model from RAM
        mWhisper?.let {
            it.stop()
            it.unloadModel()  // This frees up ~300MB+ RAM
        }
        mWhisper = null
        
        // Remove overlay view
        if (isOverlayAdded) {
            try { 
                windowManager.removeView(overlayView) 
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
            isOverlayAdded = false
        }
        
        // Force garbage collection to free RAM immediately
        System.gc()
        
        Log.d(TAG, "Full cleanup complete - RAM freed")
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Voice", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant")
            .setContentText("Tap to record, tap again to stop")
            .setSmallIcon(R.drawable.ic_mic_48dp)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
    
    private fun initOverlayView() {
        overlayView = (getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater)
            .inflate(R.layout.floating_overlay_layout, null)
        
        btnMic = overlayView.findViewById(R.id.btn_mic)
        progressBar = overlayView.findViewById(R.id.progress_bar)
        pulseRing = overlayView.findViewById(R.id.pulse_ring)
        
        // Apply size based on preference (0=Small, 1=Medium, 2=Large)
        val sizeIndex = sp.getInt(PREF_OVERLAY_SIZE, 1) // Default medium
        val density = resources.displayMetrics.density
        
        // Container size in dp: Small=48, Medium=56, Large=72
        // Button size in dp: Small=36, Medium=44, Large=56
        val (containerDp, buttonDp) = when (sizeIndex) {
            0 -> Pair(48, 36)   // Small
            2 -> Pair(72, 56)   // Large
            else -> Pair(56, 44) // Medium (default)
        }
        
        val containerPx = (containerDp * density).toInt()
        val buttonPx = (buttonDp * density).toInt()
        val pulseRingPx = ((buttonDp + 8) * density).toInt()
        
        // Set sizes using new LayoutParams (views don't have layoutParams until attached)
        val container = overlayView.findViewById<android.widget.FrameLayout>(R.id.overlay_container)
        container.layoutParams = android.widget.FrameLayout.LayoutParams(containerPx, containerPx)
        
        btnMic.layoutParams = android.widget.FrameLayout.LayoutParams(buttonPx, buttonPx, Gravity.CENTER)
        progressBar.layoutParams = android.widget.FrameLayout.LayoutParams(buttonPx, buttonPx, Gravity.CENTER)
        pulseRing.layoutParams = android.widget.FrameLayout.LayoutParams(pulseRingPx, pulseRingPx, Gravity.CENTER)
        
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        
        layoutParams = WindowManager.LayoutParams(
            containerPx,
            containerPx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt(PREF_OVERLAY_X, 50)
            y = sp.getInt(PREF_OVERLAY_Y, 400)
        }
        
        windowManager.addView(overlayView, layoutParams)
        isOverlayAdded = true
        
        // Start VISIBLE since we only start in whitelisted apps
        overlayView.visibility = View.VISIBLE
        
        setupTouchListeners()
    }
    
    private fun setupTouchListeners() {
        btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > CLICK_THRESHOLD || abs(dy) > CLICK_THRESHOLD) {
                        isDragging = true
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(overlayView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        sp.edit().putInt(PREF_OVERLAY_X, layoutParams.x).putInt(PREF_OVERLAY_Y, layoutParams.y).apply()
                    } else {
                        handleTap()
                    }
                    true
                }
                else -> false
            }
        }
    }
    
    private fun handleTap() {
        when {
            isProcessing -> {
                toast("Still processing...")
            }
            isRecording -> {
                // STOP recording
                stopRecording()
            }
            else -> {
                // START recording
                startRecording()
            }
        }
    }
    
    // Overlay visibility now controlled by starting/stopping the entire service
    
    private fun initRecorder() {
        mRecorder = Recorder(this).apply {
            // NO initVad() - we want manual control
            
            setListener(object : Recorder.RecorderListener {
                override fun onUpdateReceived(message: String) {
                    Log.d(TAG, "Recorder: $message")
                    handler.post {
                        when (message) {
                            Recorder.MSG_RECORDING -> {
                                btnMic.setBackgroundResource(R.drawable.mic_button_recording)
                                startPulseAnimation()
                            }
                            Recorder.MSG_RECORDING_DONE -> {
                                btnMic.setBackgroundResource(R.drawable.mic_button_background)
                                stopPulseAnimation()
                                isRecording = false
                                isProcessing = true
                                showProcessingState()
                                toast("⏳ Transcribing...")
                                startTranscription()
                            }
                            Recorder.MSG_RECORDING_ERROR -> {
                                btnMic.setBackgroundResource(R.drawable.mic_button_background)
                                stopPulseAnimation()
                                isRecording = false
                                toast("❌ Recording error")
                            }
                        }
                    }
                }
            })
        }
    }
    
    private fun initWhisperModel() {
        val modelName = sp.getString("modelName", MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW) ?: return
        val modelFile = File(sdcardDataFolder, modelName)
        
        if (!modelFile.exists()) {
            handler.post { toast("Download model first") }
            return
        }
        
        val isMultilingual = !modelFile.name.endsWith(MainActivity.ENGLISH_ONLY_MODEL_EXTENSION)
        val vocabFile = File(sdcardDataFolder, 
            if (isMultilingual) MainActivity.MULTILINGUAL_VOCAB_FILE else MainActivity.ENGLISH_ONLY_VOCAB_FILE)
        
        mWhisper = Whisper(this).apply {
            loadModel(modelFile, vocabFile, isMultilingual)
            
            setListener(object : Whisper.WhisperListener {
                override fun onUpdateReceived(message: String) {
                    Log.d(TAG, "Whisper: $message")
                }
                
                override fun onResultReceived(result: WhisperResult) {
                    Log.d(TAG, "Result received")
                    handler.post {
                        isProcessing = false
                        hideProcessingState()
                        
                        val text = result.result?.trim() ?: ""
                        Log.d(TAG, "Text: '$text'")
                        
                        when {
                            text.isEmpty() || text == "!!!" || text.startsWith("[") -> {
                                toast("No speech detected")
                                vibrateError()
                            }
                            else -> {
                                copyToClipboard(text)
                                val injected = BridgeManager.injectText(text)
                                if (injected) {
                                    toast("✓ $text")
                                    vibrateSuccess()
                                } else {
                                    toast("📋 Copied: $text")
                                    vibrateTap()
                                }
                            }
                        }
                    }
                }
            })
        }
        Log.d(TAG, "Model: ${modelFile.name}")
    }
    
    private fun startRecording() {
        isRecording = true
        vibrateTap()
        toast("🎤 Recording... tap to stop")
        mRecorder?.start()
    }
    
    private fun stopRecording() {
        isRecording = false
        isProcessing = true
        showProcessingState()
        toast("⏳ Processing...")
        
        // Run on background thread since stop() blocks
        Thread {
            mRecorder?.stop()
        }.start()
    }
    
    private fun startTranscription() {
        mWhisper?.let {
            it.setAction(Whisper.ACTION_TRANSLATE)
            it.setLanguage(InputLang.getIdForLanguage(InputLang.getLangList(), "en"))
            it.start()
        } ?: run {
            isProcessing = false
            hideProcessingState()
            toast("Model not loaded")
        }
    }
    
    private fun copyToClipboard(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Whisper", text))
    }
    
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    
    private fun showProcessingState() {
        progressBar.visibility = View.VISIBLE
        btnMic.alpha = 0.6f
    }
    
    private fun hideProcessingState() {
        progressBar.visibility = View.GONE
        btnMic.alpha = 1f
    }
    
    private fun startPulseAnimation() {
        pulseRing.visibility = View.VISIBLE
        pulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(pulseRing, "scaleX", 1f, 1.4f).apply { duration = 600; repeatCount = ValueAnimator.INFINITE },
                ObjectAnimator.ofFloat(pulseRing, "scaleY", 1f, 1.4f).apply { duration = 600; repeatCount = ValueAnimator.INFINITE },
                ObjectAnimator.ofFloat(pulseRing, "alpha", 0.7f, 0f).apply { duration = 600; repeatCount = ValueAnimator.INFINITE }
            )
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }
    
    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseRing.visibility = View.GONE
    }
    
    private fun vibrateTap() = vibrate(25)
    private fun vibrateSuccess() = vibrate(30, 50, 30)
    private fun vibrateError() = vibrate(80)
    
    private fun vibrate(vararg durations: Long) {
        val pattern = longArrayOf(0) + durations
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(pattern, -1)
            }
        } catch (e: Exception) {}
    }
}
