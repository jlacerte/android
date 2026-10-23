/*
 * Cardea Sentinelle - Android client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte and Cardea contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */

package com.nextcloud.sentinel.killswitch

import com.nextcloud.sentinel.killswitch.detectors.CanaryDetector
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

/**
 * Exercises the manager together with the three real detectors, the way
 * KillSwitchIntegration wires them in the app.
 */
class KillSwitchIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        KillSwitchManager.reset()
        KillSwitchManager.clearDetectors()
        KillSwitchManager.clearActions()
        KillSwitchManager.registerDetector(EntropyDetector())
        KillSwitchManager.registerDetector(MassDeleteDetector())
        KillSwitchManager.registerDetector(CanaryDetector())
        KillSwitchManager.setEnabled(true)
    }

    @After
    fun tearDown() {
        KillSwitchManager.reset()
        KillSwitchManager.clearDetectors()
        KillSwitchManager.clearActions()
    }

    @Test
    fun `encrypted documents trigger the Kill Switch`() {
        val encrypted = SentinelTestFiles.highEntropyFile(tempFolder.root, "document1.txt.encrypted")

        assertTrue(KillSwitchManager.analyzeFile(encrypted, FileEvent.EventType.CREATE))
        assertTrue(KillSwitchManager.isTriggered())
        assertEquals("EntropyDetector", KillSwitchManager.getThreats().single().detectorName)
    }

    @Test
    fun `mass deletion triggers the Kill Switch`() {
        repeat(15) { index ->
            KillSwitchManager.analyzeFile(File(tempFolder.root, "original$index.txt"), FileEvent.EventType.DELETE)
        }

        assertTrue(KillSwitchManager.isTriggered())
        assertEquals("MassDeleteDetector", KillSwitchManager.getThreats().first().detectorName)
    }

    @Test
    fun `canary modification triggers immediately`() {
        val canary = CanaryDetector.createCanaryFile(tempFolder.root)!!

        assertTrue(KillSwitchManager.analyzeFile(canary, FileEvent.EventType.MODIFY))
        assertEquals(ThreatLevel.CRITICAL, KillSwitchManager.threatLevel.value)
        assertEquals("CanaryDetector", KillSwitchManager.getThreats().single().detectorName)
    }

    @Test
    fun `normal document editing and photo backup do not trigger`() {
        val document = SentinelTestFiles.textFile(tempFolder.root, "report.txt", "Normal document content.\n")
        val photo = SentinelTestFiles.highEntropyFile(tempFolder.root, "photo.jpg")

        assertFalse(KillSwitchManager.analyzeFile(document, FileEvent.EventType.MODIFY))
        assertFalse(KillSwitchManager.analyzeFile(photo, FileEvent.EventType.CREATE))
        assertFalse(KillSwitchManager.isTriggered())
        assertTrue(KillSwitchManager.getThreats().isEmpty())
    }

    @Test
    fun `a few deletions do not trigger`() {
        repeat(5) { index ->
            assertFalse(
                KillSwitchManager.analyzeFile(File(tempFolder.root, "small$index.txt"), FileEvent.EventType.DELETE)
            )
        }

        assertFalse(KillSwitchManager.isTriggered())
        assertEquals(ThreatLevel.MEDIUM, KillSwitchManager.threatLevel.value)
    }

    @Test
    fun `reset clears the deletion window and allows new analysis`() {
        repeat(8) { index ->
            KillSwitchManager.analyzeFile(File(tempFolder.root, "pre$index.txt"), FileEvent.EventType.DELETE)
        }

        KillSwitchManager.reset()
        repeat(8) { index ->
            KillSwitchManager.analyzeFile(File(tempFolder.root, "post$index.txt"), FileEvent.EventType.DELETE)
        }

        assertFalse(KillSwitchManager.isTriggered())
        val safe = SentinelTestFiles.textFile(tempFolder.root, "safe.txt")
        assertFalse(KillSwitchManager.analyzeFile(safe, FileEvent.EventType.MODIFY))
    }

    @Test
    fun `actions run in registration order and a failing action does not stop the others`() {
        val executionOrder = mutableListOf<String>()
        KillSwitchManager.registerAction(namedAction("First") { executionOrder.add("First") })
        KillSwitchManager.registerAction(namedAction("Failing") { error("Simulated failure") })
        KillSwitchManager.registerAction(namedAction("Last") { executionOrder.add("Last") })

        KillSwitchManager.analyzeFile(
            SentinelTestFiles.highEntropyFile(tempFolder.root, "trigger.dat"),
            FileEvent.EventType.MODIFY
        )

        assertEquals(listOf("First", "Last"), executionOrder)
        assertTrue(KillSwitchManager.isTriggered())
    }

    @Test
    fun `concurrent file events are handled safely`() {
        val threads = List(10) { threadIndex ->
            Thread {
                repeat(10) { index ->
                    val file = SentinelTestFiles.textFile(
                        tempFolder.root,
                        "concurrent_${threadIndex}_$index.txt",
                        "Content\n",
                        10
                    )
                    KillSwitchManager.analyzeFile(file, FileEvent.EventType.MODIFY)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        assertFalse(KillSwitchManager.isTriggered())
        assertEquals(100, KillSwitchManager.getRecentEvents().size)
    }

    @Test
    fun `threat details are available in the threatDetected flow`() = runBlocking {
        val encrypted = SentinelTestFiles.highEntropyFile(tempFolder.root, "flow_test.dat")
        val threat = async(start = CoroutineStart.UNDISPATCHED) { KillSwitchManager.threatDetected.first() }

        KillSwitchManager.analyzeFile(encrypted, FileEvent.EventType.MODIFY)

        val detected = withTimeout(2000) { threat.await() }
        assertEquals("EntropyDetector", detected.detectorName)
        assertEquals(listOf(encrypted.absolutePath), detected.affectedFiles)
    }

    private fun namedAction(actionName: String, body: () -> Unit): SyncAction = object : SyncAction {
        override val name = actionName
        override fun execute(threat: ThreatInfo) = body()
    }
}
