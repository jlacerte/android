/*
 * Nextcloud Sentinel Android - Kill Switch Integration Tests
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package com.nextcloud.sentinel.killswitch

import com.nextcloud.sentinel.killswitch.detectors.CanaryDetector
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the complete Kill Switch system.
 *
 * Tests real-world scenarios with all components working together.
 */
class KillSwitchIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var entropyDetector: EntropyDetector
    private lateinit var massDeleteDetector: MassDeleteDetector
    private lateinit var canaryDetector: CanaryDetector

    @Before
    fun setUp() {
        KillSwitchManager.reset()
        KillSwitchManager.clearDetectors()
        KillSwitchManager.clearActions()

        // Register all detectors
        entropyDetector = EntropyDetector()
        massDeleteDetector = MassDeleteDetector(
            deleteCountThreshold = 10,
            deleteRateThreshold = 5.0,
            timeWindowMs = 60000
        )
        canaryDetector = CanaryDetector()

        KillSwitchManager.registerDetector(entropyDetector)
        KillSwitchManager.registerDetector(massDeleteDetector)
        KillSwitchManager.registerDetector(canaryDetector)

        KillSwitchManager.setEnabled(true)
    }

    @After
    fun tearDown() {
        KillSwitchManager.reset()
        KillSwitchManager.clearDetectors()
        KillSwitchManager.clearActions()
    }

    // ==================== Ransomware Simulation Tests ====================

    @Test
    fun `simulated ransomware attack triggers on encrypted files`() {
        // Simulate ransomware encrypting files
        val encryptedFiles = (1..5).map { i ->
            createEncryptedFile("document$i.txt.encrypted")
        }

        var triggered = false
        for (file in encryptedFiles) {
            if (KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)) {
                triggered = true
                break
            }
        }

        assertTrue("Encrypted files should trigger Kill Switch", triggered)
        assertTrue(KillSwitchManager.isTriggered())
    }

    @Test
    fun `simulated ransomware attack triggers on mass delete`() {
        // Simulate ransomware deleting original files after encryption
        for (i in 1..15) {
            val file = tempFolder.newFile("original$i.txt")
            file.writeText("Original content")
            KillSwitchManager.analyzeFile(file, FileEvent.EventType.DELETE)
        }

        assertTrue("Mass delete should trigger Kill Switch", KillSwitchManager.isTriggered())
    }

    @Test
    fun `simulated ransomware attack triggers on canary modification`() {
        // Setup canary file
        val canaryFile = tempFolder.newFile(".DO_NOT_DELETE_canary.txt")
        canaryFile.writeText("CANARY_SENTINEL")
        canaryDetector.addCanaryFile(canaryFile.absolutePath)

        // Simulate ransomware modifying canary
        val triggered = KillSwitchManager.analyzeFile(canaryFile, FileEvent.EventType.MODIFY)

        assertTrue("Canary modification should trigger Kill Switch", triggered)
        assertEquals(ThreatLevel.CRITICAL, KillSwitchManager.threatLevel.value)
    }

    // ==================== False Positive Prevention Tests ====================

    @Test
    fun `normal document editing does not trigger`() {
        val document = tempFolder.newFile("report.docx")
        document.writeText("Normal document content.\n".repeat(1000))

        val triggered = KillSwitchManager.analyzeFile(document, FileEvent.EventType.MODIFY)

        assertFalse("Normal document should not trigger", triggered)
        assertFalse(KillSwitchManager.isTriggered())
    }

    @Test
    fun `normal photo backup does not trigger`() {
        // Even though JPG has high entropy, it should be whitelisted
        val photo = tempFolder.newFile("photo.jpg")
        val random = SecureRandom()
        val data = ByteArray(100000)
        random.nextBytes(data)
        photo.writeBytes(data)

        val triggered = KillSwitchManager.analyzeFile(photo, FileEvent.EventType.CREATE)

        assertFalse("Photo should not trigger (whitelisted)", triggered)
    }

    @Test
    fun `single file deletion does not trigger`() {
        val file = tempFolder.newFile("single.txt")

        val triggered = KillSwitchManager.analyzeFile(file, FileEvent.EventType.DELETE)

        assertFalse("Single delete should not trigger", triggered)
    }

    @Test
    fun `normal batch of small deletes does not trigger`() {
        // Delete 5 files (below threshold of 10)
        for (i in 1..5) {
            val file = tempFolder.newFile("small$i.txt")
            KillSwitchManager.analyzeFile(file, FileEvent.EventType.DELETE)
        }

        assertFalse("Small batch delete should not trigger", KillSwitchManager.isTriggered())
    }

    // ==================== Multi-Detector Coordination Tests ====================

    @Test
    fun `entropy detector triggers before mass delete completes`() {
        // In a real ransomware attack, entropy detection should trigger
        // before mass delete threshold is reached

        // Create one encrypted file
        val encryptedFile = createEncryptedFile("encrypted1.dat")
        KillSwitchManager.analyzeFile(encryptedFile, FileEvent.EventType.CREATE)

        // Kill Switch should already be triggered
        assertTrue(
            "Entropy should trigger before mass delete threshold",
            KillSwitchManager.isTriggered()
        )
    }

    @Test
    fun `canary provides immediate detection`() {
        // Canary should trigger instantly on any modification
        val canary = tempFolder.newFile(".canary")
        canaryDetector.addCanaryFile(canary.absolutePath)

        val triggered = KillSwitchManager.analyzeFile(canary, FileEvent.EventType.MODIFY)

        assertTrue("Canary should trigger immediately", triggered)
        assertEquals(ThreatLevel.CRITICAL, KillSwitchManager.threatLevel.value)
    }

    // ==================== Recovery Flow Tests ====================

    @Test
    fun `reset allows new file analysis`() {
        // Trigger Kill Switch
        val encrypted = createEncryptedFile("malware.dat")
        KillSwitchManager.analyzeFile(encrypted, FileEvent.EventType.MODIFY)
        assertTrue(KillSwitchManager.isTriggered())

        // Reset
        KillSwitchManager.reset()
        assertFalse(KillSwitchManager.isTriggered())

        // Normal file should work
        val normal = tempFolder.newFile("safe.txt")
        normal.writeText("Safe content")
        val triggered = KillSwitchManager.analyzeFile(normal, FileEvent.EventType.MODIFY)

        assertFalse("Normal file after reset should not trigger", triggered)
    }

    @Test
    fun `reset clears detector states`() {
        // Add some deletes
        for (i in 1..5) {
            val file = tempFolder.newFile("pre$i.txt")
            KillSwitchManager.analyzeFile(file, FileEvent.EventType.DELETE)
        }

        // Reset
        KillSwitchManager.reset()

        // Add more deletes - should not combine with previous
        for (i in 1..5) {
            val file = tempFolder.newFile("post$i.txt")
            KillSwitchManager.analyzeFile(file, FileEvent.EventType.DELETE)
        }

        // Should not trigger (only 5 deletes, not 10)
        assertFalse("Reset should clear delete count", KillSwitchManager.isTriggered())
    }

    // ==================== Action Execution Tests ====================

    @Test
    fun `actions execute in order on threat detection`() {
        val executionOrder = mutableListOf<String>()

        KillSwitchManager.registerAction(object : SyncAction {
            override val name = "Action1"
            override fun execute(threat: ThreatInfo) {
                executionOrder.add("Action1")
            }
        })

        KillSwitchManager.registerAction(object : SyncAction {
            override val name = "Action2"
            override fun execute(threat: ThreatInfo) {
                executionOrder.add("Action2")
            }
        })

        val encrypted = createEncryptedFile("trigger.dat")
        KillSwitchManager.analyzeFile(encrypted, FileEvent.EventType.MODIFY)

        assertEquals(listOf("Action1", "Action2"), executionOrder)
    }

    @Test
    fun `action failure does not prevent other actions`() {
        val action2Executed = CountDownLatch(1)

        KillSwitchManager.registerAction(object : SyncAction {
            override val name = "FailingAction"
            override fun execute(threat: ThreatInfo) {
                throw RuntimeException("Simulated failure")
            }
        })

        KillSwitchManager.registerAction(object : SyncAction {
            override val name = "WorkingAction"
            override fun execute(threat: ThreatInfo) {
                action2Executed.countDown()
            }
        })

        val encrypted = createEncryptedFile("error.dat")
        KillSwitchManager.analyzeFile(encrypted, FileEvent.EventType.MODIFY)

        assertTrue(
            "Second action should execute despite first failure",
            action2Executed.await(1, TimeUnit.SECONDS)
        )
    }

    // ==================== Concurrent Access Tests ====================

    @Test
    fun `handles concurrent file events safely`() {
        val threads = List(10) { threadIndex ->
            Thread {
                for (i in 1..10) {
                    val file = tempFolder.newFile("concurrent_${threadIndex}_$i.txt")
                    file.writeText("Content $i")
                    KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        // Should not crash and state should be consistent
        // (may or may not be triggered depending on timing)
    }

    // ==================== Flow Event Tests ====================

    @Test
    fun `threat details available in threatDetected flow`() = runBlocking {
        val encrypted = createEncryptedFile("flow_test.dat")

        val threat = withTimeoutOrNull(2000) {
            KillSwitchManager.analyzeFile(encrypted, FileEvent.EventType.MODIFY)
            KillSwitchManager.threatDetected.first()
        }

        assertNotNull(threat)
        assertEquals("EntropyDetector", threat?.detectorName)
        assertTrue(threat?.affectedFiles?.isNotEmpty() == true)
    }

    // ==================== Performance Tests ====================

    @Test
    fun `analyzes files within acceptable time`() {
        val file = tempFolder.newFile("perf.txt")
        file.writeText("Test content")

        val startTime = System.currentTimeMillis()

        repeat(100) {
            KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)
        }

        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(
            "100 analyses should complete within 1 second, took ${elapsed}ms",
            elapsed < 1000
        )
    }

    // ==================== Helper Methods ====================

    private fun createEncryptedFile(name: String): File {
        val file = File(tempFolder.root, name)
        val random = SecureRandom()
        val data = ByteArray(100000)
        random.nextBytes(data)
        file.writeBytes(data)
        return file
    }
}
