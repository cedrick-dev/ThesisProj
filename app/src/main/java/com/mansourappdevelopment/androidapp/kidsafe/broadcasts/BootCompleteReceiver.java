package com.mansourappdevelopment.androidapp.kidsafe.broadcasts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.mansourappdevelopment.androidapp.kidsafe.activities.LoginActivity;
import com.mansourappdevelopment.androidapp.kidsafe.services.MainForegroundService;

/**
 * Receives BOOT_COMPLETED and immediately relaunches the app UI after a device restart.
 *
 * Launch strategy:
 *  1. Always start MainForegroundService so background monitoring resumes.
 *  2. Attempt to open LoginActivity directly via startActivity().
 *       • Android < 10 (API 28-): always permitted from a BroadcastReceiver.
 *       • Android 10+ (API 29+): permitted when SYSTEM_ALERT_WINDOW is granted
 *         (the app requests this permission during child onboarding).
 *  3. If direct launch is not possible (overlay not yet granted on 10+),
 *     MainForegroundService posts a full-screen intent notification as fallback.
 *
 * LoginActivity.onStart() auto-redirects authenticated users to ChildSignedInActivity,
 * so no manual interaction is needed for a returning child.
 */
public class BootCompleteReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompleteReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":   // HTC devices
            case "com.htc.intent.action.QUICKBOOT_POWERON":   // HTC (legacy)
                Log.i(TAG, "Boot completed – restarting AegistNet");

                // ── Step 1: Start the background monitoring service ──────────────
                Intent serviceIntent = new Intent(context, MainForegroundService.class);
                serviceIntent.putExtra(MainForegroundService.EXTRA_LAUNCHED_FROM_BOOT, true);
                ContextCompat.startForegroundService(context, serviceIntent);

                // ── Step 2: Launch the app UI directly ───────────────────────────
                // On Android 10+, startActivity() from a background context is blocked
                // by default, BUT it IS explicitly allowed when SYSTEM_ALERT_WINDOW
                // (overlay) permission is granted — which this app requests during setup.
                // See: https://developer.android.com/guide/components/activities/background-starts
                boolean canLaunchDirectly =
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q   // Android 9-: always OK
                        || Settings.canDrawOverlays(context);            // Android 10+: OK if overlay granted

                if (canLaunchDirectly) {
                    try {
                        Intent launchIntent = new Intent(context, LoginActivity.class);
                        launchIntent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        launchIntent.putExtra(MainForegroundService.EXTRA_LAUNCHED_FROM_BOOT, true);
                        context.startActivity(launchIntent);
                        Log.i(TAG, "App UI launched directly on boot.");
                    } catch (Exception e) {
                        // Guard in case the OS still blocks it; service notification will handle it.
                        Log.w(TAG, "Direct launch failed, falling back to service notification: "
                                + e.getMessage());
                    }
                } else {
                    // Overlay not granted yet — the service will post a full-screen intent
                    // notification (showBootLaunchNotification / showBootLoginNotification).
                    Log.i(TAG, "SYSTEM_ALERT_WINDOW not granted – relying on service notification.");
                }
                break;

            default:
                break;
        }
    }
}
