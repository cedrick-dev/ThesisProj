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

        // Mapping of app package names to their web domains.
        // When an app is blocked, these domains are auto-added to the dynamic blocklist.
        private val APP_TO_DOMAIN_MAP = mapOf(
            // Social Media
            "com.facebook.katana" to listOf("facebook.com", "fb.com", "m.facebook.com"),
            "com.facebook.lite" to listOf("facebook.com", "fb.com", "m.facebook.com"),
            "com.facebook.orca" to listOf("messenger.com", "m.me"),
            "com.instagram.android" to listOf("instagram.com"),
            "com.twitter.android" to listOf("twitter.com", "x.com"),
            "com.zhiliaoapp.musically" to listOf("tiktok.com"),  // TikTok
            "com.ss.android.ugc.trill" to listOf("tiktok.com"),  // TikTok (alternate)
            "com.snapchat.android" to listOf("snapchat.com"),
            "com.pinterest" to listOf("pinterest.com"),
            "com.reddit.frontpage" to listOf("reddit.com"),
            "com.tumblr" to listOf("tumblr.com"),
            
            // Messaging
            "com.whatsapp" to listOf("web.whatsapp.com", "whatsapp.com"),
            "org.telegram.messenger" to listOf("telegram.org", "web.telegram.org", "t.me"),
            "com.discord" to listOf("discord.com", "discord.gg"),
            "com.viber.voip" to listOf("viber.com"),
            
            // Video / Streaming
            "com.google.android.youtube" to listOf("youtube.com", "m.youtube.com", "youtu.be"),
            "com.netflix.mediaclient" to listOf("netflix.com"),
            "com.spotify.music" to listOf("spotify.com", "open.spotify.com"),
            
            // Gaming
            "com.roblox.client" to listOf("roblox.com"),
            
            // Dating
            "com.tinder" to listOf("tinder.com"),
            "com.bumble.app" to listOf("bumble.com")
        )
    }

    private var isWebFilterEnabled = true
    private var filterListener: ValueEventListener? = null
    
    // Dynamic blocklist from Firebase (parent-defined blocked sites)
    private val dynamicBlockedSites = mutableSetOf<String>()
    private var blockedSitesListener: ValueEventListener? = null
    
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
        setupDynamicBlocklistListener()
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

    /**
     * Listens to Firebase for parent-defined blocked sites.
     * Path: users/childs/$uid/blockedSites
     * Each child should have a "url" field (String).
     * Updates are applied in real-time alongside the static blocklist.
     */
    private fun setupDynamicBlocklistListener() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val blockedSitesRef = FirebaseDatabase.getInstance()
            .getReference("users/childs/$uid/blockedSites")

        blockedSitesListener = blockedSitesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newSites = mutableSetOf<String>()
                for (child in snapshot.children) {
                    val url = child.child("url").getValue(String::class.java)
                    if (!url.isNullOrBlank()) {
                        newSites.add(url.trim().lowercase())
                    }
                }
                synchronized(dynamicBlockedSites) {
                    dynamicBlockedSites.clear()
                    dynamicBlockedSites.addAll(newSites)
                }
                Log.d(TAG, "Dynamic blocklist updated: ${newSites.size} sites loaded")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to read dynamic blocklist", error.toException())
            }
        })
    }

    /**
     * Checks if a URL matches any entry in the parent-defined dynamic blocklist.
     * Performs substring matching (same as the static list approach).
     */
    private fun isDynamicallyBlocked(url: String): Boolean {
        val normalizedUrl = url.lowercase().replace("%20", " ").replace("+", " ")
        synchronized(dynamicBlockedSites) {
            for (blockedSite in dynamicBlockedSites) {
                if (normalizedUrl.contains(blockedSite)) {
                    return true
                }
            }
        }
        return false
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

                if (!wasBlocked && nowBlocked) {
                    // App just got blocked → auto-add its web domains to the dynamic blocklist
                    syncAppDomainToBlockedSites(pkg, block = true)
                } else if (wasBlocked && !nowBlocked) {
                    // App just got unblocked → remove auto-added domains and reset strike counter
                    syncAppDomainToBlockedSites(pkg, block = false)
                    
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
            
            if (urlText != null && (UrlBlockerHelper.isUrlBlocked(urlText) || isDynamicallyBlocked(urlText))) {
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
            if (isUrlLike && (UrlBlockerHelper.isUrlBlocked(text) || isDynamicallyBlocked(text))) {
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

    /**
     * Auto-syncs an app's known web domains to the Firebase blockedSites node.
     * When block=true, adds each domain with source="auto:{packageName}".
     * When block=false, removes only auto-added entries for that package.
     */
    private fun syncAppDomainToBlockedSites(packageName: String, block: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val domains = APP_TO_DOMAIN_MAP[packageName.trim().lowercase()]
        if (domains.isNullOrEmpty()) {
            Log.d(TAG, "No domain mapping for package: $packageName")
            return
        }

        val blockedSitesRef = FirebaseDatabase.getInstance()
            .getReference("users/childs/$uid/blockedSites")

        if (block) {
            // Add each domain with a source tag so we can identify auto-added entries
            for (domain in domains) {
                val entry = mapOf(
                    "url" to domain,
                    "source" to "auto:$packageName"
                )
                blockedSitesRef.push().setValue(entry)
                Log.i(TAG, "🌐 Auto-blocked website '$domain' (from app $packageName)")
            }
        } else {
            // Remove only auto-added entries for this specific package
            blockedSitesRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        val source = child.child("source").getValue(String::class.java)
                        if (source == "auto:$packageName") {
                            child.ref.removeValue()
                            val url = child.child("url").getValue(String::class.java)
                            Log.i(TAG, "🌐 Auto-unblocked website '$url' (from app $packageName)")
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to clean up auto-blocked sites", error.toException())
                }
            })
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
            if (blockedSitesListener != null) {
                val blockedSitesRef = FirebaseDatabase.getInstance().getReference("users/childs/$uid/blockedSites")
                blockedSitesRef.removeEventListener(blockedSitesListener!!)
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
