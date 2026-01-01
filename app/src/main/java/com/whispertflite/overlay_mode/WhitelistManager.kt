package com.whispertflite.overlay_mode

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Manages the app whitelist - stores which apps should show the overlay
 */
object WhitelistManager {
    
    private const val PREF_WHITELISTED_APPS = "whitelisted_apps"
    
    // Default apps when first installed
    private val DEFAULT_APPS = setOf(
        "com.google.android.googlequicksearchbox",  // Google App
        "com.google.android.apps.bard"  // Gemini App
    )
    
    private fun getPrefs(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }
    
    /**
     * Get the set of whitelisted package names
     */
    fun getWhitelistedApps(context: Context): Set<String> {
        val prefs = getPrefs(context)
        val saved = prefs.getStringSet(PREF_WHITELISTED_APPS, null)
        return saved ?: DEFAULT_APPS
    }
    
    /**
     * Save the set of whitelisted package names
     */
    fun setWhitelistedApps(context: Context, apps: Set<String>) {
        getPrefs(context).edit()
            .putStringSet(PREF_WHITELISTED_APPS, apps)
            .apply()
    }
    
    /**
     * Add an app to the whitelist
     */
    fun addApp(context: Context, packageName: String) {
        val current = getWhitelistedApps(context).toMutableSet()
        current.add(packageName)
        setWhitelistedApps(context, current)
    }
    
    /**
     * Remove an app from the whitelist
     */
    fun removeApp(context: Context, packageName: String) {
        val current = getWhitelistedApps(context).toMutableSet()
        current.remove(packageName)
        setWhitelistedApps(context, current)
    }
    
    /**
     * Check if an app is whitelisted
     */
    fun isWhitelisted(context: Context, packageName: String): Boolean {
        return packageName in getWhitelistedApps(context)
    }
}
