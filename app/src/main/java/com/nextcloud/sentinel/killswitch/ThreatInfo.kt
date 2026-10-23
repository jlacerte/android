/*
 * Cardea Sentinelle - Android client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte and Cardea contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */

package com.nextcloud.sentinel.killswitch

import java.time.Instant

/**
 * Threat severity levels
 */
@Suppress("MagicNumber")
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
