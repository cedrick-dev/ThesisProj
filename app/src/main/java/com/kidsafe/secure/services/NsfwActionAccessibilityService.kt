package com.kidsafe.secure.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

class NsfwActionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NsfwActionService"
        const val ACTION_TRIGGER_BACK = "com.kidsafe.secure.ACTION_TRIGGER_BACK"
        
        var isServiceEnabled = false
            private set
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TRIGGER_BACK) {
                Log.d(TAG, "Received broadcast to trigger BACK action")
                if (isServiceEnabled) {
                    val success = performGlobalAction(GLOBAL_ACTION_BACK)
                    Log.d(TAG, "Perform global back action: $success")
                } else {
                    Log.w(TAG, "Cannot perform back action: AccessibilityService is not enabled")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        Log.d(TAG, "Accessibility Service Connected")
        
        // Register receiver for the trigger action
        val filter = IntentFilter(ACTION_TRIGGER_BACK)
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only use this service for global actions, no need to process events
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
        isServiceEnabled = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceEnabled = false
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Receiver not registered or already unregistered", e)
        }
        Log.d(TAG, "Accessibility Service Destroyed")
    }
}
