/*
 * Cardea Sentinelle - Android client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte and Cardea contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Detects encrypted/ransomware files via Shannon entropy analysis.
 * Ported from C++/Qt desktop implementation.
 */

package com.nextcloud.sentinel.killswitch.detectors

import com.nextcloud.sentinel.killswitch.FileEvent
import com.nextcloud.sentinel.killswitch.ThreatDetector
import com.nextcloud.sentinel.killswitch.ThreatInfo
import com.nextcloud.sentinel.killswitch.ThreatLevel
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log2

/**
 * Detects encrypted/ransomware files via entropy analysis
 *
 * Shannon entropy measures randomness in data (0-8 bits/byte).
 * Encrypted data has very high entropy (~7.9-8.0).
 * Normal files have lower entropy depending on type.
 *
 * Detection criteria:
 * - New files with suspiciously high entropy
 * - Existing files with sudden entropy increase (indicating encryption)
 * - Patterns of multiple high-entropy file modifications
 */
class EntropyDetector : ThreatDetector {

    override val name: String = "EntropyDetector"
    override var enabled: Boolean = true

    // Thresholds (configurable)
    var highEntropyThreshold: Double = DEFAULT_HIGH_ENTROPY_THRESHOLD
    var suspiciousThreshold: Double = DEFAULT_SUSPICIOUS_THRESHOLD
    var sampleSize: Int = DEFAULT_SAMPLE_SIZE

    // Cache of known file entropies for comparison
    private val entropyCache = ConcurrentHashMap<String, Double>()

    // File extensions that normally have high entropy (compressed/encrypted by design)
    private val highEntropyExtensions = setOf(
        // Archives
        ".zip", ".gz", ".bz2", ".xz", ".7z", ".rar", ".tar.gz", ".tgz",
        // Images (compressed)
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif",
        // Media
        ".mp3", ".mp4", ".avi", ".mkv", ".flac", ".ogg", ".m4a", ".mov",
        // Documents (often compressed internally)
        ".pdf", ".docx", ".xlsx", ".pptx", ".odt", ".ods",
        // Encrypted
        ".gpg", ".aes", ".enc"
    )

    // Expected entropy ranges by file type
    @Suppress("MagicNumber")
    private val entropyRanges = mapOf(
        // Text files: 3.0 - 5.5
        ".txt" to (3.0 to 5.5),
        ".md" to (3.0 to 5.5),
        ".rst" to (3.0 to 5.5),
        // Source code: 4.0 - 6.0
        ".kt" to (4.0 to 6.0),
        ".java" to (4.0 to 6.0),
        ".py" to (4.0 to 6.0),
        ".js" to (4.0 to 6.0),
        ".ts" to (4.0 to 6.0),
        ".cpp" to (4.0 to 6.0),
        ".h" to (4.0 to 6.0),
        ".c" to (4.0 to 6.0),
        // Config files: 3.5 - 5.5
        ".json" to (3.5 to 5.5),
        ".xml" to (3.5 to 5.5),
        ".yaml" to (3.5 to 5.5),
        ".yml" to (3.5 to 5.5),
        ".ini" to (3.5 to 5.5),
        ".conf" to (3.5 to 5.5),
        // CSV/data: 3.0 - 5.0
        ".csv" to (3.0 to 5.0),
        ".tsv" to (3.0 to 5.0),
        // HTML: 4.0 - 6.0
        ".html" to (4.0 to 6.0),
        ".htm" to (4.0 to 6.0)
    )

    @Suppress("ReturnCount", "LongMethod")
    override fun analyze(file: File, eventType: FileEvent.EventType, recentEvents: List<FileEvent>): ThreatInfo {
        if (!enabled) return ThreatInfo.none()

        // Only analyze file modifications and creations
        if (eventType != FileEvent.EventType.CREATE &&
            eventType != FileEvent.EventType.MODIFY
        ) {
            return ThreatInfo.none()
        }

        // Skip directories
        if (file.isDirectory) return ThreatInfo.none()

        // Skip files that normally have high entropy
        if (isNormallyHighEntropy(file.name)) {
            return ThreatInfo.none()
        }

        // Calculate current entropy
        val entropy = calculateFileEntropy(file) ?: return ThreatInfo.none()

        // Get expected range for this file type
        val expectedRange = getExpectedEntropyRange(file.name)

        // Check if entropy is suspicious
        return when {
            entropy >= highEntropyThreshold -> {
                ThreatInfo(
                    level = ThreatLevel.CRITICAL,
                    detectorName = name,
                    description = "Critical entropy: %.3f bits/byte (file: %s)".format(entropy, file.name),
                    affectedFiles = listOf(file.absolutePath)
                )
            }

            entropy >= suspiciousThreshold && entropy > expectedRange.second -> {
                ThreatInfo(
                    level = ThreatLevel.HIGH,
                    detectorName = name,
                    description = "Suspicious entropy: %.3f (expected: %.1f-%.1f) for %s".format(
                        entropy,
                        expectedRange.first,
                        expectedRange.second,
                        file.name
                    ),
                    affectedFiles = listOf(file.absolutePath)
                )
            }

            entropyCache.containsKey(file.absolutePath) -> {
                // Check for sudden entropy increase (ransomware encryption)
                val oldEntropy = entropyCache[file.absolutePath]!!
                val increase = entropy - oldEntropy

                if (increase > ENTROPY_SPIKE_DELTA && entropy > ENTROPY_SPIKE_FLOOR) {
                    entropyCache[file.absolutePath] = entropy
                    ThreatInfo(
                        level = ThreatLevel.HIGH,
                        detectorName = name,
                        description = "Entropy spike: %.1f -> %.1f (delta: +%.1f) for %s".format(
                            oldEntropy,
                            entropy,
                            increase,
                            file.name
                        ),
                        affectedFiles = listOf(file.absolutePath)
                    )
                } else {
                    entropyCache[file.absolutePath] = entropy
                    ThreatInfo.none()
                }
            }

            else -> {
                // Update cache and return no threat
                entropyCache[file.absolutePath] = entropy
                ThreatInfo.none()
            }
        }
    }

    override fun reset() {
        entropyCache.clear()
    }

    /**
     * Calculate Shannon entropy of data
     * @param data The byte array to analyze
     * @return Entropy in bits per byte (0.0 - 8.0)
     */
    fun calculateEntropy(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0

        // Count byte frequencies
        val frequency = IntArray(BYTE_VALUES)
        for (byte in data) {
            frequency[byte.toInt() and BYTE_MASK]++
        }

        // Calculate Shannon entropy
        val total = data.size.toDouble()
        return frequency
            .filter { it > 0 }
            .map { it.toDouble() / total }
            .sumOf { probability -> -probability * log2(probability) }
    }

    /**
     * Calculate entropy of a file
     * @param file The file to analyze
     * @return Entropy in bits per byte, or null on error
     */
    @Suppress("ReturnCount")
    fun calculateFileEntropy(file: File): Double? {
        if (!file.exists() || !file.isFile) return null

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val size = minOf(file.length(), sampleSize.toLong()).toInt()
                if (size == 0) return 0.0

                val buffer = ByteArray(size)
                raf.readFully(buffer)
                calculateEntropy(buffer)
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Check if file type normally has high entropy
     */
    private fun isNormallyHighEntropy(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return highEntropyExtensions.any { lower.endsWith(it) }
    }

    /**
     * Get expected entropy range for file type
     */
    private fun getExpectedEntropyRange(fileName: String): Pair<Double, Double> {
        val lower = fileName.lowercase()
        for ((ext, range) in entropyRanges) {
            if (lower.endsWith(ext)) return range
        }
        return 0.0 to MAX_ENTROPY // Default for unknown types
    }

    companion object {
        const val DEFAULT_HIGH_ENTROPY_THRESHOLD = 7.9
        const val DEFAULT_SUSPICIOUS_THRESHOLD = 7.5
        const val DEFAULT_SAMPLE_SIZE = 65536
        const val MAX_ENTROPY = 8.0
        private const val ENTROPY_SPIKE_DELTA = 2.0
        private const val ENTROPY_SPIKE_FLOOR = 7.0
        private const val BYTE_VALUES = 256
        private const val BYTE_MASK = 0xFF

        /**
         * Quick entropy check for a byte array
         */
        fun quickEntropyCheck(data: ByteArray): Double = EntropyDetector().calculateEntropy(data)
    }
}
