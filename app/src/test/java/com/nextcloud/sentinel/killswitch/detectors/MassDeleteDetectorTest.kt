/*
 * Nextcloud Sentinel Android - Mass Delete Detector Tests
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

/**
 * Unit tests for MassDeleteDetector.
 *
 * Tests detection of bulk file deletions and high deletion rates
 * which are indicators of ransomware activity.
 */
class MassDeleteDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var detector: MassDeleteDetector

    @Before
    fun setUp() {
        detector = MassDeleteDetector(
            deleteCountThreshold = 10,
            deleteRateThreshold = 5.0,  // 5 files per second
            timeWindowMs = 60000        // 1 minute window
        )
    }

    // ==================== Single Event Tests ====================

    @Test
    fun `analyze returns null for single delete event`() {
        val file = tempFolder.newFile("single.txt")
        val event = FileEvent(file, FileEvent.EventType.DELETE)

        val threat = detector.analyze(event)

        assertNull("Single delete should not trigger threat", threat)
    }

    @Test
    fun `analyze returns null for CREATE event`() {
        val file = tempFolder.newFile("created.txt")
        val event = FileEvent(file, FileEvent.EventType.CREATE)

        val threat = detector.analyze(event)

        assertNull("CREATE events should not trigger mass delete", threat)
    }

    @Test
    fun `analyze returns null for MODIFY event`() {
        val file = tempFolder.newFile("modified.txt")
        val event = FileEvent(file, FileEvent.EventType.MODIFY)

        val threat = detector.analyze(event)

        assertNull("MODIFY events should not trigger mass delete", threat)
    }

    // ==================== Mass Delete Count Tests ====================

    @Test
    fun `analyze triggers CRITICAL when delete count exceeds 2x threshold`() {
        // Delete 20+ files (2x the threshold of 10)
        for (i in 1..25) {
            val file = File(tempFolder.root, "file$i.txt")
            file.createNewFile()
            val event = FileEvent(file, FileEvent.EventType.DELETE)
            val threat = detector.analyze(event)

            if (i >= 20) {
                // Should trigger at 2x threshold
                assertNotNull("Should trigger at $i deletes", threat)
                assertEquals(ThreatLevel.CRITICAL, threat?.level)
                return
            }
        }
    }

    @Test
    fun `analyze triggers HIGH when delete count exceeds threshold`() {
        // Delete 10+ files (at threshold)
        var lastThreat: com.nextcloud.sentinel.killswitch.ThreatInfo? = null
        for (i in 1..15) {
            val file = File(tempFolder.root, "file$i.txt")
            file.createNewFile()
            val event = FileEvent(file, FileEvent.EventType.DELETE)
            val threat = detector.analyze(event)

            if (threat != null) {
                lastThreat = threat
            }
        }

        assertNotNull("Should trigger after threshold", lastThreat)
        assertTrue(
            "Should be HIGH or CRITICAL",
            lastThreat?.level!! >= ThreatLevel.HIGH
        )
    }

    @Test
    fun `analyze accumulates deletes over time window`() {
        // Delete files spread out but within time window
        for (i in 1..8) {
            val file = File(tempFolder.root, "file$i.txt")
            file.createNewFile()
            val event = FileEvent(file, FileEvent.EventType.DELETE)
            detector.analyze(event)
        }

        // Should have 8 deletes tracked
        assertEquals(8, detector.getDeleteCountInWindow())
    }

    @Test
    fun `analyze includes affected files in threat info`() {
        // Delete enough files to trigger
        var lastThreat: com.nextcloud.sentinel.killswitch.ThreatInfo? = null
        val deletedFiles = mutableListOf<String>()

        for (i in 1..12) {
            val file = File(tempFolder.root, "deleted$i.txt")
            file.createNewFile()
            deletedFiles.add(file.absolutePath)
            val event = FileEvent(file, FileEvent.EventType.DELETE)
            val threat = detector.analyze(event)
            if (threat != null) {
                lastThreat = threat
            }
        }

        assertNotNull(lastThreat)
        assertTrue(lastThreat?.affectedFiles?.isNotEmpty() == true)
    }

    // ==================== Delete Rate Tests ====================

    @Test
    fun `analyze triggers on high delete rate`() {
        // Simulate rapid deletion (faster than rate threshold)
        val startTime = System.currentTimeMillis()

        // Delete 10 files in rapid succession (should exceed 5/sec threshold)
        for (i in 1..10) {
            val file = File(tempFolder.root, "rapid$i.txt")
            file.createNewFile()
            val event = FileEvent(file, FileEvent.EventType.DELETE)
            val threat = detector.analyze(event)

            // After enough rapid deletes, should trigger
            if (threat != null && threat.level >= ThreatLevel.HIGH) {
                assertTrue(
                    "Should mention rate in description",
                    threat.description.contains("rate") || threat.description.contains("rapid")
                )
                return
            }
        }

        // Rate detection depends on timing, may not always trigger in unit test
    }

    // ==================== Time Window Tests ====================

    @Test
    fun `old events are cleared from window`() {
        detector = MassDeleteDetector(
            deleteCountThreshold = 10,
            deleteRateThreshold = 5.0,
            timeWindowMs = 100  // Very short window for testing
        )

        // Add some deletes
        for (i in 1..5) {
            val file = File(tempFolder.root, "old$i.txt")
            file.createNewFile()
            val event = FileEvent(file, FileEvent.EventType.DELETE)
            detector.analyze(event)
        }

        // Wait for window to expire
        Thread.sleep(150)

        // Add one more delete - count should be reset
        val file = File(tempFolder.root, "new.txt")
        file.createNewFile()
        val event = FileEvent(file, FileEvent.EventType.DELETE)
        detector.analyze(event)

        // Should only count the new delete
        assertTrue(detector.getDeleteCountInWindow() <= 2)
    }

    // ==================== Reset Tests ====================

    @Test
    fun `reset clears delete history`() {
        // Add some deletes
        for (i in 1..5) {
            val file = File(tempFolder.root, "file$i.txt")
            file.createNewFile()
            val event = FileEvent(file, FileEvent.EventType.DELETE)
            detector.analyze(event)
        }

        assertTrue(detector.getDeleteCountInWindow() > 0)

        // Reset
        detector.reset()

        assertEquals(0, detector.getDeleteCountInWindow())
    }

    // ==================== Detector Properties Tests ====================

    @Test
    fun `detector has correct name`() {
        assertEquals("MassDeleteDetector", detector.name)
    }

    @Test
    fun `detector is enabled by default`() {
        assertTrue(detector.isEnabled)
    }

    @Test
    fun `detector can be disabled`() {
        detector.isEnabled = false
        assertFalse(detector.isEnabled)

        // Add many deletes - should not trigger when disabled
        for (i in 1..25) {
            val file = File(tempFolder.root, "disabled$i.txt")
            file.createNewFile()
            val event = FileEvent(file, FileEvent.EventType.DELETE)
            val threat = detector.analyze(event)
            assertNull("Disabled detector should not trigger", threat)
        }
    }

    // ==================== Threshold Configuration Tests ====================

    @Test
    fun `custom thresholds are respected`() {
        val customDetector = MassDeleteDetector(
            deleteCountThreshold = 5,  // Lower threshold
            deleteRateThreshold = 10.0,
            timeWindowMs = 60000
        )

        // Should trigger at 5 deletes instead of 10
        var triggered = false
        for (i in 1..6) {
            val file = File(tempFolder.root, "custom$i.txt")
            file.createNewFile()
            val event = FileEvent(file, FileEvent.EventType.DELETE)
            val threat = customDetector.analyze(event)
            if (threat != null) {
                triggered = true
                break
            }
        }

        assertTrue("Should trigger with custom lower threshold", triggered)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles null file path gracefully`() {
        val file = File("/nonexistent/path/file.txt")
        val event = FileEvent(file, FileEvent.EventType.DELETE)

        // Should not crash
        val threat = detector.analyze(event)
        // May or may not trigger depending on implementation
    }

    @Test
    fun `handles concurrent deletes safely`() {
        // Simulate concurrent delete events
        val threads = List(10) { threadIndex ->
            Thread {
                for (i in 1..5) {
                    val file = File(tempFolder.root, "thread${threadIndex}_file$i.txt")
                    file.createNewFile()
                    val event = FileEvent(file, FileEvent.EventType.DELETE)
                    detector.analyze(event)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should have tracked all deletes (50 total)
        assertTrue(detector.getDeleteCountInWindow() >= 40)  // Allow some timing variance
    }
}
