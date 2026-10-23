/*
 * Nextcloud Sentinel Android - Alert Action
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Action that shows a critical notification when a threat is detected.
 */

package com.nextcloud.sentinel.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nextcloud.sentinel.killswitch.SyncAction
import com.nextcloud.sentinel.killswitch.ThreatInfo
import com.nextcloud.sentinel.killswitch.ThreatLevel

/**
 * Action that displays a critical notification when a threat is detected.
 *
 * Features:
 * - High-priority notification (heads-up display)
 * - Sound and vibration
 * - Expandable with threat details
 * - Action buttons: "View Details", "Dismiss"
 * - Launches AlertActivity when tapped
 *
 * Usage:
 * ```kotlin
 * val action = AlertAction(context)
 * KillSwitchManager.registerAction(action)
 * ```
 */
class AlertAction(private val context: Context, private val alertActivityClass: Class<*>? = null) : SyncAction {

    companion object {
        private const val TAG = "AlertAction"

        // Notification configuration
        const val CHANNEL_ID = "killswitch_alerts"
        const val CHANNEL_NAME = "Kill Switch Alerts"
        const val CHANNEL_DESCRIPTION = "Critical security alerts from Kill Switch protection"

        const val NOTIFICATION_ID_BASE = 9900
        private var notificationCounter = 0

        // Intent actions
        const val ACTION_VIEW_THREAT = "com.nextcloud.sentinel.VIEW_THREAT"
        const val ACTION_DISMISS_THREAT = "com.nextcloud.sentinel.DISMISS_THREAT"
        const val ACTION_RESUME_SYNC = "com.nextcloud.sentinel.RESUME_SYNC"

        // Intent extras
        const val EXTRA_THREAT_ID = "threat_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        private const val MAX_LISTED_FILES = 5
        private const val DISMISS_REQUEST_OFFSET = 1000
        private const val RESUME_REQUEST_OFFSET = 2000
        private val CHANNEL_VIBRATION_PATTERN = longArrayOf(0, 500, 200, 500)
        private val NOTIFICATION_VIBRATION_PATTERN = longArrayOf(0, 500, 200, 500, 200, 500)
    }

    override val name: String = "AlertAction"

    private var channelCreated = false
    private val threats = mutableMapOf<Int, ThreatInfo>()

    init {
        createNotificationChannel()
    }

    override fun execute(threat: ThreatInfo) {
        Log.w(TAG, "Showing alert for threat: ${threat.description}")

        val notificationId = NOTIFICATION_ID_BASE + (notificationCounter++)
        threats[notificationId] = threat

        showNotification(threat, notificationId)
    }

    /**
     * Create the notification channel (required for Android O+)
     */
    private fun createNotificationChannel() {
        if (channelCreated) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH

            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                vibrationPattern = CHANNEL_VIBRATION_PATTERN
                enableLights(true)
                lightColor = 0xFFFF0000.toInt() // Red

                // Set sound
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)

                // Show on lock screen
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)

            Log.i(TAG, "Notification channel created: $CHANNEL_ID")
        }

        channelCreated = true
    }

    /**
     * Show the notification for a threat
     */
    private fun showNotification(threat: ThreatInfo, notificationId: Int) {
        // Build content intent (open alert activity)
        val contentIntent = buildContentIntent(threat, notificationId)

        // Build action intents
        val dismissIntent = buildDismissIntent(notificationId)
        val resumeIntent = buildResumeIntent(notificationId)

        // Build notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getTitleForLevel(threat.level))
            .setContentText(threat.description)
            .setStyle(buildExpandedStyle(threat))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false) // Don't auto-cancel, user must acknowledge
            .setOngoing(true) // Persistent until dismissed
            .setContentIntent(contentIntent)
            .setColor(getColorForLevel(threat.level))
            .addAction(
                android.R.drawable.ic_menu_view,
                "View Details",
                contentIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss",
                dismissIntent
            )

        // Add resume action only if sync is paused
        if (threat.level >= ThreatLevel.HIGH) {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Resume Sync",
                resumeIntent
            )
        }

        // Vibration pattern
        builder.setVibrate(NOTIFICATION_VIBRATION_PATTERN)

        // Sound
        builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))

        // Show notification
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            Log.i(TAG, "Notification shown: $notificationId")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for notification", e)
        }
    }

    /**
     * Dismiss a specific notification
     */
    fun dismissNotification(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
        threats.remove(notificationId)
        Log.d(TAG, "Notification dismissed: $notificationId")
    }

    /**
     * Dismiss all Kill Switch notifications
     */
    fun dismissAll() {
        threats.keys.forEach { id ->
            NotificationManagerCompat.from(context).cancel(id)
        }
        threats.clear()
        Log.d(TAG, "All notifications dismissed")
    }

    /**
     * Get a stored threat by notification ID
     */
    fun getThreat(notificationId: Int): ThreatInfo? = threats[notificationId]

    private fun getTitleForLevel(level: ThreatLevel): String = when (level) {
        ThreatLevel.CRITICAL -> "CRITICAL: Ransomware Detected!"
        ThreatLevel.HIGH -> "WARNING: Suspicious Activity"
        ThreatLevel.MEDIUM -> "Alert: Unusual File Activity"
        ThreatLevel.LOW -> "Notice: File Activity Detected"
        ThreatLevel.NONE -> "Kill Switch Status"
    }

    private fun getColorForLevel(level: ThreatLevel): Int = when (level) {
        ThreatLevel.CRITICAL -> 0xFFFF0000.toInt()

        // Red
        ThreatLevel.HIGH -> 0xFFFF6600.toInt()

        // Orange
        ThreatLevel.MEDIUM -> 0xFFFFCC00.toInt()

        // Yellow
        ThreatLevel.LOW -> 0xFF0099FF.toInt()

        // Blue
        ThreatLevel.NONE -> 0xFF00CC00.toInt() // Green
    }

    private fun buildExpandedStyle(threat: ThreatInfo): NotificationCompat.BigTextStyle {
        val details = StringBuilder()
        details.appendLine("Detector: ${threat.detectorName}")
        details.appendLine("Level: ${threat.level}")
        details.appendLine()
        details.appendLine(threat.description)

        if (threat.affectedFiles.isNotEmpty()) {
            details.appendLine()
            details.appendLine("Affected files (${threat.affectedFiles.size}):")
            threat.affectedFiles.take(MAX_LISTED_FILES).forEach { file ->
                details.appendLine("  - ${file.substringAfterLast("/")}")
            }
            if (threat.affectedFiles.size > MAX_LISTED_FILES) {
                details.appendLine("  ... and ${threat.affectedFiles.size - MAX_LISTED_FILES} more")
            }
        }

        details.appendLine()
        details.appendLine("Sync has been paused for your protection.")

        return NotificationCompat.BigTextStyle()
            .bigText(details.toString())
            .setBigContentTitle(getTitleForLevel(threat.level))
    }

    private fun buildContentIntent(threat: ThreatInfo, notificationId: Int): PendingIntent {
        val intent = if (alertActivityClass != null) {
            Intent(context, alertActivityClass)
        } else {
            // Default: just open the app's main activity
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent()
        }

        intent.apply {
            action = ACTION_VIEW_THREAT
            putExtra(EXTRA_THREAT_ID, threat.timestamp.toEpochMilli())
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildDismissIntent(notificationId: Int): PendingIntent {
        val intent = Intent(ACTION_DISMISS_THREAT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        return PendingIntent.getBroadcast(
            context,
            notificationId + DISMISS_REQUEST_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildResumeIntent(notificationId: Int): PendingIntent {
        val intent = Intent(ACTION_RESUME_SYNC).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }

        return PendingIntent.getBroadcast(
            context,
            notificationId + RESUME_REQUEST_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
