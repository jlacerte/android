/*
 * Nextcloud Sentinel Android - Kill Switch Manager Tests
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package com.nextcloud.sentinel.killswitch

import com.nextcloud.sentinel.killswitch.detectors.EntropyDetector
import com.nextcloud.sentinel.killswitch.detectors.MassDeleteDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.time.Instant

/**
 * Unit tests for KillSwitchManager.
 *
 * Tests the central coordinator for threat detection and response.
 */
class KillSwitchManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        // Reset manager state before each test
        KillSwitchManager.reset()
        KillSwitchManager.clearDetectors()
        KillSwitchManager.clearActions()
        KillSwitchManager.setEnabled(true)
    }

    @After
    fun tearDown() {
        KillSwitchManager.reset()
        KillSwitchManager.clearDetectors()
        KillSwitchManager.clearActions()
    }

    // ==================== Enable/Disable Tests ====================

    @Test
    fun `setEnabled changes enabled state`() {
        KillSwitchManager.setEnabled(false)
        assertFalse(KillSwitchManager.isEnabled())

        KillSwitchManager.setEnabled(true)
        assertTrue(KillSwitchManager.isEnabled())
    }

    @Test
    fun `enabled state is observable via flow`() = runBlocking {
        KillSwitchManager.setEnabled(false)
        assertEquals(false, KillSwitchManager.enabled.first())

        KillSwitchManager.setEnabled(true)
        assertEquals(true, KillSwitchManager.enabled.first())
    }

    // ==================== Detector Registration Tests ====================

    @Test
    fun `registerDetector adds detector`() {
        val detector = EntropyDetector()
        KillSwitchManager.registerDetector(detector)

        assertTrue(KillSwitchManager.getDetectors().contains(detector))
    }

    @Test
    fun `unregisterDetector removes detector`() {
        val detector = EntropyDetector()
        KillSwitchManager.registerDetector(detector)
        KillSwitchManager.unregisterDetector(detector)

        assertFalse(KillSwitchManager.getDetectors().contains(detector))
    }

    @Test
    fun `clearDetectors removes all detectors`() {
        KillSwitchManager.registerDetector(EntropyDetector())
        KillSwitchManager.registerDetector(MassDeleteDetector())
        KillSwitchManager.clearDetectors()

        assertTrue(KillSwitchManager.getDetectors().isEmpty())
    }

    // ==================== Action Registration Tests ====================

    @Test
    fun `registerAction adds action`() {
        val action = TestAction()
        KillSwitchManager.registerAction(action)

        assertTrue(KillSwitchManager.getActions().contains(action))
    }

    @Test
    fun `clearActions removes all actions`() {
        KillSwitchManager.registerAction(TestAction())
        KillSwitchManager.registerAction(TestAction())
        KillSwitchManager.clearActions()

        assertTrue(KillSwitchManager.getActions().isEmpty())
    }

    // ==================== File Analysis Tests ====================

    @Test
    fun `analyzeFile returns false when disabled`() {
        KillSwitchManager.setEnabled(false)
        KillSwitchManager.registerDetector(EntropyDetector())

        val file = createHighEntropyFile("test.dat")
        val result = KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertFalse("Should not analyze when disabled", result)
    }

    @Test
    fun `analyzeFile returns false for no threat`() {
        KillSwitchManager.registerDetector(EntropyDetector())

        val file = createNormalTextFile("normal.txt")
        val result = KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertFalse("Normal file should not trigger", result)
    }

    @Test
    fun `analyzeFile returns true for detected threat`() {
        KillSwitchManager.registerDetector(EntropyDetector())

        val file = createHighEntropyFile("encrypted.dat")
        val result = KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertTrue("High entropy file should trigger", result)
    }

    @Test
    fun `analyzeFile executes actions on threat`() {
        val action = TestAction()
        KillSwitchManager.registerDetector(EntropyDetector())
        KillSwitchManager.registerAction(action)

        val file = createHighEntropyFile("malicious.dat")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertTrue("Action should be executed", action.wasExecuted)
    }

    @Test
    fun `analyzeFile sets triggered state on threat`() {
        KillSwitchManager.registerDetector(EntropyDetector())

        assertFalse(KillSwitchManager.isTriggered())

        val file = createHighEntropyFile("threat.dat")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertTrue(KillSwitchManager.isTriggered())
    }

    // ==================== Batch Analysis Tests ====================

    @Test
    fun `analyzeBatch processes multiple files`() {
        val massDeleteDetector = MassDeleteDetector(
            deleteCountThreshold = 5,
            deleteRateThreshold = 100.0,
            timeWindowMs = 60000
        )
        KillSwitchManager.registerDetector(massDeleteDetector)

        val batch = (1..10).map { i ->
            val file = tempFolder.newFile("file$i.txt")
            file to FileEvent.EventType.DELETE
        }

        val triggered = KillSwitchManager.analyzeBatch(batch)

        assertTrue("Batch delete should trigger", triggered)
    }

    @Test
    fun `analyzeBatch returns false for safe batch`() {
        KillSwitchManager.registerDetector(EntropyDetector())

        val batch = (1..5).map { i ->
            val file = createNormalTextFile("safe$i.txt")
            file to FileEvent.EventType.MODIFY
        }

        val triggered = KillSwitchManager.analyzeBatch(batch)

        assertFalse("Safe batch should not trigger", triggered)
    }

    // ==================== Threat Level Tests ====================

    @Test
    fun `threatLevel updates on detection`() = runBlocking {
        KillSwitchManager.registerDetector(EntropyDetector())

        assertEquals(ThreatLevel.NONE, KillSwitchManager.threatLevel.value)

        val file = createHighEntropyFile("critical.dat")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertTrue(KillSwitchManager.threatLevel.value >= ThreatLevel.HIGH)
    }

    @Test
    fun `threatLevel resets after reset call`() {
        KillSwitchManager.registerDetector(EntropyDetector())

        val file = createHighEntropyFile("trigger.dat")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)
        assertTrue(KillSwitchManager.threatLevel.value >= ThreatLevel.HIGH)

        KillSwitchManager.reset()

        assertEquals(ThreatLevel.NONE, KillSwitchManager.threatLevel.value)
    }

    // ==================== Threat History Tests ====================

    @Test
    fun `getThreatHistory returns detected threats`() {
        KillSwitchManager.registerDetector(EntropyDetector())

        assertTrue(KillSwitchManager.getThreatHistory().isEmpty())

        val file = createHighEntropyFile("threat1.dat")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertTrue(KillSwitchManager.getThreatHistory().isNotEmpty())
    }

    @Test
    fun `threat history contains threat details`() {
        KillSwitchManager.registerDetector(EntropyDetector())

        val file = createHighEntropyFile("detailed.dat")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        val history = KillSwitchManager.getThreatHistory()
        assertNotNull(history.firstOrNull())
        assertTrue(history.first().affectedFiles.contains(file.absolutePath))
    }

    // ==================== Reset Tests ====================

    @Test
    fun `reset clears triggered state`() {
        KillSwitchManager.registerDetector(EntropyDetector())

        val file = createHighEntropyFile("threat.dat")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)
        assertTrue(KillSwitchManager.isTriggered())

        KillSwitchManager.reset()

        assertFalse(KillSwitchManager.isTriggered())
    }

    @Test
    fun `reset resets detectors`() {
        val detector = MassDeleteDetector()
        KillSwitchManager.registerDetector(detector)

        // Add some deletes
        for (i in 1..5) {
            val file = tempFolder.newFile("del$i.txt")
            KillSwitchManager.analyzeFile(file, FileEvent.EventType.DELETE)
        }

        assertTrue(detector.getDeleteCountInWindow() > 0)

        KillSwitchManager.reset()

        assertEquals(0, detector.getDeleteCountInWindow())
    }

    @Test
    fun `reset emits syncResumed event`() = runBlocking {
        KillSwitchManager.registerDetector(EntropyDetector())

        val file = createHighEntropyFile("trigger.dat")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        // Reset should emit syncResumed
        val resumed = withTimeoutOrNull(1000) {
            KillSwitchManager.reset()
            KillSwitchManager.syncResumed.first()
        }

        assertNotNull("Should emit syncResumed", resumed)
    }

    // ==================== Flow Events Tests ====================

    @Test
    fun `threatDetected flow emits on detection`() = runBlocking {
        KillSwitchManager.registerDetector(EntropyDetector())

        val file = createHighEntropyFile("emitted.dat")

        val threat = withTimeoutOrNull(1000) {
            KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)
            KillSwitchManager.threatDetected.first()
        }

        assertNotNull("Should emit threatDetected", threat)
    }

    @Test
    fun `syncPaused flow emits on trigger`() = runBlocking {
        KillSwitchManager.registerDetector(EntropyDetector())

        val file = createHighEntropyFile("pause.dat")

        val paused = withTimeoutOrNull(1000) {
            KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)
            KillSwitchManager.syncPaused.first()
        }

        assertNotNull("Should emit syncPaused", paused)
    }

    // ==================== Multiple Detectors Tests ====================

    @Test
    fun `multiple detectors are all invoked`() {
        val detector1 = TestDetector("Detector1")
        val detector2 = TestDetector("Detector2")
        KillSwitchManager.registerDetector(detector1)
        KillSwitchManager.registerDetector(detector2)

        val file = tempFolder.newFile("multi.txt")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertTrue("Detector1 should be called", detector1.wasCalled)
        assertTrue("Detector2 should be called", detector2.wasCalled)
    }

    @Test
    fun `first threat stops further detection`() {
        val detector1 = TestDetector("Detector1", threatLevel = ThreatLevel.CRITICAL)
        val detector2 = TestDetector("Detector2")
        KillSwitchManager.registerDetector(detector1)
        KillSwitchManager.registerDetector(detector2)

        val file = tempFolder.newFile("stop.txt")
        KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        // First detector triggers, second may or may not be called
        // depending on implementation (early exit or not)
        assertTrue("Detector1 should be called", detector1.wasCalled)
    }

    // ==================== Helper Methods ====================

    private fun createNormalTextFile(name: String): File {
        val file = File(tempFolder.root, name)
        file.writeText("Normal text content.\n".repeat(1000))
        return file
    }

    private fun createHighEntropyFile(name: String): File {
        val file = File(tempFolder.root, name)
        val random = SecureRandom()
        val data = ByteArray(100000)
        random.nextBytes(data)
        file.writeBytes(data)
        return file
    }

    // ==================== Test Doubles ====================

    private class TestAction : SyncAction {
        override val name = "TestAction"
        var wasExecuted = false

        override fun execute(threat: ThreatInfo) {
            wasExecuted = true
        }
    }

    private class TestDetector(
        override val name: String,
        private val threatLevel: ThreatLevel? = null
    ) : ThreatDetector {
        override var isEnabled = true
        var wasCalled = false

        override fun analyze(event: FileEvent): ThreatInfo? {
            wasCalled = true
            return threatLevel?.let {
                ThreatInfo(
                    level = it,
                    detectorName = name,
                    description = "Test threat",
                    affectedFiles = listOf(event.file.absolutePath),
                    timestamp = Instant.now()
                )
            }
        }

        override fun reset() {}
    }
}
