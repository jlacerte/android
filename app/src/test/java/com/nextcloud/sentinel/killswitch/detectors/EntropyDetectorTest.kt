/*
 * Nextcloud Sentinel Android - Kill Switch Tests
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package com.nextcloud.sentinel.killswitch.detectors

import com.nextcloud.sentinel.killswitch.FileEvent
import com.nextcloud.sentinel.killswitch.SentinelTestFiles
import com.nextcloud.sentinel.killswitch.ThreatInfo
import com.nextcloud.sentinel.killswitch.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@Suppress("TooManyFunctions")
class EntropyDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var detector: EntropyDetector

    @Before
    fun setUp() {
        detector = EntropyDetector()
    }

    private fun analyze(file: File, eventType: FileEvent.EventType = FileEvent.EventType.MODIFY): ThreatInfo =
        detector.analyze(file, eventType, emptyList())

    @Test
    fun `calculateEntropy is 0 for empty and uniform data`() {
        assertEquals(0.0, detector.calculateEntropy(ByteArray(0)), 0.001)
        assertEquals(0.0, detector.calculateEntropy(ByteArray(1000) { 0x00 }), 0.001)
    }

    @Test
    fun `calculateEntropy is low for repetitive text`() {
        val entropy = detector.calculateEntropy("AAAAAAAAAAAAAAAAAAAAAAAAA".repeat(100).toByteArray())

        assertTrue("Repetitive text should have low entropy", entropy < 1.0)
    }

    @Test
    fun `calculateEntropy is medium for normal text`() {
        val text = """
            The quick brown fox jumps over the lazy dog.
            Pack my box with five dozen liquor jugs.
            How vexingly quick daft zebras jump!
        """.trimIndent().repeat(50)

        val entropy = detector.calculateEntropy(text.toByteArray())

        assertTrue("Normal text should have entropy 3-6, was $entropy", entropy in 3.0..6.0)
    }

    @Test
    fun `calculateEntropy is high for random data`() {
        val entropy = detector.calculateEntropy(SentinelTestFiles.randomBytes(10_000))

        assertTrue("Random data should have entropy > 7.5", entropy > 7.5)
    }

    @Test
    fun `calculateEntropy approaches 8 for a perfect distribution`() {
        val entropy = detector.calculateEntropy(ByteArray(256 * 100) { i -> (i % 256).toByte() })

        assertTrue("Perfect distribution should approach 8", entropy > 7.9)
    }

    @Test
    fun `calculateFileEntropy is null for missing files`() {
        assertNull(detector.calculateFileEntropy(File(tempFolder.root, "missing.bin")))
        assertNull(detector.calculateFileEntropy(tempFolder.root))
    }

    @Test
    fun `normal text file is not a threat`() {
        val file = SentinelTestFiles.textFile(
            tempFolder.root,
            "normal.txt",
            "Hello World! This is a normal text file.\n",
            100
        )

        assertEquals(ThreatLevel.NONE, analyze(file).level)
    }

    @Test
    fun `tiny file is not a threat`() {
        val file = SentinelTestFiles.textFile(tempFolder.root, "small.txt", "tiny", 1)

        assertEquals(ThreatLevel.NONE, analyze(file).level)
    }

    @Test
    fun `random data file is CRITICAL with the file listed`() {
        val file = SentinelTestFiles.highEntropyFile(tempFolder.root, "encrypted.dat")

        val threat = analyze(file)

        assertEquals(ThreatLevel.CRITICAL, threat.level)
        assertEquals("EntropyDetector", threat.detectorName)
        assertTrue(threat.description.contains("entropy"))
        assertEquals(listOf(file.absolutePath), threat.affectedFiles)
    }

    @Test
    fun `random data file is CRITICAL on create as well`() {
        val file = SentinelTestFiles.highEntropyFile(tempFolder.root, "document.txt.encrypted")

        assertEquals(ThreatLevel.CRITICAL, analyze(file, FileEvent.EventType.CREATE).level)
    }

    @Test
    fun `delete events and missing files are ignored`() {
        val deleted = tempFolder.newFile("deleted.txt")
        val missing = File(tempFolder.root, "nonexistent.txt")

        assertEquals(ThreatLevel.NONE, analyze(deleted, FileEvent.EventType.DELETE).level)
        assertEquals(ThreatLevel.NONE, analyze(missing).level)
    }

    @Test
    fun `file types that are normally high entropy are skipped`() {
        for (name in listOf("photo.jpg", "video.mp4", "archive.zip", "report.pdf", "secret.gpg")) {
            val file = SentinelTestFiles.highEntropyFile(tempFolder.root, name)

            assertEquals("$name should be skipped", ThreatLevel.NONE, analyze(file).level)
        }
    }

    @Test
    fun `disabled detector never reports`() {
        val file = SentinelTestFiles.highEntropyFile(tempFolder.root, "test.dat")
        detector.enabled = false

        assertFalse(detector.enabled)
        assertEquals(ThreatLevel.NONE, analyze(file).level)
    }

    @Test
    fun `detector has correct name and defaults`() {
        assertEquals("EntropyDetector", detector.name)
        assertTrue(detector.enabled)
        assertEquals(EntropyDetector.DEFAULT_HIGH_ENTROPY_THRESHOLD, detector.highEntropyThreshold, 0.0)
        assertEquals(EntropyDetector.DEFAULT_SUSPICIOUS_THRESHOLD, detector.suspiciousThreshold, 0.0)
        assertEquals(EntropyDetector.DEFAULT_SAMPLE_SIZE, detector.sampleSize)
    }
}
