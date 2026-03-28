package com.kidsafe.secure.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class NsfwAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "NsfwAccessibilitySvc"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✓ NsfwAccessibilityService connected")
        NsfwActionManager.accessibilityService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used, we only need the service to perform global actions
    }

    override fun onInterrupt() {
        Log.d(TAG, "NsfwAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "NsfwAccessibilityService unbound")
        NsfwActionManager.accessibilityService = null
        return super.onUnbind(intent)
    }
}
