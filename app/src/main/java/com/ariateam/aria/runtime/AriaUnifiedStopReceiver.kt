package com.ariateam.aria.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ariateam.aria.speech.VoiceService
import com.ariateam.aria.vision.VisionForegroundService
import com.ariateam.aria.vision.VisionSession

/**
 * Handles the unified Stop action from the persistent foreground notification.
 * Terminates both VoiceService and VisionForegroundService atomically and persists the user-stop latch.
 */
class AriaUnifiedStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AriaUnifiedNotificationCoordinator.ACTION_STOP_ALL) return
        Log.i(TAG, "AriaUnifiedStopReceiver: Stopping all foreground services upon user action")

        // 1. Mark explicit user stop and stop camera
        VisionSession.markExplicitStop(context)
        VisionForegroundService.requestStop(context)

        // 2. Stop voice service
        try {
            context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
        } catch (error: Exception) {
            Log.e(TAG, "Failed to send ACTION_STOP to VoiceService", error)
        }

        // 3. Update runtime state
        AriaRuntimeStateBridge.setVisionActive(context, false)
        AriaRuntimeStateBridge.setVoiceActive(context, false)
    }

    companion object {
        private const val TAG = "AriaUnifiedStopReceiver"
    }
}
