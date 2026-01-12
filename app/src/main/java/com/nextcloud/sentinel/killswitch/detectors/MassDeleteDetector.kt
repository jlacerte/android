/*
 * Nextcloud Sentinel Android - Mass Delete Detector
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
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
    var threshold: Int = 10           // Files deleted to trigger alert
    var rateLimit: Double = 5.0       // Files per second limit

    override fun analyze(
        file: File,
        eventType: FileEvent.EventType,
        recentEvents: List<FileEvent>
    ): ThreatInfo {
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
        var rate = 0.0
        if (oldestDelete != null && newestDelete != null) {
            val msElapsed = newestDelete - oldestDelete
            if (msElapsed > 0) {
                rate = (deleteCount * 1000.0) / msElapsed
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

            deleteCount >= (threshold * 0.5).toInt() -> {
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
}
