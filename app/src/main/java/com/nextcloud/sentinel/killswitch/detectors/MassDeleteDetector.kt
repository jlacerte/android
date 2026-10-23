/*
 * Cardea Sentinelle - Android client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte and Cardea contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Detects ransomware-like mass file deletions.
 * Ported from C++/Qt desktop implementation.
 */

package com.nextcloud.sentinel.killswitch.detectors

import com.nextcloud.sentinel.killswitch.FileEvent
import com.nextcloud.sentinel.killswitch.ThreatDetector
import com.nextcloud.sentinel.killswitch.ThreatInfo
import com.nextcloud.sentinel.killswitch.ThreatLevel
import java.io.File

/**
 * Detects mass file deletions (ransomware pattern)
 *
 * Ransomware often deletes original files after encrypting.
 * This detector monitors deletion rate and count:
 * - Threshold: Too many deletions in time window
 * - Rate limit: Deletions happening too fast
 *
 * Both patterns indicate potential ransomware activity.
 */
class MassDeleteDetector : ThreatDetector {

    override val name: String = "MassDeleteDetector"
    override var enabled: Boolean = true

    // Configurable thresholds
    var threshold: Int = DEFAULT_THRESHOLD
    var rateLimit: Double = DEFAULT_RATE_LIMIT

    @Suppress("ReturnCount", "LongMethod")
    override fun analyze(file: File, eventType: FileEvent.EventType, recentEvents: List<FileEvent>): ThreatInfo {
        if (!enabled) return ThreatInfo.none()

        // Only interested in deletions
        if (eventType != FileEvent.EventType.DELETE) {
            return ThreatInfo.none()
        }

        // Count recent deletions
        var deleteCount = 0
        var oldestDelete: Long? = null
        var newestDelete: Long? = null
        val affectedFiles = mutableListOf<String>()

        for (event in recentEvents) {
            if (event.eventType == FileEvent.EventType.DELETE) {
                deleteCount++
                affectedFiles.add(event.path)

                if (oldestDelete == null || event.timestamp < oldestDelete) {
                    oldestDelete = event.timestamp
                }
                if (newestDelete == null || event.timestamp > newestDelete) {
                    newestDelete = event.timestamp
                }
            }
        }

        // Calculate deletion rate (files per second)
        // All items of one sync run are analyzed within milliseconds of each
        // other, which would otherwise turn any two deletions into a "high
        // deletion rate". Same guard as the desktop MassDeleteDetector.
        var rate = 0.0
        val minDeletesForRate = maxOf(MIN_DELETES_FOR_RATE, threshold / 2)
        if (deleteCount >= minDeletesForRate && oldestDelete != null && newestDelete != null) {
            val msElapsed = newestDelete - oldestDelete
            if (msElapsed >= MIN_RATE_WINDOW_MS) {
                rate = (deleteCount * MILLIS_PER_SECOND) / msElapsed
            }
        }

        // Evaluate threat level
        return when {
            deleteCount >= threshold * 2 -> {
                ThreatInfo(
                    level = ThreatLevel.CRITICAL,
                    detectorName = name,
                    description = "Critical: $deleteCount files deleted (threshold: $threshold)",
                    affectedFiles = affectedFiles
                )
            }

            deleteCount >= threshold -> {
                ThreatInfo(
                    level = ThreatLevel.HIGH,
                    detectorName = name,
                    description = "High: $deleteCount files deleted, approaching critical",
                    affectedFiles = affectedFiles
                )
            }

            rate > rateLimit -> {
                ThreatInfo(
                    level = ThreatLevel.HIGH,
                    detectorName = name,
                    description = "High deletion rate: %.1f files/sec (limit: %.1f)".format(rate, rateLimit),
                    affectedFiles = affectedFiles
                )
            }

            deleteCount >= (threshold * MEDIUM_RATIO).toInt() -> {
                ThreatInfo(
                    level = ThreatLevel.MEDIUM,
                    detectorName = name,
                    description = "Medium: $deleteCount files deleted in short window",
                    affectedFiles = affectedFiles
                )
            }

            else -> ThreatInfo.none()
        }
    }

    override fun reset() {
        // No internal state to reset
    }

    companion object {
        const val DEFAULT_THRESHOLD = 10
        const val DEFAULT_RATE_LIMIT = 5.0
        private const val MILLIS_PER_SECOND = 1000.0
        private const val MIN_RATE_WINDOW_MS = 1000L
        private const val MIN_DELETES_FOR_RATE = 3
        private const val MEDIUM_RATIO = 0.5
    }
}
