/*
 * Cardea Sentinelle - Android client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte and Cardea contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */

package com.nextcloud.sentinel.killswitch.detectors

import com.nextcloud.sentinel.killswitch.FileEvent
import com.nextcloud.sentinel.killswitch.ThreatInfo
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

@Suppress("TooManyFunctions")
class CanaryDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var detector: CanaryDetector
    private lateinit var canaryFile: File

    @Before
    fun setUp() {
        detector = CanaryDetector()
        canaryFile = tempFolder.newFile(".killswitch_canary")
    }

    private fun analyze(file: File, eventType: FileEvent.EventType): ThreatInfo =
        detector.analyze(file, eventType, emptyList())

    @Test
    fun `default patterns are recognised`() {
        assertTrue(detector.isCanaryFile(canaryFile.absolutePath))
        assertTrue(detector.isCanaryFile(File(tempFolder.root, "DO_NOT_DELETE.sentinel").absolutePath))
        assertFalse(detector.isCanaryFile(File(tempFolder.root, "holiday.jpg").absolutePath))
    }

    @Test
    fun `addCanaryPattern registers a new exact pattern`() {
        detector.addCanaryPattern("trap.txt")

        assertTrue(detector.getPatterns().contains("trap.txt"))
        assertTrue(detector.isCanaryFile(File(tempFolder.root, "trap.txt").absolutePath))
    }

    @Test
    fun `duplicate pattern registration is ignored`() {
        val before = detector.getPatterns().size

        detector.addCanaryPattern(".killswitch_canary")

        assertEquals(before, detector.getPatterns().size)
    }

    @Test
    fun `removeCanaryPattern unregisters the pattern`() {
        detector.removeCanaryPattern(".killswitch_canary")

        assertFalse(detector.getPatterns().contains(".killswitch_canary"))
        assertFalse(detector.isCanaryFile(canaryFile.absolutePath))
    }

    @Test
    fun `wildcard patterns match file names`() {
        detector.addCanaryPattern("*.trap")

        assertTrue(detector.isCanaryFile(File(tempFolder.root, "anything.trap").absolutePath))
        assertFalse(detector.isCanaryFile(File(tempFolder.root, "anything.trap.bak").absolutePath))
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(detector.isCanaryFile(File(tempFolder.root, "do_not_delete.SENTINEL").absolutePath))
    }

    @Test
    fun `modifying a canary is CRITICAL with the file listed`() {
        val threat = analyze(canaryFile, FileEvent.EventType.MODIFY)

        assertEquals(ThreatLevel.CRITICAL, threat.level)
        assertEquals("CanaryDetector", threat.detectorName)
        assertTrue(threat.description.contains("CANARY"))
        assertEquals(listOf(canaryFile.absolutePath), threat.affectedFiles)
    }

    @Test
    fun `deleting or renaming a canary is CRITICAL`() {
        assertEquals(ThreatLevel.CRITICAL, analyze(canaryFile, FileEvent.EventType.DELETE).level)
        assertEquals(ThreatLevel.CRITICAL, analyze(canaryFile, FileEvent.EventType.RENAME).level)
    }

    @Test
    fun `creating a canary is not a threat`() {
        assertEquals(ThreatLevel.NONE, analyze(canaryFile, FileEvent.EventType.CREATE).level)
    }

    @Test
    fun `non-canary files are not a threat`() {
        val normal = tempFolder.newFile("normal.txt")

        assertEquals(ThreatLevel.NONE, analyze(normal, FileEvent.EventType.MODIFY).level)
        assertEquals(ThreatLevel.NONE, analyze(normal, FileEvent.EventType.DELETE).level)
    }

    @Test
    fun `non-existent canary deletion is still detected`() {
        val missing = File(tempFolder.root, "missing/.killswitch_canary")

        assertEquals(ThreatLevel.CRITICAL, analyze(missing, FileEvent.EventType.DELETE).level)
    }

    @Test
    fun `disabled detector can be re-enabled`() {
        detector.enabled = false
        assertEquals(ThreatLevel.NONE, analyze(canaryFile, FileEvent.EventType.MODIFY).level)

        detector.enabled = true
        assertEquals(ThreatLevel.CRITICAL, analyze(canaryFile, FileEvent.EventType.MODIFY).level)
    }

    @Test
    fun `createCanaryFile writes a marker file only inside a directory`() {
        val created = CanaryDetector.createCanaryFile(tempFolder.newFolder("watched"))

        assertNotNull(created)
        assertTrue(created!!.isFile)
        assertTrue(detector.isCanaryFile(created.absolutePath))
        assertNull(CanaryDetector.createCanaryFile(canaryFile))
    }

    @Test
    fun `detector has correct name and is enabled by default`() {
        assertEquals("CanaryDetector", detector.name)
        assertTrue(detector.enabled)
    }
}
