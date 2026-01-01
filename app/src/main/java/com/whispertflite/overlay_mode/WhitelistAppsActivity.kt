package com.whispertflite.overlay_mode

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whispertflite.R
import kotlinx.coroutines.*

/**
 * Activity to select which apps should show the voice overlay
 */
class WhitelistAppsActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whitelist_apps)
        
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        
        recyclerView = findViewById(R.id.recycler_apps)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // Load apps in background
        scope.launch {
            val apps = withContext(Dispatchers.IO) { getInstalledApps() }
            adapter = AppListAdapter(apps)
            recyclerView.adapter = adapter
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
    
    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val whitelisted = WhitelistManager.getWhitelistedApps(this)
        
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { 
                // Only show apps with launcher activity (user apps)
                pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = appInfo,
                    isWhitelisted = appInfo.packageName in whitelisted
                )
            }
            .sortedWith(compareByDescending<AppInfo> { it.isWhitelisted }.thenBy { it.appName })
    }
    
    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: ApplicationInfo,
        var isWhitelisted: Boolean
    )
    
    inner class AppListAdapter(private val apps: List<AppInfo>) : 
        RecyclerView.Adapter<AppListAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val name: TextView = view.findViewById(R.id.app_name)
            val toggle: SwitchCompat = view.findViewById(R.id.app_checkbox)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            
            holder.icon.setImageDrawable(packageManager.getApplicationIcon(app.icon))
            holder.name.text = app.appName
            holder.toggle.isChecked = app.isWhitelisted
            
            holder.toggle.setOnCheckedChangeListener { _, isChecked ->
                app.isWhitelisted = isChecked
                if (isChecked) {
                    WhitelistManager.addApp(this@WhitelistAppsActivity, app.packageName)
                } else {
                    WhitelistManager.removeApp(this@WhitelistAppsActivity, app.packageName)
                }
            }
            
            holder.itemView.setOnClickListener {
                holder.toggle.isChecked = !holder.toggle.isChecked
            }
        }
        
        override fun getItemCount() = apps.size
    }
}
