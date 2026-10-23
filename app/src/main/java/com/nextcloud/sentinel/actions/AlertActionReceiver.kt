/*
 * Nextcloud Sentinel Android - Alert Action Receiver
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * BroadcastReceiver for handling notification action buttons.
 */

package com.nextcloud.sentinel.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.nextcloud.sentinel.integration.KillSwitchIntegration

/**
 * Handles notification action buttons from AlertAction notifications.
 *
 * Actions handled:
 * - DISMISS_THREAT: Dismiss notification but keep sync paused
 * - RESUME_SYNC: Reset Kill Switch and resume sync
 *
 * Register in AndroidManifest.xml:
 * ```xml
 * <receiver
 *     android:name="com.nextcloud.sentinel.actions.AlertActionReceiver"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="com.nextcloud.sentinel.DISMISS_THREAT" />
 *         <action android:name="com.nextcloud.sentinel.RESUME_SYNC" />
 *     </intent-filter>
 * </receiver>
 * ```
 */
class AlertActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlertActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(AlertAction.EXTRA_NOTIFICATION_ID, -1)

        Log.d(TAG, "Received action: ${intent.action}, notificationId: $notificationId")

        when (intent.action) {
            AlertAction.ACTION_DISMISS_THREAT -> {
                handleDismiss(context, notificationId)
            }

            AlertAction.ACTION_RESUME_SYNC -> {
                handleResume(context, notificationId)
            }

            AlertAction.ACTION_VIEW_THREAT -> {
                // View threat is handled by PendingIntent to Activity
                // This case is here for completeness if broadcast is used
                Log.d(TAG, "View threat action - should open AlertActivity")
            }

            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
    }

    /**
     * Handle dismiss action - just dismiss notification, keep sync paused.
     * User is aware of threat but wants to deal with it later.
     */
    private fun handleDismiss(context: Context, notificationId: Int) {
        Log.i(TAG, "Dismissing notification: $notificationId (sync remains paused)")

        // Dismiss the specific notification
        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        // Note: Sync remains paused - user must explicitly resume
    }

    /**
     * Handle resume action - reset Kill Switch and resume sync.
     * User acknowledges threat and wants to continue syncing.
     */
    private fun handleResume(context: Context, notificationId: Int) {
        Log.i(TAG, "Resuming sync - user acknowledged threat")

        // Dismiss notification
        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        // Reset Kill Switch (this also resumes sync and dismisses all notifications)
        KillSwitchIntegration.reset()

        // Show confirmation (optional toast)
        // Could also broadcast an event for UI to update
    }
}
