/*
 * Nextcloud Sentinel Android - Threat Detector Interface
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package com.nextcloud.sentinel.killswitch

import java.io.File

/**
 * Represents a file event for analysis
 */
data class FileEvent(
    val path: String,
    val eventType: EventType,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class EventType {
        CREATE,
        MODIFY,
        DELETE,
        RENAME
    }
}

/**
 * Base interface for all threat detectors
 *
 * Each detector analyzes file events and returns a ThreatInfo
 * indicating the severity of any detected threats.
 */
interface ThreatDetector {
    /**
     * Unique name for this detector
     */
    val name: String

    /**
     * Whether this detector is enabled
     */
    var enabled: Boolean

    /**
     * Analyze a file for potential threats
     *
     * @param file The file to analyze
     * @param eventType The type of file event
     * @param recentEvents Recent file events for context
     * @return ThreatInfo with detection results
     */
    fun analyze(
        file: File,
        eventType: FileEvent.EventType,
        recentEvents: List<FileEvent>
    ): ThreatInfo

    /**
     * Reset detector state (clear caches, counters, etc.)
     */
    fun reset()
}
