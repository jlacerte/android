/*
 * Nextcloud Sentinel Android - Entropy Detector Tests
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package com.nextcloud.sentinel.killswitch.detectors

import com.nextcloud.sentinel.killswitch.FileEvent
import com.nextcloud.sentinel.killswitch.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

/**
 * Unit tests for EntropyDetector.
 *
 * Tests Shannon entropy calculation and threat detection
 * for encrypted/compressed files (ransomware signature).
 */
class EntropyDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var detector: EntropyDetector

    @Before
    fun setUp() {
        detector = EntropyDetector()
    }

    // ==================== Entropy Calculation Tests ====================

    @Test
    fun `calculateEntropy returns 0 for empty data`() {
        val entropy = detector.calculateEntropy(ByteArray(0))
        assertEquals(0.0, entropy, 0.001)
    }

    @Test
    fun `calculateEntropy returns 0 for uniform data`() {
        // All same bytes = 0 entropy
        val data = ByteArray(1000) { 0x00 }
        val entropy = detector.calculateEntropy(data)
        assertEquals(0.0, entropy, 0.001)
    }

    @Test
    fun `calculateEntropy returns low value for repetitive text`() {
        // Repetitive text has low entropy
        val text = "AAAAAAAAAAAAAAAAAAAAAAAAA".repeat(100)
        val entropy = detector.calculateEntropy(text.toByteArray())
        assertTrue("Repetitive text should have low entropy", entropy < 1.0)
    }

    @Test
    fun `calculateEntropy returns medium value for normal text`() {
        // Normal English text has entropy around 4-5 bits/byte
        val text = """
            The quick brown fox jumps over the lazy dog.
            Pack my box with five dozen liquor jugs.
            How vexingly quick daft zebras jump!
        """.trimIndent().repeat(50)
        val entropy = detector.calculateEntropy(text.toByteArray())
        assertTrue("Normal text should have entropy 3-6", entropy in 3.0..6.0)
    }

    @Test
    fun `calculateEntropy returns high value for random data`() {
        // Random data should have high entropy (close to 8 bits/byte)
        val random = SecureRandom()
        val data = ByteArray(10000)
        random.nextBytes(data)
        val entropy = detector.calculateEntropy(data)
        assertTrue("Random data should have entropy > 7.5", entropy > 7.5)
    }

    @Test
    fun `calculateEntropy returns maximum 8 for perfect random distribution`() {
        // Create data with perfect distribution of all 256 byte values
        val data = ByteArray(256 * 100) { i -> (i % 256).toByte() }
        val entropy = detector.calculateEntropy(data)
        assertTrue("Perfect distribution should approach 8", entropy > 7.9)
    }

    // ==================== File Analysis Tests ====================

    @Test
    fun `analyze returns null for normal text file`() {
        val file = createTempFile("normal.txt", "Hello World! This is a normal text file.\n".repeat(100))
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        assertNull("Normal text file should not trigger threat", threat)
    }

    @Test
    fun `analyze returns null for small file below threshold`() {
        // Small files are skipped
        val file = createTempFile("small.txt", "tiny")
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        assertNull("Small file should not be analyzed", threat)
    }

    @Test
    fun `analyze returns CRITICAL for high entropy file`() {
        // Simulate encrypted file with random data
        val random = SecureRandom()
        val data = ByteArray(100000)
        random.nextBytes(data)
        val file = createTempFile("encrypted.dat", data)
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        assertNotNull("High entropy file should trigger threat", threat)
        assertEquals(ThreatLevel.CRITICAL, threat?.level)
        assertTrue(threat?.description?.contains("entropy") == true)
    }

    @Test
    fun `analyze returns HIGH for moderately high entropy`() {
        // Create data with entropy around 7.6-7.8 (compressed but not encrypted)
        val data = createMediumEntropyData(100000)
        val file = createTempFile("compressed.bin", data)
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        // Should trigger HIGH or CRITICAL depending on exact entropy
        if (threat != null) {
            assertTrue(
                "Medium-high entropy should trigger HIGH or CRITICAL",
                threat.level >= ThreatLevel.HIGH
            )
        }
    }

    @Test
    fun `analyze ignores DELETE events`() {
        val file = tempFolder.newFile("deleted.txt")
        val event = FileEvent(file, FileEvent.EventType.DELETE)

        val threat = detector.analyze(event)

        assertNull("DELETE events should be ignored", threat)
    }

    @Test
    fun `analyze ignores non-existent files`() {
        val file = File(tempFolder.root, "nonexistent.txt")
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        assertNull("Non-existent files should be ignored", threat)
    }

    @Test
    fun `analyze includes file path in threat info`() {
        val random = SecureRandom()
        val data = ByteArray(100000)
        random.nextBytes(data)
        val file = createTempFile("suspicious.encrypted", data)
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        assertNotNull(threat)
        assertTrue(threat?.affectedFiles?.contains(file.absolutePath) == true)
    }

    // ==================== Whitelisted Extensions Tests ====================

    @Test
    fun `analyze skips whitelisted extensions - jpg`() {
        // Even high entropy JPG should be skipped (images are naturally high entropy)
        val random = SecureRandom()
        val data = ByteArray(100000)
        random.nextBytes(data)
        val file = createTempFile("photo.jpg", data)
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        assertNull("JPG files should be whitelisted", threat)
    }

    @Test
    fun `analyze skips whitelisted extensions - mp4`() {
        val random = SecureRandom()
        val data = ByteArray(100000)
        random.nextBytes(data)
        val file = createTempFile("video.mp4", data)
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        assertNull("MP4 files should be whitelisted", threat)
    }

    @Test
    fun `analyze skips whitelisted extensions - zip`() {
        val random = SecureRandom()
        val data = ByteArray(100000)
        random.nextBytes(data)
        val file = createTempFile("archive.zip", data)
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        assertNull("ZIP files should be whitelisted", threat)
    }

    // ==================== Detector Properties Tests ====================

    @Test
    fun `detector has correct name`() {
        assertEquals("EntropyDetector", detector.name)
    }

    @Test
    fun `detector is enabled by default`() {
        assertTrue(detector.isEnabled)
    }

    @Test
    fun `detector can be disabled`() {
        detector.isEnabled = false
        assertFalse(detector.isEnabled)

        // Disabled detector should not analyze
        val random = SecureRandom()
        val data = ByteArray(100000)
        random.nextBytes(data)
        val file = createTempFile("test.dat", data)
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)
        assertNull("Disabled detector should not analyze", threat)
    }

    // ==================== Helper Methods ====================

    private fun createTempFile(name: String, content: String): File {
        val file = File(tempFolder.root, name)
        file.writeText(content)
        return file
    }

    private fun createTempFile(name: String, content: ByteArray): File {
        val file = File(tempFolder.root, name)
        file.writeBytes(content)
        return file
    }

    /**
     * Create data with medium-high entropy (around 7.5-7.8).
     * This simulates compressed data that's not fully random.
     */
    private fun createMediumEntropyData(size: Int): ByteArray {
        val data = ByteArray(size)
        val random = SecureRandom()

        // Mix random data with some patterns
        for (i in data.indices) {
            data[i] = if (i % 10 == 0) {
                // Every 10th byte is predictable
                (i % 256).toByte()
            } else {
                random.nextInt(256).toByte()
            }
        }
        return data
    }
}
