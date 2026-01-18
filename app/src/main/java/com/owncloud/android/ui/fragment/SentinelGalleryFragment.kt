/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.fragment

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.nextcloud.client.account.User
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.di.Injectable
import com.owncloud.android.R
import com.owncloud.android.databinding.FragmentSentinelGalleryBinding
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.lib.resources.tags.Tag
import com.owncloud.android.ui.activity.FileDisplayActivity
import com.owncloud.android.ui.adapter.SentinelGalleryAdapter
import com.owncloud.android.ui.helpers.SentinelTagFilterHelper
import com.owncloud.android.utils.theme.ViewThemeUtils
import javax.inject.Inject

/**
 * Fragment that displays media files with tag filtering capabilities.
 * Part of the Sentinel project for AI-tagged media visualization.
 */
class SentinelGalleryFragment : Fragment(), Injectable, SwipeRefreshLayout.OnRefreshListener {

    companion object {
        private const val TAG = "SentinelGalleryFragment"
        private const val COLUMNS_PORTRAIT = 3
        private const val COLUMNS_LANDSCAPE = 5

        fun newInstance(): SentinelGalleryFragment {
            return SentinelGalleryFragment()
        }
    }

    @Inject
    lateinit var accountManager: UserAccountManager

    @Inject
    lateinit var viewThemeUtils: ViewThemeUtils

    private var _binding: FragmentSentinelGalleryBinding? = null
    private val binding get() = _binding!!

    private var storageManager: FileDataStorageManager? = null
    private var adapter: SentinelGalleryAdapter? = null

    private var allMediaFiles: List<OCFile> = emptyList()
    private var selectedTags: MutableSet<String> = mutableSetOf()
    private var allTags: List<TagWithCount> = emptyList()
    private var useAndFilter: Boolean = true // true = AND (ALL), false = OR (ANY)

    private val columnCount: Int
        get() = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            COLUMNS_LANDSCAPE
        } else {
            COLUMNS_PORTRAIT
        }

    data class TagWithCount(val tag: Tag, val count: Int)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log_OC.d(TAG, "onCreateView")
        _binding = FragmentSentinelGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log_OC.d(TAG, "onViewCreated")

        setupStorageManager()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFilterModeToggle()
        loadMedia()
    }

    override fun onResume() {
        super.onResume()
        updateActionBar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (binding.galleryRecyclerView.layoutManager as? GridLayoutManager)?.spanCount = columnCount
    }

    override fun onRefresh() {
        loadMedia()
    }

    private fun setupStorageManager() {
        val activity = requireActivity()
        if (activity is FileDisplayActivity) {
            storageManager = activity.storageManager
        }
    }

    private fun setupRecyclerView() {
        val user = accountManager.user
        val manager = storageManager ?: return
        adapter = SentinelGalleryAdapter(
            context = requireContext(),
            user = user,
            storageManager = manager,
            viewThemeUtils = viewThemeUtils,
            onItemClick = { file -> onMediaItemClicked(file) }
        )

        binding.galleryRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), columnCount)
            adapter = this@SentinelGalleryFragment.adapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.apply {
            setOnRefreshListener(this@SentinelGalleryFragment)
            viewThemeUtils.androidx.themeSwipeRefreshLayout(this)
        }
    }

    private fun setupFilterModeToggle() {
        // Set initial state - AND is default
        binding.filterModeToggle.check(R.id.btn_filter_and)

        binding.filterModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                useAndFilter = (checkedId == R.id.btn_filter_and)
                Log_OC.d(TAG, "Filter mode changed: ${if (useAndFilter) "AND" else "OR"}")
                applyFilterAndDisplay()
            }
        }
    }

    private fun loadMedia() {
        Log_OC.d(TAG, "loadMedia")
        binding.swipeRefreshLayout.isRefreshing = true

        val manager = storageManager
        if (manager == null) {
            Log_OC.e(TAG, "StorageManager is null")
            binding.swipeRefreshLayout.isRefreshing = false
            showEmptyState()
            return
        }

        // Load all gallery items
        allMediaFiles = manager.allGalleryItems ?: emptyList()
        Log_OC.d(TAG, "Loaded ${allMediaFiles.size} media files")

        // Extract unique tags with counts
        extractTags()

        // Build tag chips
        buildTagChips()

        // Apply current filter and display
        applyFilterAndDisplay()

        binding.swipeRefreshLayout.isRefreshing = false
    }

    private fun extractTags() {
        val tagCounts = mutableMapOf<String, MutableList<Tag>>()

        allMediaFiles.forEach { file ->
            file.tags?.forEach { tag ->
                val tagName = tag.name ?: return@forEach
                tagCounts.getOrPut(tagName) { mutableListOf() }.add(tag)
            }
        }

        allTags = tagCounts.map { (name, tags) ->
            TagWithCount(tags.first(), tags.size)
        }.sortedByDescending { it.count }

        Log_OC.d(TAG, "Found ${allTags.size} unique tags")
    }

    private fun buildTagChips() {
        binding.tagChipGroup.removeAllViews()

        if (allTags.isEmpty()) {
            binding.tagFilterScroll.visibility = View.GONE
            binding.tagFilterDivider.visibility = View.GONE
            return
        }

        binding.tagFilterScroll.visibility = View.VISIBLE
        binding.tagFilterDivider.visibility = View.VISIBLE

        allTags.forEach { tagWithCount ->
            val tagName = tagWithCount.tag.name ?: return@forEach
            val chip = createTagChip(tagName, tagWithCount.count, tagWithCount.tag.color)
            binding.tagChipGroup.addView(chip)
        }
    }

    private fun createTagChip(tagName: String, count: Int, colorHex: String?): Chip {
        val chip = Chip(requireContext()).apply {
            text = "$tagName ($count)"
            isCheckable = true
            isChecked = selectedTags.contains(tagName)

            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedTags.add(tagName)
                } else {
                    selectedTags.remove(tagName)
                }
                applyFilterAndDisplay()
            }
        }

        // Apply tag color if available
        if (!colorHex.isNullOrEmpty()) {
            try {
                val color = android.graphics.Color.parseColor(
                    if (colorHex.startsWith("#")) colorHex else "#$colorHex"
                )
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(color)
                // Use white or black text based on background brightness
                val brightness = (android.graphics.Color.red(color) * 299 +
                    android.graphics.Color.green(color) * 587 +
                    android.graphics.Color.blue(color) * 114) / 1000
                chip.setTextColor(if (brightness > 128) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            } catch (e: IllegalArgumentException) {
                Log_OC.w(TAG, "Invalid color: $colorHex")
                viewThemeUtils.material.themeChipSuggestion(chip)
            }
        } else {
            viewThemeUtils.material.themeChipSuggestion(chip)
        }

        return chip
    }

    private fun applyFilterAndDisplay() {
        val filteredFiles = if (useAndFilter) {
            SentinelTagFilterHelper.filterFilesByTags(allMediaFiles, selectedTags)
        } else {
            SentinelTagFilterHelper.filterFilesByTagsOr(allMediaFiles, selectedTags)
        }

        val mode = if (useAndFilter) "AND" else "OR"
        Log_OC.d(TAG, "Displaying ${filteredFiles.size} files (filter: ${selectedTags.size} tags, mode: $mode)")

        adapter?.setFiles(filteredFiles)

        if (filteredFiles.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
        }
    }

    private fun showEmptyState() {
        binding.emptyList.root.visibility = View.VISIBLE
        binding.emptyList.emptyListViewHeadline.text = getString(R.string.file_list_empty_headline)
        binding.emptyList.emptyListViewText.text = if (selectedTags.isNotEmpty()) {
            getString(R.string.file_list_empty_search)
        } else {
            getString(R.string.file_list_empty)
        }
    }

    private fun hideEmptyState() {
        binding.emptyList.root.visibility = View.GONE
    }

    private fun onMediaItemClicked(file: OCFile) {
        val activity = requireActivity()
        if (activity is FileDisplayActivity) {
            activity.fileOperationsHelper.openFile(file)
        }
    }

    private fun updateActionBar() {
        val activity = requireActivity()
        if (activity is FileDisplayActivity) {
            activity.updateActionBarTitleAndHomeButtonByString(getString(R.string.drawer_item_gallery))
            activity.setMainFabVisible(false)
        }
    }

    fun clearTagFilter() {
        selectedTags.clear()
        buildTagChips()
        applyFilterAndDisplay()
    }
}
