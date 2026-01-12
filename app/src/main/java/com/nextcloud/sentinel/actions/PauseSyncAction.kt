/*
 * Nextcloud Sentinel Android - Pause Sync Action
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Action that pauses synchronization when a threat is detected.
 */

package com.nextcloud.sentinel.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nextcloud.sentinel.killswitch.SyncAction
import com.nextcloud.sentinel.killswitch.ThreatInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Interface for controlling sync operations.
 *
 * This interface abstracts the sync control mechanism, allowing
 * different implementations for the actual Nextcloud sync system.
 */
interface SyncController {
    /**
     * Pause all sync operations
     * @param reason The reason for pausing
     */
    fun pauseSync(reason: String)

    /**
     * Resume sync operations
     */
    fun resumeSync()

    /**
     * Check if sync is currently paused
     */
    fun isSyncPaused(): Boolean

    /**
     * Cancel all pending uploads
     */
    fun cancelPendingUploads()
}

/**
 * Action that pauses synchronization when a threat is detected.
 *
 * This action:
 * - Immediately pauses all sync operations
 * - Cancels pending uploads (optional)
 * - Logs the threat details
 * - Broadcasts an intent for other components to react
 *
 * Usage:
 * ```kotlin
 * val controller = NextcloudSyncController(context)
 * val action = PauseSyncAction(context, controller)
 * KillSwitchManager.registerAction(action)
 * ```
 */
class PauseSyncAction(
    private val context: Context,
    private val syncController: SyncController? = null,
    private val cancelPendingOnThreat: Boolean = true
) : SyncAction {

    companion object {
        private const val TAG = "PauseSyncAction"

        // Broadcast actions
        const val ACTION_SYNC_PAUSED_BY_KILLSWITCH = "com.nextcloud.sentinel.SYNC_PAUSED"
        const val ACTION_SYNC_RESUMED_BY_KILLSWITCH = "com.nextcloud.sentinel.SYNC_RESUMED"

        // Intent extras
        const val EXTRA_THREAT_LEVEL = "threat_level"
        const val EXTRA_THREAT_DESCRIPTION = "threat_description"
        const val EXTRA_THREAT_DETECTOR = "threat_detector"
        const val EXTRA_AFFECTED_FILES = "affected_files"
    }

    override val name: String = "PauseSyncAction"

    // State tracking
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private var lastThreat: ThreatInfo? = null

    override fun execute(threat: ThreatInfo) {
        Log.w(TAG, "!!! PAUSING SYNC - Threat detected !!!")
        Log.w(TAG, "Detector: ${threat.detectorName}")
        Log.w(TAG, "Level: ${threat.level}")
        Log.w(TAG, "Description: ${threat.description}")
        Log.w(TAG, "Affected files: ${threat.affectedFiles.size}")

        lastThreat = threat
        _isPaused.value = true

        // Pause sync via controller if available
        syncController?.let { controller ->
            controller.pauseSync(threat.description)

            if (cancelPendingOnThreat) {
                controller.cancelPendingUploads()
                Log.i(TAG, "Cancelled pending uploads")
            }
        }

        // Broadcast for other components
        broadcastSyncPaused(threat)
    }

    /**
     * Resume sync after user confirmation
     */
    fun resume() {
        if (!_isPaused.value) {
            Log.d(TAG, "Sync is not paused, nothing to resume")
            return
        }

        Log.i(TAG, "Resuming sync after KillSwitch reset")

        syncController?.resumeSync()
        _isPaused.value = false
        lastThreat = null

        broadcastSyncResumed()
    }

    /**
     * Get the threat that caused the pause
     */
    fun getLastThreat(): ThreatInfo? = lastThreat

    private fun broadcastSyncPaused(threat: ThreatInfo) {
        val intent = Intent(ACTION_SYNC_PAUSED_BY_KILLSWITCH).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_THREAT_LEVEL, threat.level.name)
            putExtra(EXTRA_THREAT_DESCRIPTION, threat.description)
            putExtra(EXTRA_THREAT_DETECTOR, threat.detectorName)
            putStringArrayListExtra(EXTRA_AFFECTED_FILES, ArrayList(threat.affectedFiles))
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: $ACTION_SYNC_PAUSED_BY_KILLSWITCH")
    }

    private fun broadcastSyncResumed() {
        val intent = Intent(ACTION_SYNC_RESUMED_BY_KILLSWITCH).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: $ACTION_SYNC_RESUMED_BY_KILLSWITCH")
    }
}

/**
 * Default implementation of SyncController that uses SharedPreferences
 * to signal pause/resume to other components.
 *
 * The actual Nextcloud sync code should check this flag before syncing.
 */
class DefaultSyncController(private val context: Context) : SyncController {

    companion object {
        private const val TAG = "DefaultSyncController"
        private const val PREFS_NAME = "killswitch_sync"
        private const val KEY_SYNC_PAUSED = "sync_paused"
        private const val KEY_PAUSE_REASON = "pause_reason"
        private const val KEY_PAUSE_TIMESTAMP = "pause_timestamp"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun pauseSync(reason: String) {
        prefs.edit()
            .putBoolean(KEY_SYNC_PAUSED, true)
            .putString(KEY_PAUSE_REASON, reason)
            .putLong(KEY_PAUSE_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Sync paused: $reason")
    }

    override fun resumeSync() {
        prefs.edit()
            .putBoolean(KEY_SYNC_PAUSED, false)
            .remove(KEY_PAUSE_REASON)
            .remove(KEY_PAUSE_TIMESTAMP)
            .apply()
        Log.i(TAG, "Sync resumed")
    }

    override fun isSyncPaused(): Boolean {
        return prefs.getBoolean(KEY_SYNC_PAUSED, false)
    }

    override fun cancelPendingUploads() {
        // This would need to integrate with Nextcloud's upload queue
        // For now, just log the intent
        Log.i(TAG, "Cancel pending uploads requested (needs Nextcloud integration)")

        // TODO: Integrate with Nextcloud's UploadFileOperation or FileUploadWorker
        // Something like:
        // FileUploadHelper.instance().cancelAll()
    }

    /**
     * Get the reason for the current pause
     */
    fun getPauseReason(): String? {
        return if (isSyncPaused()) {
            prefs.getString(KEY_PAUSE_REASON, null)
        } else null
    }

    /**
     * Get when sync was paused
     */
    fun getPauseTimestamp(): Long {
        return if (isSyncPaused()) {
            prefs.getLong(KEY_PAUSE_TIMESTAMP, 0)
        } else 0
    }
}
