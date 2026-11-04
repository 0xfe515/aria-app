package com.ariateam.aria.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ariateam.aria.MainActivity
import com.ariateam.aria.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * State tuple representing the active foreground state across voice and vision services.
 */
data class NotificationStateTuple(
    val voiceActive: Boolean,
    val visionActive: Boolean,
    val cameraUnavailable: Boolean,
)

/**
 * Coordinates a common persistent notification for Aria's foreground services (Voice + Vision).
 *
 * Ensures:
 * 1. Single unified notification channel and single ongoing notification ID (1001).
 * 2. Aggregated status display when both voice and vision are running.
 * 3. Graceful fallback when one service stops so the remaining service keeps the ongoing notification.
 * 4. Overall Stop action that terminates both VoiceService and VisionForegroundService.
 * 5. Single, truthful error notification (1002) that auto-cancels and never duplicates.
 * 6. Deduplicated updates: only posts or updates notification when the state tuple changes.
 */
object AriaUnifiedNotificationCoordinator {
    const val CHANNEL_ID = "aria_foreground_service"
    const val NOTIFICATION_ID = 1001
    const val ERROR_NOTIFICATION_ID = 1002
    const val ACTION_STOP_ALL = "com.ariateam.aria.ACTION_STOP_ALL"
    const val ACTION_UPDATE_NOTIFICATION = "com.ariateam.aria.ACTION_UPDATE_NOTIFICATION"

    private const val TAG = "AriaUnifiedNotif"

    @Volatile
    private var lastPostedTuple: NotificationStateTuple? = null
    private val notificationPostCount = AtomicInteger(0)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aria 백그라운드 서비스",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "음성 인식 및 비전 위험 모니터링 상태를 표시합니다."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    data class NotificationContent(
        val contentText: String,
        val stopActionLabel: String,
    )

    fun resolveNotificationContent(
        voiceActive: Boolean,
        visionActive: Boolean,
        cameraUnavailable: Boolean = false,
    ): NotificationContent {
        val contentText = when {
            voiceActive && visionActive -> {
                if (cameraUnavailable) {
                    "음성 인식 중 · 카메라 일시 사용 중 (재연결 대기)"
                } else {
                    "음성 인식 및 비전 모니터링 동작 중"
                }
            }
            visionActive -> {
                if (cameraUnavailable) {
                    "카메라 일시 사용 중 (재연결 대기)"
                } else {
                    "카메라로 주변 위험을 모니터링 중입니다."
                }
            }
            voiceActive -> "음성 대기 중입니다."
            else -> "Aria 모니터링 서비스"
        }

        val stopLabel = when {
            voiceActive && visionActive -> "모두 중지"
            visionActive -> "모니터링 중지"
            else -> "음성 인식 중지"
        }
        return NotificationContent(contentText, stopLabel)
    }

    fun buildUnifiedNotification(
        context: Context,
        voiceActive: Boolean,
        visionActive: Boolean,
        cameraUnavailable: Boolean = false,
    ): Notification {
        ensureChannel(context)

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopAllIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, AriaUnifiedStopReceiver::class.java).apply {
                action = ACTION_STOP_ALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val content = resolveNotificationContent(voiceActive, visionActive, cameraUnavailable)

        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(content.contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, content.stopActionLabel, stopAllIntent).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Updates the persistent ongoing notification only if the state tuple has actually changed,
     * or if [force] is true. When both services become inactive, cancels the ongoing notification.
     *
     * @return true if an update or cancellation occurred, false if deduplicated.
     */
    fun updateUnifiedNotification(
        context: Context?,
        voiceActive: Boolean,
        visionActive: Boolean,
        cameraUnavailable: Boolean? = null,
        force: Boolean = false,
    ): Boolean {
        val resolvedCameraUnavailable = cameraUnavailable ?: if (visionActive) {
            lastPostedTuple?.cameraUnavailable ?: false
        } else {
            false
        }
        val nextTuple = NotificationStateTuple(
            voiceActive = voiceActive,
            visionActive = visionActive,
            cameraUnavailable = resolvedCameraUnavailable,
        )

        if (!force && lastPostedTuple == nextTuple) {
            // Deduplicated: identical state tuple
            return false
        }

        val manager = context?.getSystemService(NotificationManager::class.java)
        if (manager == null) {
            // Unit test or null context: record tuple transition without platform notification call
            lastPostedTuple = nextTuple
            notificationPostCount.incrementAndGet()
            return true
        }

        if (!voiceActive && !visionActive) {
            // Neither service is active: cancel ongoing notification
            manager.cancel(NOTIFICATION_ID)
            lastPostedTuple = nextTuple
            notificationPostCount.incrementAndGet()
            return true
        }

        val notification = buildUnifiedNotification(
            context = context,
            voiceActive = voiceActive,
            visionActive = visionActive,
            cameraUnavailable = resolvedCameraUnavailable,
        )
        manager.notify(NOTIFICATION_ID, notification)
        lastPostedTuple = nextTuple
        notificationPostCount.incrementAndGet()
        return true
    }

    fun getLastPostedTuple(): NotificationStateTuple? = lastPostedTuple

    fun getNotificationPostCountForTesting(): Int = notificationPostCount.get()

    fun resetForTesting() {
        lastPostedTuple = null
        notificationPostCount.set(0)
    }

    fun postErrorNotification(context: Context, title: String, detail: String) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val openIntent = PendingIntent.getActivity(
            context,
            ERROR_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(ERROR_NOTIFICATION_ID, notification)
    }

    fun cancelErrorNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(ERROR_NOTIFICATION_ID)
    }
}
