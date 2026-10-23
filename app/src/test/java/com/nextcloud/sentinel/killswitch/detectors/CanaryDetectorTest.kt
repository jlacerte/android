/*
 * Nextcloud Sentinel Android - Canary Detector Tests
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
 * Unit tests for CanaryDetector.
 *
 * Tests trap file (canary) monitoring for ransomware detection.
 * Any modification to a canary file indicates malicious activity.
 */
class CanaryDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var detector: CanaryDetector
    private lateinit var canaryFile: File

    @Before
    fun setUp() {
        detector = CanaryDetector()

        // Create a canary file
        canaryFile = tempFolder.newFile(".canary_trap.txt")
        canaryFile.writeText("CANARY_SENTINEL_DO_NOT_MODIFY")
    }

    // ==================== Canary Registration Tests ====================

    @Test
    fun `addCanaryFile registers file for monitoring`() {
        detector.addCanaryFile(canaryFile.absolutePath)

        assertTrue(detector.getCanaryFiles().contains(canaryFile.absolutePath))
    }

    @Test
    fun `addCanaryFile accepts multiple files`() {
        val canary1 = tempFolder.newFile("canary1.txt")
        val canary2 = tempFolder.newFile("canary2.txt")

        detector.addCanaryFile(canary1.absolutePath)
        detector.addCanaryFile(canary2.absolutePath)

        assertEquals(2, detector.getCanaryFiles().size)
    }

    @Test
    fun `removeCanaryFile unregisters file`() {
        detector.addCanaryFile(canaryFile.absolutePath)
        detector.removeCanaryFile(canaryFile.absolutePath)

        assertFalse(detector.getCanaryFiles().contains(canaryFile.absolutePath))
    }

    @Test
    fun `clearCanaryFiles removes all files`() {
        val canary1 = tempFolder.newFile("canary1.txt")
        val canary2 = tempFolder.newFile("canary2.txt")

        detector.addCanaryFile(canary1.absolutePath)
        detector.addCanaryFile(canary2.absolutePath)
        detector.clearCanaryFiles()

        assertTrue(detector.getCanaryFiles().isEmpty())
    }

    // ==================== Canary Detection Tests ====================

    @Test
    fun `analyze returns CRITICAL when canary is modified`() {
        detector.addCanaryFile(canaryFile.absolutePath)

        val event = FileEvent(canaryFile, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNotNull("Canary modification should trigger threat", threat)
        assertEquals(ThreatLevel.CRITICAL, threat?.level)
    }

    @Test
    fun `analyze returns CRITICAL when canary is deleted`() {
        detector.addCanaryFile(canaryFile.absolutePath)

        val event = FileEvent(canaryFile, FileEvent.EventType.DELETE)
        val threat = detector.analyze(event)

        assertNotNull("Canary deletion should trigger threat", threat)
        assertEquals(ThreatLevel.CRITICAL, threat?.level)
    }

    @Test
    fun `analyze returns null for non-canary files`() {
        detector.addCanaryFile(canaryFile.absolutePath)

        val normalFile = tempFolder.newFile("normal.txt")
        val event = FileEvent(normalFile, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNull("Non-canary files should not trigger", threat)
    }

    @Test
    fun `analyze returns null when no canaries registered`() {
        // No canaries registered
        val event = FileEvent(canaryFile, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNull("Should not trigger without registered canaries", threat)
    }

    @Test
    fun `analyze description mentions canary file`() {
        detector.addCanaryFile(canaryFile.absolutePath)

        val event = FileEvent(canaryFile, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNotNull(threat)
        assertTrue(
            "Description should mention canary",
            threat?.description?.lowercase()?.contains("canary") == true ||
            threat?.description?.lowercase()?.contains("trap") == true
        )
    }

    @Test
    fun `analyze includes canary path in affected files`() {
        detector.addCanaryFile(canaryFile.absolutePath)

        val event = FileEvent(canaryFile, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNotNull(threat)
        assertTrue(threat?.affectedFiles?.contains(canaryFile.absolutePath) == true)
    }

    // ==================== Event Type Tests ====================

    @Test
    fun `CREATE event on canary does not trigger`() {
        detector.addCanaryFile(canaryFile.absolutePath)

        // CREATE shouldn't trigger - canary was just created
        val event = FileEvent(canaryFile, FileEvent.EventType.CREATE)
        val threat = detector.analyze(event)

        assertNull("CREATE should not trigger for canary", threat)
    }

    @Test
    fun `all modification types trigger for canary`() {
        val modifyTypes = listOf(
            FileEvent.EventType.MODIFY,
            FileEvent.EventType.DELETE
        )

        for (eventType in modifyTypes) {
            val freshDetector = CanaryDetector()
            val freshCanary = tempFolder.newFile("canary_${eventType.name}.txt")
            freshDetector.addCanaryFile(freshCanary.absolutePath)

            val event = FileEvent(freshCanary, eventType)
            val threat = freshDetector.analyze(event)

            assertNotNull("$eventType should trigger for canary", threat)
        }
    }

    // ==================== Multiple Canaries Tests ====================

    @Test
    fun `detects correct canary among multiple`() {
        val canary1 = tempFolder.newFile("canary1.txt")
        val canary2 = tempFolder.newFile("canary2.txt")
        val canary3 = tempFolder.newFile("canary3.txt")

        detector.addCanaryFile(canary1.absolutePath)
        detector.addCanaryFile(canary2.absolutePath)
        detector.addCanaryFile(canary3.absolutePath)

        // Only canary2 is modified
        val event = FileEvent(canary2, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNotNull(threat)
        assertTrue(threat?.affectedFiles?.contains(canary2.absolutePath) == true)
    }

    // ==================== Path Matching Tests ====================

    @Test
    fun `matches canary by absolute path`() {
        detector.addCanaryFile(canaryFile.absolutePath)

        // Create event with same file but different File object
        val samePath = File(canaryFile.absolutePath)
        val event = FileEvent(samePath, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNotNull("Should match by path", threat)
    }

    @Test
    fun `handles paths with different separators`() {
        // This test is platform-specific
        val path = canaryFile.absolutePath
        detector.addCanaryFile(path)

        // Try with canonical path
        val canonicalFile = File(canaryFile.canonicalPath)
        val event = FileEvent(canonicalFile, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNotNull("Should handle canonical paths", threat)
    }

    // ==================== Detector Properties Tests ====================

    @Test
    fun `detector has correct name`() {
        assertEquals("CanaryDetector", detector.name)
    }

    @Test
    fun `detector is enabled by default`() {
        assertTrue(detector.isEnabled)
    }

    @Test
    fun `detector can be disabled`() {
        detector.addCanaryFile(canaryFile.absolutePath)
        detector.isEnabled = false

        val event = FileEvent(canaryFile, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNull("Disabled detector should not trigger", threat)
    }

    @Test
    fun `re-enabling detector works`() {
        detector.addCanaryFile(canaryFile.absolutePath)
        detector.isEnabled = false
        detector.isEnabled = true

        val event = FileEvent(canaryFile, FileEvent.EventType.MODIFY)
        val threat = detector.analyze(event)

        assertNotNull("Re-enabled detector should trigger", threat)
    }

    // ==================== Reset Tests ====================

    @Test
    fun `reset does not clear canary files`() {
        detector.addCanaryFile(canaryFile.absolutePath)
        detector.reset()

        // Canary files should persist after reset
        assertTrue(detector.getCanaryFiles().isNotEmpty())
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles duplicate canary registration`() {
        detector.addCanaryFile(canaryFile.absolutePath)
        detector.addCanaryFile(canaryFile.absolutePath)  // Duplicate

        // Should not have duplicates
        assertEquals(1, detector.getCanaryFiles().size)
    }

    @Test
    fun `handles non-existent canary file path`() {
        val nonExistent = "/path/to/nonexistent/canary.txt"
        detector.addCanaryFile(nonExistent)

        // Should still register the path
        assertTrue(detector.getCanaryFiles().contains(nonExistent))

        // Event for non-existent file should still trigger
        val event = FileEvent(File(nonExistent), FileEvent.EventType.DELETE)
        val threat = detector.analyze(event)

        assertNotNull("Should detect deletion of non-existent canary", threat)
    }

    @Test
    fun `handles empty path gracefully`() {
        detector.addCanaryFile("")

        // Should not crash, may or may not add
        val event = FileEvent(File(""), FileEvent.EventType.MODIFY)
        detector.analyze(event)  // Should not crash
    }
}
