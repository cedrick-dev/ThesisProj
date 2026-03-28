package com.kidsafe.secure.services

import android.accessibilityservice.AccessibilityService
import android.util.Log

object NsfwActionManager {
    private const val TAG = "NsfwActionManager"
    var accessibilityService: AccessibilityService? = null

    fun performGoBack() {
        if (accessibilityService != null) {
            accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG, "Triggered AUTO-BACK via Accessibility Service")
        } else {
            Log.w(TAG, "Accessibility Service is NOT connected. Auto-back failed.")
        }
    }
}
