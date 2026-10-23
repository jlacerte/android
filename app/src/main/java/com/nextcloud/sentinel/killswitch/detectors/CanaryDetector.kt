/*
 * Cardea Sentinelle - Android client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte and Cardea contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Detects modifications to trap "canary" files.
 * Ported from C++/Qt desktop implementation.
 */

package com.nextcloud.sentinel.killswitch.detectors

import com.nextcloud.sentinel.killswitch.FileEvent
import com.nextcloud.sentinel.killswitch.ThreatDetector
import com.nextcloud.sentinel.killswitch.ThreatInfo
import com.nextcloud.sentinel.killswitch.ThreatLevel
import java.io.File
import java.io.IOException
import java.util.regex.Pattern

/**
 * Detects modifications to canary (trap) files
 *
 * Canary files are decoy files placed in sync folders that
 * should never be touched by normal operations. If a canary
 * file is deleted, modified, or renamed, it's a strong
 * indicator of malicious activity.
 *
 * This is the most reliable detector - legitimate software
 * never touches these specific trap files.
 */
class CanaryDetector : ThreatDetector {

    override val name: String = "CanaryDetector"
    override var enabled: Boolean = true

    // Canary file patterns (case-insensitive)
    private val patterns = mutableListOf(
        "_canary.txt",
        ".canary",
        "zzz_canary.txt",
        "DO_NOT_DELETE.sentinel",
        ".killswitch_canary"
    )

    /**
     * Add a custom canary pattern
     */
    fun addCanaryPattern(pattern: String) {
        if (!patterns.contains(pattern)) {
            patterns.add(pattern)
        }
    }

    /**
     * Remove a canary pattern
     */
    fun removeCanaryPattern(pattern: String) {
        patterns.remove(pattern)
    }

    /**
     * Get current canary patterns
     */
    fun getPatterns(): List<String> = patterns.toList()

    /**
     * Check if a file matches canary patterns
     */
    @Suppress("ReturnCount")
    fun isCanaryFile(filePath: String): Boolean {
        val file = File(filePath)
        val filename = file.name.lowercase()

        for (pattern in patterns) {
            val lowerPattern = pattern.lowercase()

            // Check for wildcard patterns
            if (lowerPattern.contains('*') || lowerPattern.contains('?')) {
                val regex = wildcardToRegex(lowerPattern)
                if (regex.matcher(filename).matches()) {
                    return true
                }
            } else {
                // Exact match
                if (filename == lowerPattern) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Convert wildcard pattern to regex
     */
    private fun wildcardToRegex(wildcard: String): Pattern {
        val regex = StringBuilder("^")
        for (c in wildcard) {
            when (c) {
                '*' -> regex.append(".*")
                '?' -> regex.append(".")
                '.' -> regex.append("\\.")
                '\\' -> regex.append("\\\\")
                else -> regex.append(c)
            }
        }
        regex.append("$")
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE)
    }

    @Suppress("ReturnCount")
    override fun analyze(file: File, eventType: FileEvent.EventType, recentEvents: List<FileEvent>): ThreatInfo {
        if (!enabled) return ThreatInfo.none()

        // Check if this is a canary file
        if (!isCanaryFile(file.absolutePath)) {
            return ThreatInfo.none()
        }

        // Determine operation type
        val operation = when (eventType) {
            FileEvent.EventType.DELETE -> "DELETED"

            FileEvent.EventType.MODIFY -> "MODIFIED"

            FileEvent.EventType.RENAME -> "RENAMED"

            FileEvent.EventType.CREATE -> {
                // New canary file is OK (initial setup)
                return ThreatInfo.none()
            }
        }

        // Any operation on canary file is CRITICAL
        return ThreatInfo(
            level = ThreatLevel.CRITICAL,
            detectorName = name,
            description = "CANARY FILE $operation: ${file.name}",
            affectedFiles = listOf(file.absolutePath)
        )
    }

    override fun reset() {
        // Could optionally reset to default patterns
    }

    companion object {
        /**
         * Create a canary file in a directory
         * @return The created canary file, or null on error
         */
        fun createCanaryFile(directory: File, pattern: String = ".killswitch_canary"): File? {
            if (!directory.isDirectory) return null

            return try {
                val canaryFile = File(directory, pattern)
                canaryFile.writeText(
                    """
                    |# Cardea Sentinelle Canary File
                    |# DO NOT DELETE OR MODIFY THIS FILE
                    |#
                    |# This file is used to detect ransomware attacks.
                    |# If this file is modified or deleted unexpectedly,
                    |# it may indicate malicious activity.
                    |#
                    |# Created: ${System.currentTimeMillis()}
                    """.trimMargin()
                )
                canaryFile
            } catch (e: IOException) {
                null
            }
        }
    }
}
