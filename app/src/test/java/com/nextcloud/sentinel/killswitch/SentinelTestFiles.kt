/*
 * Cardea Sentinelle - Android client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte and Cardea contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */

package com.nextcloud.sentinel.killswitch

import java.io.File
import java.security.SecureRandom

object SentinelTestFiles {

    const val RANDOM_FILE_SIZE = 100_000

    fun randomBytes(size: Int = RANDOM_FILE_SIZE): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    fun highEntropyFile(directory: File, name: String): File = File(directory, name).apply { writeBytes(randomBytes()) }

    fun textFile(directory: File, name: String, line: String = "Normal text content.\n", repeat: Int = 1000): File =
        File(directory, name).apply { writeText(line.repeat(repeat)) }

    fun deleteEvents(
        directory: File,
        count: Int,
        spreadMs: Long,
        endTime: Long = System.currentTimeMillis()
    ): List<FileEvent> = List(count) { index ->
        val offset = if (count > 1) (index * spreadMs) / (count - 1) else 0L
        FileEvent(
            path = File(directory, "deleted$index.txt").absolutePath,
            eventType = FileEvent.EventType.DELETE,
            timestamp = endTime - spreadMs + offset
        )
    }
}
