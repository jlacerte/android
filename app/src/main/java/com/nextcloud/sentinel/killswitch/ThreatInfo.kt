/*
 * Nextcloud Sentinel Android - Threat Information
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package com.nextcloud.sentinel.killswitch

import java.time.Instant

/**
 * Threat severity levels
 */
enum class ThreatLevel(val priority: Int) {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    fun isActionable(): Boolean = this >= HIGH
}

/**
 * Information about a detected threat
 *
 * @property level Severity of the threat
 * @property detectorName Name of the detector that found the threat
 * @property description Human-readable description
 * @property affectedFiles List of files involved
 * @property timestamp When the threat was detected
 */
data class ThreatInfo(
    val level: ThreatLevel = ThreatLevel.NONE,
    val detectorName: String = "",
    val description: String = "",
    val affectedFiles: List<String> = emptyList(),
    val timestamp: Instant = Instant.now()
) {
    companion object {
        fun none() = ThreatInfo()
    }

    fun isActionable(): Boolean = level.isActionable()
}
