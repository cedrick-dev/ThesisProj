package com.kidsafe.secure.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mansourappdevelopment.androidapp.kidsafe.activities.BlockedAppActivity
import com.mansourappdevelopment.androidapp.kidsafe.services.MainForegroundService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class NsfwAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "NsfwAccessibilitySvc"
        
        // Browsers and their URL bar Resource IDs
        private val BROWSER_PACKAGES = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar"
            // You can add more like edge, firefox here as needed
        )
    }

    private var isWebFilterEnabled = true
    private var filterListener: ValueEventListener? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✓ NsfwAccessibilityService connected")
        NsfwActionManager.accessibilityService = this
        setupFirebaseListener()
    }

    private fun setupFirebaseListener() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val dbRef = FirebaseDatabase.getInstance().getReference("users/childs/$uid/contentFilters/webFilter")

        filterListener = dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Default to true (active) if the value is missing in Firebase
                isWebFilterEnabled = snapshot.getValue(Boolean::class.java) ?: true
                Log.d(TAG, "Web filter remote state updated: $isWebFilterEnabled")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to read web filter state", error.toException())
            }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isWebFilterEnabled) return
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // 1. Check if the event comes from a supported browser
        if (BROWSER_PACKAGES.containsKey(packageName)) {
            
            // Only care about window state or window content changing to avoid spamming
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                
                Log.v(TAG, "Browser Event detected for $packageName")
                captureAndAnalyzeUrl(packageName)
            }
        }
    }

    private fun captureAndAnalyzeUrl(packageName: String) {
        val rootNode = rootInActiveWindow ?: return
        val urlBarId = BROWSER_PACKAGES[packageName] ?: return
        
        // Find the specific node containing the URL
        val urlNodes = rootNode.findAccessibilityNodeInfosByViewId(urlBarId)
        
        if (urlNodes != null && urlNodes.isNotEmpty()) {
            val urlNode = urlNodes[0]
            val urlText = urlNode.text?.toString()
            
            Log.d(TAG, "Captured URL: $urlText")
            
            if (urlText != null && UrlBlockerHelper.isUrlBlocked(urlText)) {
                Log.w(TAG, "BLOCKED URL DETECTED: $urlText")
                blockUrl(urlText)
            }
        }
        rootNode.recycle()
    }

    private fun blockUrl(url: String) {
        val intent = Intent(this, BlockedAppActivity::class.java).apply {
            putExtra(MainForegroundService.BLOCKED_APP_NAME_EXTRA, "Blocked Website: $url")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch BlockedAppActivity", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "NsfwAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "NsfwAccessibilityService unbound")
        
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null && filterListener != null) {
            val dbRef = FirebaseDatabase.getInstance().getReference("users/childs/$uid/contentFilters/webFilter")
            dbRef.removeEventListener(filterListener!!)
        }
        
        NsfwActionManager.accessibilityService = null
        return super.onUnbind(intent)
    }
}
