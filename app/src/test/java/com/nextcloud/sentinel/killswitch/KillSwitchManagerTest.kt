/*
 * Nextcloud Sentinel Android - Kill Switch Tests
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package com.nextcloud.sentinel.killswitch

import com.nextcloud.sentinel.killswitch.detectors.EntropyDetector
import com.nextcloud.sentinel.killswitch.detectors.MassDeleteDetector
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@Suppress("TooManyFunctions")
class KillSwitchManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
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

    @Test
    fun `setEnabled changes the enabled state and its flow`() {
        KillSwitchManager.setEnabled(false)
        assertFalse(KillSwitchManager.isEnabled())
        assertFalse(KillSwitchManager.enabled.value)

        KillSwitchManager.setEnabled(true)
        assertTrue(KillSwitchManager.isEnabled())
        assertTrue(KillSwitchManager.enabled.value)
    }

    @Test
    fun `detectors can be registered, unregistered and cleared`() {
        val entropy = EntropyDetector()
        val massDelete = MassDeleteDetector()

        KillSwitchManager.registerDetector(entropy)
        KillSwitchManager.registerDetector(massDelete)
        assertEquals(listOf(entropy, massDelete), KillSwitchManager.getDetectors())

        KillSwitchManager.unregisterDetector(entropy)
        assertEquals(listOf(massDelete), KillSwitchManager.getDetectors())

        KillSwitchManager.clearDetectors()
        assertTrue(KillSwitchManager.getDetectors().isEmpty())
    }

    @Test
    fun `actions can be registered and cleared`() {
        val action = RecordingAction()

        KillSwitchManager.registerAction(action)
        assertEquals(listOf(action), KillSwitchManager.getActions())

        KillSwitchManager.clearActions()
        assertTrue(KillSwitchManager.getActions().isEmpty())
    }

    @Test
    fun `analyzeFile does nothing when disabled`() {
        KillSwitchManager.setEnabled(false)
        KillSwitchManager.registerDetector(EntropyDetector())

        val blocked = KillSwitchManager.analyzeFile(highEntropyFile("test.dat"), FileEvent.EventType.MODIFY)

        assertFalse(blocked)
        assertFalse(KillSwitchManager.isTriggered())
        assertTrue(KillSwitchManager.getRecentEvents().isEmpty())
    }

    @Test
    fun `analyzeFile allows a normal file and records the event`() {
        KillSwitchManager.registerDetector(EntropyDetector())
        val file = SentinelTestFiles.textFile(tempFolder.root, "normal.txt")

        val blocked = KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertFalse(blocked)
        assertEquals(ThreatLevel.NONE, KillSwitchManager.threatLevel.value)
        assertEquals(listOf(file.absolutePath), KillSwitchManager.getRecentEvents().map { it.path })
    }

    @Test
    fun `an actionable threat blocks, triggers, runs actions and is recorded`() {
        val action = RecordingAction()
        KillSwitchManager.registerDetector(EntropyDetector())
        KillSwitchManager.registerAction(action)
        val file = highEntropyFile("encrypted.dat")

        val blocked = KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)

        assertTrue(blocked)
        assertTrue(KillSwitchManager.isTriggered())
        assertTrue(KillSwitchManager.triggered.value)
        assertEquals(ThreatLevel.CRITICAL, KillSwitchManager.threatLevel.value)
        assertEquals(1, action.executions.size)
        assertEquals(listOf(file.absolutePath), KillSwitchManager.getThreats().single().affectedFiles)
    }

    @Test
    fun `once triggered every file is blocked until reset`() {
        KillSwitchManager.registerDetector(EntropyDetector())
        KillSwitchManager.analyzeFile(highEntropyFile("trigger.dat"), FileEvent.EventType.MODIFY)
        val normal = SentinelTestFiles.textFile(tempFolder.root, "normal.txt")

        assertTrue(KillSwitchManager.analyzeFile(normal, FileEvent.EventType.MODIFY))

        KillSwitchManager.reset()

        assertFalse(KillSwitchManager.isTriggered())
        assertEquals(ThreatLevel.NONE, KillSwitchManager.threatLevel.value)
        assertTrue(KillSwitchManager.getThreats().isEmpty())
        assertFalse(KillSwitchManager.analyzeFile(normal, FileEvent.EventType.MODIFY))
    }

    @Test
    fun `a non-actionable threat is recorded without triggering`() {
        KillSwitchManager.registerDetector(FixedDetector("Medium", ThreatLevel.MEDIUM))

        val blocked = KillSwitchManager.analyzeFile(tempFolder.newFile("file.txt"), FileEvent.EventType.MODIFY)

        assertFalse(blocked)
        assertFalse(KillSwitchManager.isTriggered())
        assertEquals(1, KillSwitchManager.getThreats().size)
    }

    @Test
    fun `mass deletion triggers through the shared event window`() {
        KillSwitchManager.registerDetector(MassDeleteDetector())

        repeat(MassDeleteDetector.DEFAULT_THRESHOLD - 1) { index ->
            assertFalse(
                KillSwitchManager.analyzeFile(File(tempFolder.root, "del$index.txt"), FileEvent.EventType.DELETE)
            )
        }
        assertFalse(KillSwitchManager.isTriggered())

        assertTrue(KillSwitchManager.analyzeFile(File(tempFolder.root, "last.txt"), FileEvent.EventType.DELETE))
        assertTrue(KillSwitchManager.isTriggered())
    }

    @Test
    fun `analyzeBatch triggers on a massive deletion and allows a safe batch`() {
        KillSwitchManager.registerDetector(EntropyDetector())
        val safeBatch = (1..5).map { index ->
            SentinelTestFiles.textFile(tempFolder.root, "safe$index.txt") to FileEvent.EventType.MODIFY
        }
        assertFalse(KillSwitchManager.analyzeBatch(safeBatch))

        val deletions = (1..(KillSwitchManager.deleteThreshold * 2 + 1)).map { index ->
            File(tempFolder.root, "gone$index.txt") to FileEvent.EventType.DELETE
        }
        assertTrue(KillSwitchManager.analyzeBatch(deletions))
        assertTrue(KillSwitchManager.isTriggered())
    }

    @Test
    fun `threatDetected and syncPaused are emitted on trigger and syncResumed on reset`() = runBlocking {
        KillSwitchManager.registerDetector(EntropyDetector())
        val threat = async(start = CoroutineStart.UNDISPATCHED) { KillSwitchManager.threatDetected.first() }
        val paused = async(start = CoroutineStart.UNDISPATCHED) { KillSwitchManager.syncPaused.first() }
        val resumed = async(start = CoroutineStart.UNDISPATCHED) { KillSwitchManager.syncResumed.first() }

        KillSwitchManager.analyzeFile(highEntropyFile("emitted.dat"), FileEvent.EventType.MODIFY)
        KillSwitchManager.reset()

        withTimeout(1000) {
            assertEquals("EntropyDetector", threat.await().detectorName)
            assertTrue(paused.await().contains("entropy"))
            resumed.await()
        }
    }

    @Test
    fun `detectors run in order and the first actionable threat stops the analysis`() {
        val first = FixedDetector("First", ThreatLevel.NONE)
        val second = FixedDetector("Second", ThreatLevel.CRITICAL)
        val third = FixedDetector("Third", ThreatLevel.NONE)
        listOf(first, second, third).forEach { KillSwitchManager.registerDetector(it) }

        KillSwitchManager.analyzeFile(tempFolder.newFile("multi.txt"), FileEvent.EventType.MODIFY)

        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertEquals(0, third.calls)
    }

    private fun highEntropyFile(name: String): File = SentinelTestFiles.highEntropyFile(tempFolder.root, name)

    private class RecordingAction : SyncAction {
        override val name = "RecordingAction"
        val executions = mutableListOf<ThreatInfo>()

        override fun execute(threat: ThreatInfo) {
            executions.add(threat)
        }
    }

    private class FixedDetector(override val name: String, private val level: ThreatLevel) : ThreatDetector {
        override var enabled = true
        var calls = 0

        override fun analyze(file: File, eventType: FileEvent.EventType, recentEvents: List<FileEvent>): ThreatInfo {
            calls++
            return ThreatInfo(
                level = level,
                detectorName = name,
                description = "Fixed $level threat",
                affectedFiles = listOf(file.absolutePath)
            )
        }

        override fun reset() = Unit
    }
}
