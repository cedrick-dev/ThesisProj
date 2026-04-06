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
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import android.content.Context

class NsfwAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "NsfwAccessibilitySvc"
        
        // Browsers and their URL bar Resource IDs
        private val BROWSER_PACKAGES = mapOf(
            "com.android.chrome" to listOf(
                "com.android.chrome:id/url_bar"
            ),
            "com.google.android.googlequicksearchbox" to listOf(
                "com.google.android.googlequicksearchbox:id/googleapp_search_box",
                "com.google.android.googlequicksearchbox:id/url_bar", // Some Custom Tab versions
                "com.google.android.googlequicksearchbox:id/search_box"
            )
        )
    }

    private var isWebFilterEnabled = true
    private var filterListener: ValueEventListener? = null
    
    // Strike tracking
    private var lastStrikeTime = 0L
    private val STRIKE_THROTTLE_MS = 2000L // 2 seconds per strike count
    
    private var appUnblockListener: ChildEventListener? = null
    private val previousBlockedStates = mutableMapOf<String, Boolean>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✓ NsfwAccessibilityService connected")
        NsfwActionManager.accessibilityService = this
        setupFirebaseListener()
        listenForParentUnblocks()
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

    private fun listenForParentUnblocks() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val appsRef = FirebaseDatabase.getInstance().getReference("users/childs/$uid/apps")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val pkg = snapshot.child("packageName").getValue(String::class.java) ?: return
                val blocked = snapshot.child("blocked").getValue(Boolean::class.java) ?: false
                previousBlockedStates[pkg] = blocked
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val pkg = snapshot.child("packageName").getValue(String::class.java) ?: return
                val nowBlocked = snapshot.child("blocked").getValue(Boolean::class.java) ?: false
                val wasBlocked = previousBlockedStates[pkg] ?: false

                previousBlockedStates[pkg] = nowBlocked

                if (wasBlocked && !nowBlocked) {
                    val prefs = getSharedPreferences("WebFilterIncidentPrefs", Context.MODE_PRIVATE)
                    val countKey = "web_strike_count_$pkg"
                    val previous = prefs.getInt(countKey, 0)
                    if (previous > 0) {
                        prefs.edit().putInt(countKey, 0).apply()
                        Log.i(TAG, "🔓 Parent unblocked '$pkg' — Web Strike counter reset from $previous → 0")
                    }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val pkg = snapshot.child("packageName").getValue(String::class.java) ?: return
                previousBlockedStates.remove(pkg)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "App unblock listener cancelled: ${error.message}")
            }
        }

        appsRef.addChildEventListener(listener)
        appUnblockListener = listener
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isWebFilterEnabled) return
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // 1. Check if the event comes from a supported browser
        if (BROWSER_PACKAGES.containsKey(packageName)) {
            
            // Only care about window state, window content, or focusing/clicking to avoid skipping
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                
                Log.v(TAG, "Discovery Mode: Browser app interaction detected from $packageName")
                captureAndAnalyzeUrl(packageName)
            }
        }
    }

    private fun captureAndAnalyzeUrl(packageName: String) {
        val rootNode = rootInActiveWindow ?: return
        
        // --- RECURSIVE DISCOVERY MODE ---
        // Instead of only using IDs, we scan the whole screen for nodes containing URLs
        val urlNode = findUrlNodeRecursively(rootNode, packageName)
        
        if (urlNode != null) {
            val urlText = urlNode.text?.toString()
            Log.d(TAG, "✓ discovery: Found URL in $packageName: $urlText")
            
            if (urlText != null && UrlBlockerHelper.isUrlBlocked(urlText)) {
                Log.e(TAG, "🚫 BLOCKED URL DISCOVERED in $packageName: $urlText")
                incrementWebStrikeCounter(packageName)
                blockUrl(urlText)
            }
        }
        rootNode.recycle()
    }

    private fun findUrlNodeRecursively(node: AccessibilityNodeInfo?, packageName: String): AccessibilityNodeInfo? {
        if (node == null) return null
        
        // Check current node text
        val text = node.text?.toString()?.lowercase() ?: ""
        
        // Most URL bars in browser packages either have IDs or look like URLs/Search labels
        // We match common TLDs or explicit keyword discovery
        if (text.isNotEmpty()) {
            val isUrlLike = text.contains(".") || text.contains("/") || text.contains("search")
            if (isUrlLike && UrlBlockerHelper.isUrlBlocked(text)) {
                Log.v(TAG, "→ Matching node found in scan: [$text]")
                return node
            }
        }

        // Specifically check the Resource IDs we know about to favor them first
        val urlBarIds = BROWSER_PACKAGES[packageName] ?: emptyList<String>()
        for (id in urlBarIds) {
            if (node.viewIdResourceName == id) return node
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findUrlNodeRecursively(child, packageName)
            if (found != null) return found
        }

        return null
    }

    private fun incrementWebStrikeCounter(appPackage: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastStrikeTime > STRIKE_THROTTLE_MS) {
            lastStrikeTime = currentTime
            
            val prefs = getSharedPreferences("WebFilterIncidentPrefs", Context.MODE_PRIVATE)
            val countKey = "web_strike_count_$appPackage"
            var currentCount = prefs.getInt(countKey, 0)
            currentCount++
            prefs.edit().putInt(countKey, currentCount).apply()
            
            Log.w(TAG, "🕸️ WEB VIOLATION! Incident detected for $appPackage.")
            
            // 1-strike lock: Block the app immediately
            Log.e(TAG, "🚫 WEB THRESHOLD REACHED! Locking $appPackage immediately on first strike.")
            blockAppAutomatically(uid, appPackage)
        } else {
            Log.v(TAG, "Web strike throttled for $appPackage")
        }
    }

    private fun blockAppAutomatically(uid: String, appPackage: String) {
        val appsRef = FirebaseDatabase.getInstance().getReference("users/childs/$uid/apps")
        appsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    var blocked = false
                    val targetPkg = appPackage.trim().lowercase()
                    
                    for (appSnapshot in snapshot.children) {
                        try {
                            val pName = appSnapshot.child("packageName").getValue(String::class.java)?.trim()?.lowercase()
                            if (pName == targetPkg) {
                                appSnapshot.ref.child("blocked").setValue(true)
                                Log.e(TAG, "🚫 AUTOMATICALLY BLOCKED browser: $appPackage due to web filter violations.")
                                blocked = true
                                break
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking app package names", e)
                        }
                    }
                    if (!blocked) {
                        Log.w(TAG, "Could not find $appPackage in Firebase apps list to auto-block it.")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to fetch apps for auto-blocking", error.toException())
            }
        })
    }

    private fun blockUrl(url: String) {
        val intent = Intent(this, BlockedAppActivity::class.java).apply {
            putExtra(MainForegroundService.BLOCKED_APP_NAME_EXTRA, "Inappropriate Content")
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
        if (uid != null) {
            if (filterListener != null) {
                val dbRef = FirebaseDatabase.getInstance().getReference("users/childs/$uid/contentFilters/webFilter")
                dbRef.removeEventListener(filterListener!!)
            }
            if (appUnblockListener != null) {
                val appsRef = FirebaseDatabase.getInstance().getReference("users/childs/$uid/apps")
                appsRef.removeEventListener(appUnblockListener!!)
            }
        }
        
        NsfwActionManager.accessibilityService = null
        return super.onUnbind(intent)
    }
}
