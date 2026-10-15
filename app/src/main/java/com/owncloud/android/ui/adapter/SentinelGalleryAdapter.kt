/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.nextcloud.client.account.User
import com.owncloud.android.databinding.ItemSentinelGalleryBinding
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.datamodel.ThumbnailsCacheManager
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.utils.MimeTypeUtil
import com.owncloud.android.utils.theme.ViewThemeUtils

/**
 * Adapter for the Sentinel Gallery grid view.
 * Displays media thumbnails with optional tag badges.
 * Supports multi-select mode for batch operations.
 */
class SentinelGalleryAdapter(
    private val context: Context,
    private val user: User,
    private val storageManager: FileDataStorageManager,
    private val viewThemeUtils: ViewThemeUtils,
    private val onItemClick: (OCFile) -> Unit,
    private val onItemLongClick: (OCFile) -> Boolean = { false },
    private val onSelectionChanged: () -> Unit = {}
) : RecyclerView.Adapter<SentinelGalleryAdapter.ViewHolder>() {

    companion object {
        private const val TAG = "SentinelGalleryAdapter"
    }

    private var files: List<OCFile> = emptyList()
    private val asyncTasks = mutableListOf<ThumbnailsCacheManager.ThumbnailGenerationTask>()

    // Multi-select support
    var isMultiSelectMode: Boolean = false
        private set
    private val checkedFiles: MutableSet<OCFile> = mutableSetOf()

    val checkedItems: Set<OCFile>
        get() = checkedFiles.toSet()

    val checkedCount: Int
        get() = checkedFiles.size

    class ViewHolder(val binding: ItemSentinelGalleryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSentinelGalleryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        bindThumbnail(holder, file)
        bindTagBadge(holder, file)
        bindVideoIndicator(holder, file)
        bindCheckbox(holder, file)
        bindClickListener(holder, file)
    }

    override fun getItemCount(): Int = files.size

    override fun getItemId(position: Int): Long {
        return files.getOrNull(position)?.fileId ?: -1L
    }

    fun setFiles(newFiles: List<OCFile>) {
        Log_OC.d(TAG, "setFiles called with ${newFiles.size} files")
        val diffCallback = FilesDiffCallback(files, newFiles)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        files = newFiles
        diffResult.dispatchUpdatesTo(this)
        Log_OC.d(TAG, "After setFiles, adapter count: ${files.size}")
    }

    fun cleanup() {
        asyncTasks.forEach { it.cancel(true) }
        asyncTasks.clear()
    }

    // Multi-select methods

    fun enterMultiSelectMode() {
        if (!isMultiSelectMode) {
            isMultiSelectMode = true
            notifyDataSetChanged()
        }
    }

    fun exitMultiSelectMode() {
        if (isMultiSelectMode) {
            isMultiSelectMode = false
            checkedFiles.clear()
            notifyDataSetChanged()
        }
    }

    fun toggleSelection(file: OCFile) {
        if (checkedFiles.contains(file)) {
            checkedFiles.remove(file)
        } else {
            checkedFiles.add(file)
        }
        val position = files.indexOf(file)
        if (position >= 0) {
            notifyItemChanged(position)
        }
        onSelectionChanged()
    }

    fun isSelected(file: OCFile): Boolean = checkedFiles.contains(file)

    fun clearSelection() {
        checkedFiles.clear()
        notifyDataSetChanged()
    }

    fun selectAll() {
        checkedFiles.clear()
        checkedFiles.addAll(files)
        notifyDataSetChanged()
    }

    fun getFileAtPosition(position: Int): OCFile? = files.getOrNull(position)

    /**
     * Returns a copy of the current files list.
     * Used by SentinelGalleryFragment to pass filtered files to PreviewImageActivity.
     */
    fun getCurrentFiles(): List<OCFile> = files.toList()

    private fun bindThumbnail(holder: ViewHolder, file: OCFile) {
        val thumbnail = holder.binding.thumbnail
        val shimmer = holder.binding.thumbnailShimmer

        // Reset state
        thumbnail.setImageDrawable(null)
        shimmer.visibility = View.GONE

        // Try to get cached thumbnail first
        val cached = ThumbnailsCacheManager.getBitmapFromDiskCache(
            ThumbnailsCacheManager.PREFIX_THUMBNAIL + file.remoteId
        )

        if (cached != null) {
            if (MimeTypeUtil.isVideo(file)) {
                val withOverlay = ThumbnailsCacheManager.addVideoOverlay(cached, context)
                thumbnail.setImageBitmap(withOverlay)
            } else {
                thumbnail.setImageBitmap(cached)
            }
            return
        }

        // Show placeholder and generate thumbnail
        val placeholderDrawable = MimeTypeUtil.getFileTypeIcon(
            file.mimeType,
            file.fileName,
            context,
            viewThemeUtils
        )
        thumbnail.setImageDrawable(placeholderDrawable)

        // Generate thumbnail async if possible
        if (file.remoteId != null && file.isPreviewAvailable) {
            generateThumbnail(holder, file)
        }
    }

    private fun generateThumbnail(holder: ViewHolder, file: OCFile) {
        val thumbnail = holder.binding.thumbnail

        if (ThumbnailsCacheManager.cancelPotentialThumbnailWork(file, thumbnail)) {
            try {
                val task = ThumbnailsCacheManager.ThumbnailGenerationTask(
                    thumbnail,
                    storageManager,
                    user
                )

                val defaultBitmap = if (MimeTypeUtil.isVideo(file)) {
                    ThumbnailsCacheManager.mDefaultVideo
                } else {
                    ThumbnailsCacheManager.mDefaultImg
                }

                val asyncDrawable = ThumbnailsCacheManager.AsyncThumbnailDrawable(
                    context.resources,
                    defaultBitmap,
                    task
                )

                thumbnail.setImageDrawable(asyncDrawable)
                asyncTasks.add(task)
                task.execute(ThumbnailsCacheManager.ThumbnailGenerationTaskObject(file, file.remoteId))
            } catch (e: Exception) {
                Log_OC.e(TAG, "Error generating thumbnail", e)
            }
        }
    }

    private fun bindTagBadge(holder: ViewHolder, file: OCFile) {
        val badge = holder.binding.tagBadge
        val tags = file.tags

        if (tags.isNullOrEmpty()) {
            badge.visibility = View.GONE
            return
        }

        // Show first tag as badge
        val firstTag = tags.first()
        val tagName = firstTag.name

        if (tagName.isNullOrEmpty()) {
            badge.visibility = View.GONE
            return
        }

        badge.text = tagName
        badge.visibility = View.VISIBLE

        // Apply tag color if available
        val colorHex = firstTag.color
        if (!colorHex.isNullOrEmpty()) {
            try {
                val color = Color.parseColor(
                    if (colorHex.startsWith("#")) colorHex else "#$colorHex"
                )
                badge.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                // Contrast text color
                val brightness = (Color.red(color) * 299 +
                    Color.green(color) * 587 +
                    Color.blue(color) * 114) / 1000
                badge.setTextColor(if (brightness > 128) Color.BLACK else Color.WHITE)
            } catch (e: IllegalArgumentException) {
                Log_OC.w(TAG, "Invalid tag color: $colorHex")
            }
        }
    }

    private fun bindVideoIndicator(holder: ViewHolder, file: OCFile) {
        holder.binding.videoIndicator.visibility = if (MimeTypeUtil.isVideo(file)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun bindCheckbox(holder: ViewHolder, file: OCFile) {
        val checkbox = holder.binding.checkbox
        if (isMultiSelectMode) {
            checkbox.visibility = View.VISIBLE
            val isChecked = checkedFiles.contains(file)
            checkbox.setImageResource(
                if (isChecked) android.R.drawable.checkbox_on_background
                else android.R.drawable.checkbox_off_background
            )
        } else {
            checkbox.visibility = View.GONE
        }
    }

    private fun bindClickListener(holder: ViewHolder, file: OCFile) {
        holder.itemView.setOnClickListener {
            if (isMultiSelectMode) {
                toggleSelection(file)
            } else {
                onItemClick(file)
            }
        }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(file)
        }
    }

    private class FilesDiffCallback(
        private val oldList: List<OCFile>,
        private val newList: List<OCFile>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].fileId == newList[newItemPosition].fileId
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldList[oldItemPosition]
            val new = newList[newItemPosition]
            return old.fileId == new.fileId &&
                old.modificationTimestamp == new.modificationTimestamp &&
                old.etag == new.etag
        }
    }
}
