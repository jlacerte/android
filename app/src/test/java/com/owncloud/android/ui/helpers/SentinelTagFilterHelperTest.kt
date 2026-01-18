/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.helpers

import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.lib.resources.tags.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

class SentinelTagFilterHelperTest {

    private lateinit var mocks: AutoCloseable

    @Before
    fun setUp() {
        mocks = MockitoAnnotations.openMocks(this)
    }

    // Helper function to create a mock Tag
    private fun createMockTag(name: String, color: String? = null): Tag {
        val tag = org.mockito.Mockito.mock(Tag::class.java)
        whenever(tag.name).thenReturn(name)
        whenever(tag.color).thenReturn(color)
        return tag
    }

    // Helper function to create an OCFile with tags
    private fun createFileWithTags(path: String, vararg tagNames: String): OCFile {
        val file = OCFile(path)
        file.tags = tagNames.map { createMockTag(it) }
        return file
    }

    // ========== extractTagsWithCounts tests ==========

    @Test
    fun extractTagsWithCounts_emptyList_returnsEmpty() {
        val result = SentinelTagFilterHelper.extractTagsWithCounts(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun extractTagsWithCounts_filesWithNoTags_returnsEmpty() {
        val files = listOf(
            OCFile("/photo1.jpg"),
            OCFile("/photo2.jpg")
        )
        val result = SentinelTagFilterHelper.extractTagsWithCounts(files)
        assertTrue(result.isEmpty())
    }

    @Test
    fun extractTagsWithCounts_singleFileWithTags_returnsCorrectCounts() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature", "sunset")
        )
        val result = SentinelTagFilterHelper.extractTagsWithCounts(files)

        assertEquals(2, result.size)
        assertTrue(result.all { it.count == 1 })
    }

    @Test
    fun extractTagsWithCounts_multipleFilesSharedTags_countsProperly() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature", "sunset"),
            createFileWithTags("/photo2.jpg", "nature", "mountain"),
            createFileWithTags("/photo3.jpg", "nature")
        )
        val result = SentinelTagFilterHelper.extractTagsWithCounts(files)

        // nature: 3, sunset: 1, mountain: 1
        assertEquals(3, result.size)

        val natureTag = result.find { it.tagName == "nature" }
        assertEquals(3, natureTag?.count)

        val sunsetTag = result.find { it.tagName == "sunset" }
        assertEquals(1, sunsetTag?.count)
    }

    @Test
    fun extractTagsWithCounts_sortedByCountDescending() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "rare", "common", "frequent"),
            createFileWithTags("/photo2.jpg", "common", "frequent"),
            createFileWithTags("/photo3.jpg", "frequent")
        )
        val result = SentinelTagFilterHelper.extractTagsWithCounts(files)

        // Should be sorted: frequent(3), common(2), rare(1)
        assertEquals("frequent", result[0].tagName)
        assertEquals(3, result[0].count)
        assertEquals("common", result[1].tagName)
        assertEquals(2, result[1].count)
        assertEquals("rare", result[2].tagName)
        assertEquals(1, result[2].count)
    }

    // ========== filterFilesByTags (AND logic) tests ==========

    @Test
    fun filterFilesByTags_emptySelection_returnsAllFiles() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature"),
            createFileWithTags("/photo2.jpg", "city")
        )
        val result = SentinelTagFilterHelper.filterFilesByTags(files, emptySet())

        assertEquals(2, result.size)
    }

    @Test
    fun filterFilesByTags_singleTag_filtersCorrectly() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature", "sunset"),
            createFileWithTags("/photo2.jpg", "city"),
            createFileWithTags("/photo3.jpg", "nature")
        )
        val result = SentinelTagFilterHelper.filterFilesByTags(files, setOf("nature"))

        assertEquals(2, result.size)
        assertTrue(result.any { it.remotePath == "/photo1.jpg" })
        assertTrue(result.any { it.remotePath == "/photo3.jpg" })
    }

    @Test
    fun filterFilesByTags_multipleTags_andLogic() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature", "sunset"),
            createFileWithTags("/photo2.jpg", "nature", "mountain"),
            createFileWithTags("/photo3.jpg", "sunset"),
            createFileWithTags("/photo4.jpg", "nature", "sunset", "mountain")
        )
        // Files must have BOTH nature AND sunset
        val result = SentinelTagFilterHelper.filterFilesByTags(files, setOf("nature", "sunset"))

        assertEquals(2, result.size)
        assertTrue(result.any { it.remotePath == "/photo1.jpg" })
        assertTrue(result.any { it.remotePath == "/photo4.jpg" })
    }

    @Test
    fun filterFilesByTags_noMatches_returnsEmpty() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature"),
            createFileWithTags("/photo2.jpg", "city")
        )
        val result = SentinelTagFilterHelper.filterFilesByTags(files, setOf("ocean"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun filterFilesByTags_filesWithNullTags_handledGracefully() {
        val file1 = OCFile("/photo1.jpg")
        file1.tags = null
        val file2 = createFileWithTags("/photo2.jpg", "nature")

        val files = listOf(file1, file2)
        val result = SentinelTagFilterHelper.filterFilesByTags(files, setOf("nature"))

        assertEquals(1, result.size)
        assertEquals("/photo2.jpg", result[0].remotePath)
    }

    // ========== filterFilesByTagsOr (OR logic) tests ==========

    @Test
    fun filterFilesByTagsOr_emptySelection_returnsAllFiles() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature"),
            createFileWithTags("/photo2.jpg", "city")
        )
        val result = SentinelTagFilterHelper.filterFilesByTagsOr(files, emptySet())

        assertEquals(2, result.size)
    }

    @Test
    fun filterFilesByTagsOr_multipleTags_orLogic() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature"),
            createFileWithTags("/photo2.jpg", "city"),
            createFileWithTags("/photo3.jpg", "ocean"),
            createFileWithTags("/photo4.jpg", "mountain")
        )
        // Files must have nature OR city
        val result = SentinelTagFilterHelper.filterFilesByTagsOr(files, setOf("nature", "city"))

        assertEquals(2, result.size)
        assertTrue(result.any { it.remotePath == "/photo1.jpg" })
        assertTrue(result.any { it.remotePath == "/photo2.jpg" })
    }

    @Test
    fun filterFilesByTagsOr_fileWithMultipleMatchingTags_includedOnce() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature", "sunset", "mountain")
        )
        val result = SentinelTagFilterHelper.filterFilesByTagsOr(files, setOf("nature", "sunset"))

        assertEquals(1, result.size)
    }

    // ========== getUniqueTagNames tests ==========

    @Test
    fun getUniqueTagNames_emptyList_returnsEmptySet() {
        val result = SentinelTagFilterHelper.getUniqueTagNames(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun getUniqueTagNames_duplicateTags_returnsUnique() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature", "sunset"),
            createFileWithTags("/photo2.jpg", "nature", "city"),
            createFileWithTags("/photo3.jpg", "sunset")
        )
        val result = SentinelTagFilterHelper.getUniqueTagNames(files)

        assertEquals(3, result.size)
        assertTrue(result.contains("nature"))
        assertTrue(result.contains("sunset"))
        assertTrue(result.contains("city"))
    }

    // ========== countFilesWithTag tests ==========

    @Test
    fun countFilesWithTag_existingTag_returnsCorrectCount() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature"),
            createFileWithTags("/photo2.jpg", "nature", "city"),
            createFileWithTags("/photo3.jpg", "city")
        )
        val result = SentinelTagFilterHelper.countFilesWithTag(files, "nature")

        assertEquals(2, result)
    }

    @Test
    fun countFilesWithTag_nonExistentTag_returnsZero() {
        val files = listOf(
            createFileWithTags("/photo1.jpg", "nature")
        )
        val result = SentinelTagFilterHelper.countFilesWithTag(files, "ocean")

        assertEquals(0, result)
    }

    @Test
    fun countFilesWithTag_emptyList_returnsZero() {
        val result = SentinelTagFilterHelper.countFilesWithTag(emptyList(), "nature")
        assertEquals(0, result)
    }
}
