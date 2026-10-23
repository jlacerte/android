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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@Suppress("TooManyFunctions")
class MassDeleteDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var detector: MassDeleteDetector

    @Before
    fun setUp() {
        detector = MassDeleteDetector()
    }

    private fun analyzeDelete(recentEvents: List<FileEvent>): ThreatInfo {
        val file = File(tempFolder.root, "current.txt")
        return detector.analyze(file, FileEvent.EventType.DELETE, recentEvents)
    }

    private fun deletes(count: Int, spreadMs: Long = 60_000L): List<FileEvent> =
        SentinelTestFiles.deleteEvents(tempFolder.root, count, spreadMs)

    @Test
    fun `single delete is not a threat`() {
        assertEquals(ThreatLevel.NONE, analyzeDelete(deletes(1)).level)
    }

    @Test
    fun `create and modify events are ignored even with many recent deletes`() {
        val file = File(tempFolder.root, "edited.txt")
        val events = deletes(30)

        assertEquals(ThreatLevel.NONE, detector.analyze(file, FileEvent.EventType.CREATE, events).level)
        assertEquals(ThreatLevel.NONE, detector.analyze(file, FileEvent.EventType.MODIFY, events).level)
    }

    @Test
    fun `only delete events are counted`() {
        val modifications = List(20) { index ->
            FileEvent(File(tempFolder.root, "edit$index.txt").absolutePath, FileEvent.EventType.MODIFY)
        }

        assertEquals(ThreatLevel.NONE, analyzeDelete(modifications + deletes(1)).level)
    }

    @Test
    fun `reaching the threshold is HIGH`() {
        val threat = analyzeDelete(deletes(MassDeleteDetector.DEFAULT_THRESHOLD))

        assertEquals(ThreatLevel.HIGH, threat.level)
        assertEquals("MassDeleteDetector", threat.detectorName)
    }

    @Test
    fun `twice the threshold is CRITICAL and lists affected files`() {
        val events = deletes(MassDeleteDetector.DEFAULT_THRESHOLD * 2)

        val threat = analyzeDelete(events)

        assertEquals(ThreatLevel.CRITICAL, threat.level)
        assertEquals(events.map { it.path }, threat.affectedFiles)
    }

    @Test
    fun `half the threshold is MEDIUM`() {
        assertEquals(ThreatLevel.MEDIUM, analyzeDelete(deletes(MassDeleteDetector.DEFAULT_THRESHOLD / 2)).level)
    }

    @Test
    fun `fast deletions below the count threshold are HIGH because of the rate`() {
        val threat = analyzeDelete(deletes(8, spreadMs = 1000L))

        assertEquals(ThreatLevel.HIGH, threat.level)
        assertTrue("Description should mention the rate: ${threat.description}", threat.description.contains("rate"))
    }

    @Test
    fun `rate is not evaluated on bursts shorter than a second`() {
        assertEquals(ThreatLevel.NONE, analyzeDelete(deletes(4, spreadMs = 10L)).level)
    }

    @Test
    fun `slow deletions below the thresholds are not a threat`() {
        assertEquals(ThreatLevel.NONE, analyzeDelete(deletes(3, spreadMs = 60_000L)).level)
    }

    @Test
    fun `custom thresholds are respected`() {
        detector.threshold = 5

        assertEquals(ThreatLevel.HIGH, analyzeDelete(deletes(5)).level)
        assertEquals(ThreatLevel.CRITICAL, analyzeDelete(deletes(10)).level)
    }

    @Test
    fun `disabled detector never reports`() {
        detector.enabled = false

        assertFalse(detector.enabled)
        assertEquals(ThreatLevel.NONE, analyzeDelete(deletes(50)).level)
    }

    @Test
    fun `detector has correct name and defaults`() {
        assertEquals("MassDeleteDetector", detector.name)
        assertTrue(detector.enabled)
        assertEquals(MassDeleteDetector.DEFAULT_THRESHOLD, detector.threshold)
        assertEquals(MassDeleteDetector.DEFAULT_RATE_LIMIT, detector.rateLimit, 0.0)
    }

    @Test
    fun `reset keeps the configured thresholds`() {
        detector.threshold = 3

        detector.reset()

        assertEquals(3, detector.threshold)
    }
}
