/*
 * Nextcloud Sentinel Android - Kill Switch Manager
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Central coordinator for threat detection and response.
 * Ported from C++/Qt desktop implementation.
 */

package com.nextcloud.sentinel.killswitch

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kill Switch Manager - Central threat detection and response coordinator
 *
 * This singleton coordinates multiple threat detectors and manages the response
 * to potential ransomware or mass deletion attacks. It can pause synchronization,
 * create backups, and alert the user.
 *
 * Architecture:
 *   KillSwitchManager
 *     ├── ThreatDetector[] (analyzers)
 *     │   ├── EntropyDetector
 *     │   ├── MassDeleteDetector
 *     │   └── CanaryDetector
 *     └── SyncAction[] (responses)
 *         ├── PauseSyncAction
 *         ├── BackupAction
 *         └── AlertAction
 *
 * Thread Safety:
 *   All public methods are thread-safe using @Synchronized.
 *   Event lists use CopyOnWriteArrayList for concurrent access.
 */
object KillSwitchManager {

    private const val TAG = "KillSwitchManager"

    // State flows for reactive UI
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _triggered = MutableStateFlow(false)
    val triggered: StateFlow<Boolean> = _triggered.asStateFlow()

    private val _threatLevel = MutableStateFlow(ThreatLevel.NONE)
    val threatLevel: StateFlow<ThreatLevel> = _threatLevel.asStateFlow()

    // Event flows for observers
    private val _threatDetected = MutableSharedFlow<ThreatInfo>()
    val threatDetected: SharedFlow<ThreatInfo> = _threatDetected.asSharedFlow()

    private val _syncPaused = MutableSharedFlow<String>()
    val syncPaused: SharedFlow<String> = _syncPaused.asSharedFlow()

    private val _syncResumed = MutableSharedFlow<Unit>()
    val syncResumed: SharedFlow<Unit> = _syncResumed.asSharedFlow()

    // Registered components
    private val detectors = CopyOnWriteArrayList<ThreatDetector>()
    private val actions = CopyOnWriteArrayList<SyncAction>()

    // Threat history
    private val threats = CopyOnWriteArrayList<ThreatInfo>()

    // Event tracking for time window analysis
    private val recentEvents = CopyOnWriteArrayList<FileEvent>()

    // Configuration
    var deleteThreshold: Int = 10
        @Synchronized set
    var windowSeconds: Int = 60
        @Synchronized set
    var entropyThreshold: Double = 7.5
        @Synchronized set
    var autoBackup: Boolean = true
        @Synchronized set

    // Window cleanup handler
    private val handler = Handler(Looper.getMainLooper())
    private val windowCleanupRunnable = object : Runnable {
        override fun run() {
            cleanupExpiredEvents()
            handler.postDelayed(this, 1000) // Every second
        }
    }

    init {
        Log.i(TAG, "Kill Switch Manager initialized")
        handler.post(windowCleanupRunnable)
    }

    /**
     * Enable or disable kill switch protection
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (_enabled.value != enabled) {
            _enabled.value = enabled
            Log.i(TAG, "Kill Switch ${if (enabled) "enabled" else "disabled"}")
        }
    }

    /**
     * Check if kill switch is enabled
     */
    fun isEnabled(): Boolean = _enabled.value

    /**
     * Check if kill switch has been triggered
     */
    fun isTriggered(): Boolean = _triggered.value

    /**
     * Get current threat level
     */
    fun currentThreatLevel(): ThreatLevel = _threatLevel.value

    /**
     * Register a threat detector
     */
    @Synchronized
    fun registerDetector(detector: ThreatDetector) {
        detectors.add(detector)
        Log.i(TAG, "Registered detector: ${detector.name}")
    }

    /**
     * Register a sync action to execute on threat detection
     */
    @Synchronized
    fun registerAction(action: SyncAction) {
        actions.add(action)
        Log.i(TAG, "Registered action: ${action.name}")
    }

    /**
     * Analyze a file for threats
     * @param file The file to analyze
     * @param eventType The type of file operation
     * @return true if the operation should be blocked
     */
    @Synchronized
    fun analyzeFile(file: File, eventType: FileEvent.EventType): Boolean {
        if (!_enabled.value || _triggered.value) {
            return _triggered.value // Block if already triggered
        }

        // Record the event
        recordEvent(eventType, file.absolutePath)

        // Run through all detectors
        for (detector in detectors) {
            if (!detector.enabled) continue

            val threat = detector.analyze(file, eventType, recentEvents.toList())

            if (threat.level != ThreatLevel.NONE) {
                threats.add(threat)

                Log.w(TAG, "Threat detected by ${threat.detectorName} " +
                        "- Level: ${threat.level} - ${threat.description}")

                // Emit threat event
                handler.post {
                    _threatDetected.tryEmit(threat)
                }

                if (threat.level.isActionable()) {
                    trigger(threat.description)
                    return true // Block this operation
                }
            }
        }

        evaluateThreatLevel()
        return false
    }

    /**
     * Analyze a batch of files
     * @param files List of file paths with event types
     * @return true if sync should be paused
     */
    @Synchronized
    fun analyzeBatch(files: List<Pair<File, FileEvent.EventType>>): Boolean {
        if (!_enabled.value) return false

        // Count deletions in batch
        val deleteCount = files.count { it.second == FileEvent.EventType.DELETE }

        // Immediate trigger on massive batch deletion
        if (deleteCount > deleteThreshold * 2) {
            trigger("Massive batch deletion detected: $deleteCount files")
            return true
        }

        // Analyze each file
        for ((file, eventType) in files) {
            if (analyzeFile(file, eventType)) {
                return true
            }
        }

        return _triggered.value
    }

    /**
     * Manually trigger the kill switch
     * @param reason The reason for triggering
     */
    @Synchronized
    fun trigger(reason: String) {
        if (_triggered.value) return // Already triggered

        _triggered.value = true
        _threatLevel.value = ThreatLevel.CRITICAL

        Log.e(TAG, "!!! KILL SWITCH TRIGGERED !!!")
        Log.e(TAG, "Reason: $reason")

        // Create threat info for this trigger
        val threat = ThreatInfo(
            level = ThreatLevel.CRITICAL,
            detectorName = "KillSwitchManager",
            description = reason,
            affectedFiles = emptyList()
        )

        // Execute all registered actions
        executeActions(threat)

        // Emit events
        handler.post {
            _syncPaused.tryEmit(reason)
        }
    }

    /**
     * Reset the kill switch after user confirmation
     */
    @Synchronized
    fun reset() {
        _triggered.value = false
        _threatLevel.value = ThreatLevel.NONE
        threats.clear()
        recentEvents.clear()

        // Reset all detectors
        detectors.forEach { it.reset() }

        Log.i(TAG, "Kill Switch reset by user")

        handler.post {
            _syncResumed.tryEmit(Unit)
        }
    }

    /**
     * Get the list of detected threats
     */
    fun getThreats(): List<ThreatInfo> = threats.toList()

    /**
     * Get registered detectors
     */
    fun getDetectors(): List<ThreatDetector> = detectors.toList()

    /**
     * Get recent events (for debugging)
     */
    fun getRecentEvents(): List<FileEvent> = recentEvents.toList()

    /**
     * Cleanup: call when shutting down
     */
    fun shutdown() {
        handler.removeCallbacks(windowCleanupRunnable)
        Log.i(TAG, "Kill Switch Manager shutdown")
    }

    // Private implementation

    private fun recordEvent(eventType: FileEvent.EventType, path: String) {
        val event = FileEvent(
            path = path,
            eventType = eventType,
            timestamp = System.currentTimeMillis()
        )
        recentEvents.add(event)
    }

    private fun cleanupExpiredEvents() {
        val cutoff = System.currentTimeMillis() - (windowSeconds * 1000L)
        recentEvents.removeAll { it.timestamp < cutoff }
    }

    private fun evaluateThreatLevel() {
        // Count recent deletions
        val deleteCount = recentEvents.count {
            it.eventType == FileEvent.EventType.DELETE
        }

        val newLevel = when {
            deleteCount >= deleteThreshold -> ThreatLevel.CRITICAL
            deleteCount >= (deleteThreshold * 0.7).toInt() -> ThreatLevel.HIGH
            deleteCount >= (deleteThreshold * 0.5).toInt() -> ThreatLevel.MEDIUM
            deleteCount >= (deleteThreshold * 0.3).toInt() -> ThreatLevel.LOW
            else -> ThreatLevel.NONE
        }

        if (newLevel != _threatLevel.value) {
            _threatLevel.value = newLevel

            if (newLevel >= ThreatLevel.CRITICAL && !_triggered.value) {
                trigger("Deletion threshold exceeded: $deleteCount files in $windowSeconds seconds")
            }
        }
    }

    private fun executeActions(threat: ThreatInfo) {
        for (action in actions) {
            Log.i(TAG, "Executing action: ${action.name}")
            try {
                action.execute(threat)
            } catch (e: Exception) {
                Log.e(TAG, "Action ${action.name} failed: ${e.message}")
            }
        }
    }
}

/**
 * Interface for sync actions executed on threat detection
 */
interface SyncAction {
    val name: String
    fun execute(threat: ThreatInfo)
}
