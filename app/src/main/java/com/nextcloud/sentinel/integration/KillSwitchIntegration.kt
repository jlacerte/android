/*
 * Nextcloud Sentinel Android - Kill Switch Integration
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Integration layer between Kill Switch and Nextcloud sync system.
 */

package com.nextcloud.sentinel.integration

import android.content.Context
import android.util.Log
import com.nextcloud.sentinel.actions.AlertAction
import com.nextcloud.sentinel.actions.DefaultSyncController
import com.nextcloud.sentinel.actions.PauseSyncAction
import com.nextcloud.sentinel.killswitch.FileEvent
import com.nextcloud.sentinel.killswitch.KillSwitchManager
import com.nextcloud.sentinel.killswitch.ThreatLevel
import com.nextcloud.sentinel.killswitch.detectors.CanaryDetector
import com.nextcloud.sentinel.killswitch.detectors.EntropyDetector
import com.nextcloud.sentinel.killswitch.detectors.MassDeleteDetector
import com.nextcloud.sentinel.monitoring.FileChangeObserverManager
import com.nextcloud.sentinel.monitoring.SyncEventListenerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File

/**
 * Central integration point for Kill Switch with Nextcloud Android.
 *
 * This object handles:
 * - Initialization at app startup
 * - Registration of detectors and actions
 * - File monitoring setup
 * - Upload permission checks
 *
 * Usage in MainApp.java onCreate():
 * ```java
 * // Initialize Kill Switch protection
 * KillSwitchIntegration.INSTANCE.initialize(this);
 * ```
 *
 * Usage in FileUploadWorker.kt before upload:
 * ```kotlin
 * if (!KillSwitchIntegration.shouldAllowUpload(file)) {
 *     Log_OC.w(TAG, "Upload blocked by Kill Switch: ${file.name}")
 *     return Result.failure()
 * }
 * ```
 */
object KillSwitchIntegration {

    private const val TAG = "KillSwitchIntegration"

    private var isInitialized = false
    private var applicationContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fileObserverManager: FileChangeObserverManager? = null
    private var pauseSyncAction: PauseSyncAction? = null
    private var alertAction: AlertAction? = null

    /**
     * Initialize Kill Switch protection.
     *
     * Call this once in MainApp.onCreate().
     *
     * @param context Application context
     * @param syncFolderPaths List of paths to monitor (e.g., Nextcloud sync folders)
     */
    @Synchronized
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        syncFolderPaths: List<String> = emptyList()
    ) {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        applicationContext = context.applicationContext
        Log.i(TAG, "Initializing Kill Switch protection...")

        // Register detectors
        registerDetectors()

        // Register actions
        registerActions(context)

        // Setup event listeners
        setupEventListeners()

        // Initialize sync event listener
        SyncEventListenerProvider.initialize()

        // Start file monitoring if paths provided
        if (syncFolderPaths.isNotEmpty()) {
            startFileMonitoring(syncFolderPaths)
        }

        // Enable Kill Switch
        KillSwitchManager.setEnabled(true)

        isInitialized = true
        Log.i(TAG, "Kill Switch protection initialized")
    }

    /**
     * Register all threat detectors.
     */
    private fun registerDetectors() {
        // Entropy detector for encrypted files
        KillSwitchManager.registerDetector(EntropyDetector())

        // Mass delete detector
        KillSwitchManager.registerDetector(MassDeleteDetector())

        // Canary detector (trap files) - needs to be configured separately
        // KillSwitchManager.registerDetector(CanaryDetector())

        Log.i(TAG, "Detectors registered: Entropy, MassDelete")
    }

    /**
     * Register response actions.
     */
    private fun registerActions(context: Context) {
        val syncController = DefaultSyncController(context)

        // Pause sync action
        pauseSyncAction = PauseSyncAction(context, syncController, cancelPendingOnThreat = true)
        KillSwitchManager.registerAction(pauseSyncAction!!)

        // Alert notification action
        alertAction = AlertAction(context)
        KillSwitchManager.registerAction(alertAction!!)

        Log.i(TAG, "Actions registered: PauseSync, Alert")
    }

    /**
     * Setup event listeners for threat detection.
     */
    private fun setupEventListeners() {
        // Listen to threat detections
        KillSwitchManager.threatDetected
            .onEach { threat ->
                Log.w(TAG, "!!! THREAT DETECTED: ${threat.level} - ${threat.description}")
            }
            .launchIn(scope)

        // Listen to sync paused events
        KillSwitchManager.syncPaused
            .onEach { reason ->
                Log.w(TAG, "Sync paused: $reason")
            }
            .launchIn(scope)
    }

    /**
     * Start monitoring sync folders for file changes.
     *
     * @param paths List of folder paths to monitor
     */
    @JvmStatic
    fun startFileMonitoring(paths: List<String>) {
        if (fileObserverManager == null) {
            fileObserverManager = FileChangeObserverManager()
        }

        paths.forEach { path ->
            val file = File(path)
            if (file.exists() && file.isDirectory) {
                fileObserverManager?.addWatchPath(path, recursive = true)
                Log.i(TAG, "Monitoring: $path")
            } else {
                Log.w(TAG, "Cannot monitor (not a directory): $path")
            }
        }

        fileObserverManager?.startAll()
    }

    /**
     * Stop file monitoring.
     */
    @JvmStatic
    fun stopFileMonitoring() {
        fileObserverManager?.stopAll()
        Log.i(TAG, "File monitoring stopped")
    }

    /**
     * Check if a file should be allowed to upload.
     *
     * Use this in FileUploadWorker before processing an upload.
     *
     * @param file The file to check
     * @return true if upload is allowed, false if blocked by Kill Switch
     */
    @JvmStatic
    fun shouldAllowUpload(file: File): Boolean {
        return SyncEventListenerProvider.shouldAllowSync(file)
    }

    /**
     * Check if a file should be allowed to upload (by path).
     *
     * @param filePath Path to the file
     * @return true if upload is allowed, false if blocked
     */
    @JvmStatic
    fun shouldAllowUpload(filePath: String): Boolean {
        return shouldAllowUpload(File(filePath))
    }

    /**
     * Filter a batch of files, returning only those allowed to upload.
     *
     * Use this for batch uploads.
     *
     * @param files List of files to filter
     * @return List of files that are allowed to upload
     */
    @JvmStatic
    fun filterBatch(files: List<File>): List<File> {
        return SyncEventListenerProvider.filterBatch(files)
    }

    /**
     * Check if Kill Switch is currently triggered (threat detected).
     */
    @JvmStatic
    fun isTriggered(): Boolean {
        return KillSwitchManager.isTriggered()
    }

    /**
     * Check if Kill Switch protection is enabled.
     */
    @JvmStatic
    fun isEnabled(): Boolean {
        return KillSwitchManager.isEnabled()
    }

    /**
     * Enable or disable Kill Switch protection.
     */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        KillSwitchManager.setEnabled(enabled)
        Log.i(TAG, "Kill Switch ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Get the current threat level.
     */
    @JvmStatic
    fun getCurrentThreatLevel(): ThreatLevel {
        return KillSwitchManager.threatLevel.value
    }

    /**
     * Reset Kill Switch after user confirmation.
     *
     * Call this when user acknowledges the threat and wants to resume sync.
     */
    @JvmStatic
    fun reset() {
        KillSwitchManager.reset()
        pauseSyncAction?.resume()
        alertAction?.dismissAll()
        Log.i(TAG, "Kill Switch reset - sync resumed")
    }

    /**
     * Add a canary pattern (trap file pattern) to detect ransomware.
     *
     * @param pattern Pattern for canary file (e.g., ".killswitch_canary")
     */
    @JvmStatic
    fun addCanaryPattern(pattern: String) {
        // Find canary detector and add pattern
        KillSwitchManager.getDetectors()
            .filterIsInstance<CanaryDetector>()
            .firstOrNull()
            ?.addCanaryPattern(pattern)
            ?: Log.w(TAG, "CanaryDetector not registered")
    }

    /**
     * Shutdown Kill Switch (call in Application.onTerminate if needed).
     */
    @JvmStatic
    fun shutdown() {
        stopFileMonitoring()
        KillSwitchManager.shutdown()
        isInitialized = false
        Log.i(TAG, "Kill Switch shutdown")
    }

    /**
     * Analyze a file event manually.
     *
     * Use this when you have file change events from ContentObserverWork.
     *
     * @param file The file that changed
     * @param eventType Type of change (CREATE, MODIFY, DELETE)
     * @return true if threat was detected and actions triggered
     */
    @JvmStatic
    fun analyzeFileChange(file: File, eventType: FileEvent.EventType): Boolean {
        return KillSwitchManager.analyzeFile(file, eventType)
    }
}
