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
 *   Events are emitted with tryEmit on buffered flows, so no Looper is needed
 *   and the manager can be exercised in plain JVM unit tests.
 */
@Suppress("TooManyFunctions")
object KillSwitchManager {

    private const val TAG = "KillSwitchManager"

    private const val DEFAULT_DELETE_THRESHOLD = 10
    private const val DEFAULT_WINDOW_SECONDS = 60
    private const val DEFAULT_ENTROPY_THRESHOLD = 7.5
    private const val MILLIS_PER_SECOND = 1000L
    private const val EVENT_BUFFER_CAPACITY = 64
    private const val HIGH_RATIO = 0.7
    private const val MEDIUM_RATIO = 0.5
    private const val LOW_RATIO = 0.3

    // State flows for reactive UI
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _triggered = MutableStateFlow(false)
    val triggered: StateFlow<Boolean> = _triggered.asStateFlow()

    private val _threatLevel = MutableStateFlow(ThreatLevel.NONE)
    val threatLevel: StateFlow<ThreatLevel> = _threatLevel.asStateFlow()

    // Event flows for observers
    private val _threatDetected = MutableSharedFlow<ThreatInfo>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    val threatDetected: SharedFlow<ThreatInfo> = _threatDetected.asSharedFlow()

    private val _syncPaused = MutableSharedFlow<String>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    val syncPaused: SharedFlow<String> = _syncPaused.asSharedFlow()

    private val _syncResumed = MutableSharedFlow<Unit>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    val syncResumed: SharedFlow<Unit> = _syncResumed.asSharedFlow()

    // Registered components
    private val detectors = CopyOnWriteArrayList<ThreatDetector>()
    private val actions = CopyOnWriteArrayList<SyncAction>()

    // Threat history
    private val threats = CopyOnWriteArrayList<ThreatInfo>()

    // Event tracking for time window analysis
    private val recentEvents = CopyOnWriteArrayList<FileEvent>()

    // Configuration
    var deleteThreshold: Int = DEFAULT_DELETE_THRESHOLD
        @Synchronized set

    var windowSeconds: Int = DEFAULT_WINDOW_SECONDS
        @Synchronized set

    var entropyThreshold: Double = DEFAULT_ENTROPY_THRESHOLD
        @Synchronized set

    var autoBackup: Boolean = true
        @Synchronized set

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (_enabled.value != enabled) {
            _enabled.value = enabled
            Log.i(TAG, "Kill Switch ${if (enabled) "enabled" else "disabled"}")
        }
    }

    fun isEnabled(): Boolean = _enabled.value

    fun isTriggered(): Boolean = _triggered.value

    fun currentThreatLevel(): ThreatLevel = _threatLevel.value

    @Synchronized
    fun registerDetector(detector: ThreatDetector) {
        detectors.add(detector)
        Log.i(TAG, "Registered detector: ${detector.name}")
    }

    @Synchronized
    fun unregisterDetector(detector: ThreatDetector) {
        detectors.remove(detector)
    }

    @Synchronized
    fun clearDetectors() {
        detectors.clear()
    }

    @Synchronized
    fun registerAction(action: SyncAction) {
        actions.add(action)
        Log.i(TAG, "Registered action: ${action.name}")
    }

    @Synchronized
    fun clearActions() {
        actions.clear()
    }

    /**
     * Analyze a single file event.
     *
     * @return true if the operation must be blocked (Kill Switch triggered).
     */
    @Synchronized
    fun analyzeFile(file: File, eventType: FileEvent.EventType): Boolean {
        if (!_enabled.value || _triggered.value) {
            return _triggered.value
        }

        recordEvent(eventType, file.absolutePath)

        val blocked = detectors
            .filter { it.enabled }
            .any { detector -> handleThreat(detector.analyze(file, eventType, recentEvents.toList())) }

        if (!blocked) {
            evaluateThreatLevel()
        }
        return blocked
    }

    /**
     * Analyze a batch of file events, e.g. a whole upload queue.
     *
     * @return true if the batch must be blocked (Kill Switch triggered).
     */
    @Synchronized
    fun analyzeBatch(files: List<Pair<File, FileEvent.EventType>>): Boolean {
        if (!_enabled.value) {
            return false
        }

        val deleteCount = files.count { it.second == FileEvent.EventType.DELETE }
        val blocked = if (deleteCount > deleteThreshold * 2) {
            trigger("Massive batch deletion detected: $deleteCount files")
            true
        } else {
            files.any { (file, eventType) -> analyzeFile(file, eventType) }
        }
        return blocked || _triggered.value
    }

    @Synchronized
    fun trigger(reason: String) {
        if (_triggered.value) {
            return
        }

        _triggered.value = true
        _threatLevel.value = ThreatLevel.CRITICAL

        Log.e(TAG, "!!! KILL SWITCH TRIGGERED !!!")
        Log.e(TAG, "Reason: $reason")

        val threat = ThreatInfo(
            level = ThreatLevel.CRITICAL,
            detectorName = "KillSwitchManager",
            description = reason,
            affectedFiles = emptyList()
        )

        executeActions(threat)
        _syncPaused.tryEmit(reason)
    }

    @Synchronized
    fun reset() {
        _triggered.value = false
        _threatLevel.value = ThreatLevel.NONE
        threats.clear()
        recentEvents.clear()

        detectors.forEach { it.reset() }

        Log.i(TAG, "Kill Switch reset by user")
        _syncResumed.tryEmit(Unit)
    }

    fun getThreats(): List<ThreatInfo> = threats.toList()

    fun getDetectors(): List<ThreatDetector> = detectors.toList()

    fun getActions(): List<SyncAction> = actions.toList()

    fun getRecentEvents(): List<FileEvent> = recentEvents.toList()

    fun shutdown() {
        Log.i(TAG, "Kill Switch Manager shutdown")
    }

    private fun handleThreat(threat: ThreatInfo): Boolean {
        if (threat.level == ThreatLevel.NONE) {
            return false
        }

        threats.add(threat)
        Log.w(TAG, "Threat detected by ${threat.detectorName} - Level: ${threat.level} - ${threat.description}")
        _threatDetected.tryEmit(threat)

        val actionable = threat.level.isActionable()
        if (actionable) {
            trigger(threat.description)
        }
        return actionable
    }

    private fun recordEvent(eventType: FileEvent.EventType, path: String) {
        cleanupExpiredEvents()
        recentEvents.add(
            FileEvent(
                path = path,
                eventType = eventType,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun cleanupExpiredEvents() {
        val cutoff = System.currentTimeMillis() - (windowSeconds * MILLIS_PER_SECOND)
        recentEvents.removeAll { it.timestamp < cutoff }
    }

    private fun evaluateThreatLevel() {
        val deleteCount = recentEvents.count {
            it.eventType == FileEvent.EventType.DELETE
        }

        val newLevel = when {
            deleteCount >= deleteThreshold -> ThreatLevel.CRITICAL
            deleteCount >= (deleteThreshold * HIGH_RATIO).toInt() -> ThreatLevel.HIGH
            deleteCount >= (deleteThreshold * MEDIUM_RATIO).toInt() -> ThreatLevel.MEDIUM
            deleteCount >= (deleteThreshold * LOW_RATIO).toInt() -> ThreatLevel.LOW
            else -> ThreatLevel.NONE
        }

        if (newLevel != _threatLevel.value) {
            _threatLevel.value = newLevel
            if (newLevel >= ThreatLevel.CRITICAL && !_triggered.value) {
                trigger("Deletion threshold exceeded: $deleteCount files in $windowSeconds seconds")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
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
 * Response action executed when the Kill Switch triggers.
 */
interface SyncAction {
    val name: String
    fun execute(threat: ThreatInfo)
}
