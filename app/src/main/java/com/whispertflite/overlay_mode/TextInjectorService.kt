package com.whispertflite.overlay_mode

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * TextInjectorService - Overlay ONLY in whitelisted apps
 */
class TextInjectorService : AccessibilityService() {
    
    companion object {
        private const val TAG = "TextInjector"
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastPackage = ""
    private var serviceRunning = false
    
    private fun isWhitelisted(pkg: String): Boolean {
        return WhitelistManager.isWhitelisted(this, pkg)
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        
        BridgeManager.registerTextInjector(this)
        Log.d(TAG, "Ready. Whitelist loaded from preferences.")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        
        val pkg = event.packageName?.toString() ?: return
        
        // Ignore system stuff and our own app (overlay triggers our own window events)
        if (pkg.startsWith("android") || 
            pkg.startsWith("com.android.systemui") ||
            pkg == "adithyakrish0.whispervoice" ||
            pkg.contains("keyboard") ||
            pkg.contains("inputmethod") ||
            pkg.contains("gboard") ||
            pkg.contains("swiftkey") ||
            pkg.contains("honeyboard") ||
            pkg.contains("statusbar") ||
            pkg.contains("screenshot") ||
            pkg.contains("permissioncontroller") ||
            pkg.contains("notification") ||
            pkg.contains("popup") ||
            pkg.contains("overlay")) {
            return
        }
        
        // Same app, ignore
        if (pkg == lastPackage) return
        lastPackage = pkg
        
        val shouldRun = isWhitelisted(pkg)
        Log.d(TAG, "App: $pkg -> ${if (shouldRun) "START" else "STOP"}")
        
        if (shouldRun) {
            // In whitelisted app - ensure service is running
            if (!serviceRunning) {
                startOverlay()
            }
        } else {
            // Left whitelisted app - always try to stop
            // (service might have been started from MainActivity)
            stopOverlay()
        }
    }
    
    private var serviceStartTime = 0L
    
    private fun startOverlay() {
        try {
            val intent = Intent(this, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceRunning = true
            serviceStartTime = System.currentTimeMillis()
            Log.d(TAG, ">>> STARTED")
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
        }
    }
    
    private fun stopOverlay() {
        try {
            stopService(Intent(this, FloatingOverlayService::class.java))
            serviceRunning = false
            Log.d(TAG, "<<< STOPPED")
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
        }
    }
    
    override fun onInterrupt() {}
    
    override fun onDestroy() {
        BridgeManager.unregisterTextInjector()
        stopOverlay()
        super.onDestroy()
    }
    
    fun injectText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findEditText(root)
        root.recycle()
        node ?: return false
        
        val current = node.text?.toString() ?: ""
        
        // Only treat as placeholder if EXACT match (case-insensitive)
        val knownPlaceholders = setOf(
            "ask gemini",
            "search",
            "type a message",
            "message",
            "type here",
            "enter text"
        )
        val isPlaceholder = current.isEmpty() || 
            current.lowercase().trim() in knownPlaceholders
        
        // If placeholder, replace. Otherwise append.
        val newText = if (isPlaceholder) {
            text
        } else if (current.isNotEmpty() && !current.endsWith(" ")) {
            "$current $text"
        } else {
            current + text
        }
        
        val result = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) }
        )
        node.recycle()
        return result
    }
    
    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditText(child)?.let { child.recycle(); return it }
            child.recycle()
        }
        return null
    }
}
