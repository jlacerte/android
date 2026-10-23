/*
 * Nextcloud Sentinel Android - Sync Event Listener
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Interface for listening to sync events and routing to KillSwitchManager.
 */

package com.nextcloud.sentinel.monitoring

import android.util.Log
import com.nextcloud.sentinel.killswitch.FileEvent
import com.nextcloud.sentinel.killswitch.KillSwitchManager
import com.nextcloud.sentinel.killswitch.ThreatLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File

/**
 * Interface for sync event listeners.
 *
 * Implement this interface to receive notifications about sync operations
 * and integrate with the Kill Switch protection system.
 */
interface SyncEventListener {
    /**
     * Called before a file is about to be synced (uploaded).
     *
     * @param file The file that will be synced
     * @return true to allow sync, false to block
     */
    fun onFileAboutToSync(file: File): Boolean

    /**
     * Called when a batch of files is about to be synced.
     *
     * @param files List of files to be synced
     * @return List of files that are allowed to sync (blocked files removed)
     */
    fun onBatchAboutToSync(files: List<File>): List<File>

    /**
     * Called when sync operation starts
     */
    fun onSyncStarted()

    /**
     * Called when sync operation completes successfully
     */
    fun onSyncCompleted()

    /**
     * Called when sync is paused (possibly due to threat detection)
     *
     * @param reason The reason for pausing
     */
    fun onSyncPaused(reason: String)

    /**
     * Called when sync is resumed after being paused
     */
    fun onSyncResumed()

    /**
     * Called when a sync error occurs
     *
     * @param error The error that occurred
     */
    fun onSyncError(error: Throwable)
}

/**
 * Default implementation of SyncEventListener that integrates with KillSwitchManager.
 *
 * This listener:
 * - Checks files against KillSwitchManager before allowing sync
 * - Blocks suspicious files from being uploaded
 * - Listens to KillSwitch state changes
 *
 * Usage:
 * ```kotlin
 * val listener = KillSwitchSyncListener()
 * listener.initialize()
 *
 * // Before syncing a file:
 * if (listener.onFileAboutToSync(file)) {
 *     // Proceed with sync
 * } else {
 *     // File blocked, don't sync
 * }
 * ```
 */
class KillSwitchSyncListener : SyncEventListener {

    companion object {
        private const val TAG = "KillSwitchSyncListener"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isInitialized = false
    private var syncPausedCallback: ((String) -> Unit)? = null
    private var syncResumedCallback: (() -> Unit)? = null

    /**
     * Initialize the listener and start observing KillSwitch state
     */
    fun initialize() {
        if (isInitialized) return

        // Listen to sync paused events from KillSwitch
        KillSwitchManager.syncPaused
            .onEach { reason ->
                Log.w(TAG, "Sync paused by KillSwitch: $reason")
                syncPausedCallback?.invoke(reason)
            }
            .launchIn(scope)

        // Listen to sync resumed events
        KillSwitchManager.syncResumed
            .onEach {
                Log.i(TAG, "Sync resumed after KillSwitch reset")
                syncResumedCallback?.invoke()
            }
            .launchIn(scope)

        // Listen to threat level changes
        KillSwitchManager.threatLevel
            .onEach { level ->
                if (level >= ThreatLevel.HIGH) {
                    Log.w(TAG, "Threat level elevated: $level")
                }
            }
            .launchIn(scope)

        isInitialized = true
        Log.i(TAG, "KillSwitchSyncListener initialized")
    }

    /**
     * Set callback for when sync is paused
     */
    fun setSyncPausedCallback(callback: (String) -> Unit) {
        syncPausedCallback = callback
    }

    /**
     * Set callback for when sync is resumed
     */
    fun setSyncResumedCallback(callback: () -> Unit) {
        syncResumedCallback = callback
    }

    @Suppress("ReturnCount")
    override fun onFileAboutToSync(file: File): Boolean {
        // If KillSwitch is already triggered, block all syncs
        if (KillSwitchManager.isTriggered()) {
            Log.w(TAG, "Sync blocked - KillSwitch is triggered: ${file.name}")
            return false
        }

        // If KillSwitch is disabled, allow all
        if (!KillSwitchManager.isEnabled()) {
            return true
        }

        // Analyze the file
        val shouldBlock = KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        if (shouldBlock) {
            Log.w(TAG, "File blocked by KillSwitch analysis: ${file.name}")
            return false
        }

        return true
    }

    @Suppress("ReturnCount")
    override fun onBatchAboutToSync(files: List<File>): List<File> {
        // If KillSwitch is triggered, block all
        if (KillSwitchManager.isTriggered()) {
            Log.w(TAG, "Batch sync blocked - KillSwitch is triggered")
            return emptyList()
        }

        // If disabled, allow all
        if (!KillSwitchManager.isEnabled()) {
            return files
        }

        // Build batch for analysis
        val batch = files.map { file ->
            file to FileEvent.EventType.MODIFY
        }

        // If batch analysis triggers, block all
        if (KillSwitchManager.analyzeBatch(batch)) {
            Log.w(TAG, "Batch sync blocked by KillSwitch")
            return emptyList()
        }

        // Filter out individually blocked files
        return files.filter { file ->
            val blocked = KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)
            if (blocked) {
                Log.w(TAG, "File removed from batch: ${file.name}")
            }
            !blocked
        }
    }

    override fun onSyncStarted() {
        Log.d(TAG, "Sync started")
    }

    override fun onSyncCompleted() {
        Log.d(TAG, "Sync completed")
    }

    override fun onSyncPaused(reason: String) {
        Log.w(TAG, "Sync paused: $reason")
    }

    override fun onSyncResumed() {
        Log.i(TAG, "Sync resumed")
    }

    override fun onSyncError(error: Throwable) {
        Log.e(TAG, "Sync error: ${error.message}", error)
    }
}

/**
 * Singleton accessor for the default KillSwitch sync listener.
 *
 * Usage:
 * ```kotlin
 * // In your sync code:
 * val listener = SyncEventListenerProvider.getListener()
 * if (listener.onFileAboutToSync(file)) {
 *     // Proceed with upload
 * }
 * ```
 */
object SyncEventListenerProvider {
    private val listener = KillSwitchSyncListener()
    private var initialized = false

    /**
     * Initialize the provider (call once at app startup)
     */
    @Synchronized
    fun initialize() {
        if (!initialized) {
            listener.initialize()
            initialized = true
        }
    }

    /**
     * Get the sync event listener instance
     */
    fun getListener(): SyncEventListener = listener

    /**
     * Check if a file should be allowed to sync
     * Convenience method that also ensures initialization
     */
    fun shouldAllowSync(file: File): Boolean {
        if (!initialized) initialize()
        return listener.onFileAboutToSync(file)
    }

    /**
     * Filter a batch of files, returning only those allowed to sync
     */
    fun filterBatch(files: List<File>): List<File> {
        if (!initialized) initialize()
        return listener.onBatchAboutToSync(files)
    }
}
