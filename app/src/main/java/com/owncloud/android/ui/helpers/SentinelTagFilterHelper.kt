/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.helpers

import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.lib.resources.tags.Tag

/**
 * Helper class for tag-based filtering logic in Sentinel Gallery.
 * Extracted from SentinelGalleryFragment to enable unit testing.
 */
object SentinelTagFilterHelper {

    /**
     * Data class representing a tag with its occurrence count.
     */
    data class TagWithCount(
        val tagName: String,
        val tagColor: String?,
        val count: Int
    )

    /**
     * Extracts unique tags from a list of files with their occurrence counts.
     * Tags are sorted by count (descending).
     *
     * @param files List of OCFile objects to extract tags from
     * @return List of TagWithCount sorted by count descending
     */
    fun extractTagsWithCounts(files: List<OCFile>): List<TagWithCount> {
        val tagCounts = mutableMapOf<String, MutableList<Tag>>()

        files.forEach { file ->
            file.tags?.forEach { tag ->
                val tagName = tag.name ?: return@forEach
                tagCounts.getOrPut(tagName) { mutableListOf() }.add(tag)
            }
        }

        return tagCounts.map { (name, tags) ->
            TagWithCount(
                tagName = name,
                tagColor = tags.first().color,
                count = tags.size
            )
        }.sortedByDescending { it.count }
    }

    /**
     * Filters files by selected tags using AND logic.
     * A file is included only if it has ALL selected tags.
     *
     * @param files List of files to filter
     * @param selectedTags Set of tag names to filter by (empty = no filter)
     * @return Filtered list of files
     */
    fun filterFilesByTags(files: List<OCFile>, selectedTags: Set<String>): List<OCFile> {
        if (selectedTags.isEmpty()) {
            return files
        }

        return files.filter { file ->
            val fileTags = file.tags?.mapNotNull { it.name }?.toSet() ?: emptySet()
            selectedTags.all { it in fileTags }
        }
    }

    /**
     * Filters files by selected tags using OR logic.
     * A file is included if it has ANY of the selected tags.
     *
     * @param files List of files to filter
     * @param selectedTags Set of tag names to filter by (empty = no filter)
     * @return Filtered list of files
     */
    fun filterFilesByTagsOr(files: List<OCFile>, selectedTags: Set<String>): List<OCFile> {
        if (selectedTags.isEmpty()) {
            return files
        }

        return files.filter { file ->
            val fileTags = file.tags?.mapNotNull { it.name }?.toSet() ?: emptySet()
            selectedTags.any { it in fileTags }
        }
    }

    /**
     * Gets all unique tag names from a list of files.
     *
     * @param files List of files to extract tag names from
     * @return Set of unique tag names
     */
    fun getUniqueTagNames(files: List<OCFile>): Set<String> {
        return files.flatMap { file ->
            file.tags?.mapNotNull { it.name } ?: emptyList()
        }.toSet()
    }

    /**
     * Counts files that match a specific tag.
     *
     * @param files List of files to search
     * @param tagName Tag name to count
     * @return Number of files with this tag
     */
    fun countFilesWithTag(files: List<OCFile>, tagName: String): Int {
        return files.count { file ->
            file.tags?.any { it.name == tagName } == true
        }
    }
}
