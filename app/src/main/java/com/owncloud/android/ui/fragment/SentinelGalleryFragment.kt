/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.fragment

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.util.Pair
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import java.util.Calendar
import com.nextcloud.client.account.User
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.di.Injectable
import com.owncloud.android.R
import com.owncloud.android.databinding.FragmentSentinelGalleryBinding
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.datamodel.VirtualFolderType
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.lib.resources.tags.Tag
import com.owncloud.android.ui.activity.BaseActivity
import com.owncloud.android.ui.activity.FileActivity
import com.owncloud.android.ui.adapter.SentinelGalleryAdapter
import com.owncloud.android.ui.dialog.RemoveFilesDialogFragment
import com.owncloud.android.ui.helpers.SentinelTagFilterHelper
import com.owncloud.android.ui.preview.PreviewImageActivity
import com.owncloud.android.ui.preview.PreviewMediaActivity
import com.owncloud.android.utils.FileSortOrder
import com.owncloud.android.utils.MimeTypeUtil
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
        private const val PREFS_NAME = "sentinel_gallery_prefs"
        private const val PREF_SELECTED_TAGS = "selected_tags"
        private const val PREF_USE_AND_FILTER = "use_and_filter"
        private const val PREF_DATE_FILTER = "date_filter"

        fun newInstance(): SentinelGalleryFragment {
            return SentinelGalleryFragment()
        }
    }

    enum class DateFilterPeriod {
        ALL, TODAY, THIS_WEEK, THIS_MONTH, THIS_YEAR, CUSTOM
    }

    @Inject
    lateinit var accountManager: UserAccountManager

    @Inject
    lateinit var viewThemeUtils: ViewThemeUtils

    private var _binding: FragmentSentinelGalleryBinding? = null
    private val binding get() = _binding!!

    private var storageManager: FileDataStorageManager? = null
    private var adapter: SentinelGalleryAdapter? = null
    private lateinit var preferences: SharedPreferences

    private var allMediaFiles: List<OCFile> = emptyList()
    private var selectedTags: MutableSet<String> = mutableSetOf()
    private var allTags: List<TagWithCount> = emptyList()
    private var useAndFilter: Boolean = true // true = AND (ALL), false = OR (ANY)
    private var searchQuery: String = ""
    private var currentSortOrder: FileSortOrder = FileSortOrder.SORT_NEW_TO_OLD
    private var dateFilterPeriod: DateFilterPeriod = DateFilterPeriod.ALL
    private var customStartDate: Long? = null
    private var customEndDate: Long? = null

    // Multi-select support
    private var activeActionMode: ActionMode? = null

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

        preferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restoreFilterState()

        setupSwipeRefresh()
        setupFilterModeToggle()
        setupDateFilter()
        setupClearButton()
    }

    override fun onResume() {
        super.onResume()
        updateActionBar()
        // Initialize storageManager and load data here, when activity is fully ready
        setupStorageManager()
        if (adapter == null) {
            setupRecyclerView()
        }
        loadMedia()
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
        if (activity is BaseActivity) {
            storageManager = activity.storageManager
        }
    }

    private fun setupRecyclerView() {
        val user = accountManager.user
        val manager = storageManager
        if (manager == null) {
            Log_OC.e(TAG, "setupRecyclerView: storageManager is null!")
            return
        }
        Log_OC.d(TAG, "setupRecyclerView: creating adapter")
        adapter = SentinelGalleryAdapter(
            context = requireContext(),
            user = user,
            storageManager = manager,
            viewThemeUtils = viewThemeUtils,
            onItemClick = { file -> onMediaItemClicked(file) },
            onItemLongClick = { file -> onMediaItemLongClicked(file) },
            onSelectionChanged = { updateActionModeTitle() }
        )

        binding.galleryRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), columnCount)
            adapter = this@SentinelGalleryFragment.adapter
        }
        Log_OC.d(TAG, "setupRecyclerView: adapter set on RecyclerView")
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.apply {
            setOnRefreshListener(this@SentinelGalleryFragment)
            viewThemeUtils.androidx.themeSwipeRefreshLayout(this)
        }
    }

    private fun setupFilterModeToggle() {
        // Set initial state based on saved preference
        binding.filterModeToggle.check(if (useAndFilter) R.id.btn_filter_and else R.id.btn_filter_or)

        binding.filterModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                useAndFilter = (checkedId == R.id.btn_filter_and)
                Log_OC.d(TAG, "Filter mode changed: ${if (useAndFilter) "AND" else "OR"}")
                saveFilterState()
                applyFilterAndDisplay()
            }
        }
    }

    private fun setupClearButton() {
        binding.clearFiltersButton.setOnClickListener {
            clearTagFilter()
        }
    }

    private fun setupDateFilter() {
        binding.dateChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val checkedId = checkedIds.first()
            val newPeriod = when (checkedId) {
                R.id.chip_all_dates -> DateFilterPeriod.ALL
                R.id.chip_today -> DateFilterPeriod.TODAY
                R.id.chip_this_week -> DateFilterPeriod.THIS_WEEK
                R.id.chip_this_month -> DateFilterPeriod.THIS_MONTH
                R.id.chip_this_year -> DateFilterPeriod.THIS_YEAR
                R.id.chip_custom_range -> {
                    showDateRangePicker()
                    return@setOnCheckedStateChangeListener
                }
                else -> DateFilterPeriod.ALL
            }

            if (newPeriod != dateFilterPeriod) {
                dateFilterPeriod = newPeriod
                customStartDate = null
                customEndDate = null
                Log_OC.d(TAG, "Date filter changed: $dateFilterPeriod")
                saveFilterState()
                applyFilterAndDisplay()
            }
        }

        // Restore saved state
        restoreDateFilterChip()
    }

    private fun restoreDateFilterChip() {
        val chipId = when (dateFilterPeriod) {
            DateFilterPeriod.ALL -> R.id.chip_all_dates
            DateFilterPeriod.TODAY -> R.id.chip_today
            DateFilterPeriod.THIS_WEEK -> R.id.chip_this_week
            DateFilterPeriod.THIS_MONTH -> R.id.chip_this_month
            DateFilterPeriod.THIS_YEAR -> R.id.chip_this_year
            DateFilterPeriod.CUSTOM -> R.id.chip_custom_range
        }
        binding.dateChipGroup.check(chipId)
    }

    private fun showDateRangePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.sentinel_date_custom)

        // Pre-select existing custom range if available
        if (customStartDate != null && customEndDate != null) {
            builder.setSelection(Pair(customStartDate, customEndDate))
        }

        val picker = builder.build()

        picker.addOnPositiveButtonClickListener { selection ->
            customStartDate = selection.first
            customEndDate = selection.second
            dateFilterPeriod = DateFilterPeriod.CUSTOM
            Log_OC.d(TAG, "Custom date range: $customStartDate to $customEndDate")
            saveFilterState()
            applyFilterAndDisplay()
        }

        picker.addOnNegativeButtonClickListener {
            // Revert to previous selection if cancelled
            restoreDateFilterChip()
        }

        picker.addOnCancelListener {
            restoreDateFilterChip()
        }

        picker.show(parentFragmentManager, "date_range_picker")
    }

    private fun getDateRangeForPeriod(period: DateFilterPeriod): kotlin.Pair<Long, Long>? {
        val calendar = Calendar.getInstance()
        val now = System.currentTimeMillis()

        return when (period) {
            DateFilterPeriod.ALL -> null
            DateFilterPeriod.TODAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                kotlin.Pair(calendar.timeInMillis, now)
            }
            DateFilterPeriod.THIS_WEEK -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                kotlin.Pair(calendar.timeInMillis, now)
            }
            DateFilterPeriod.THIS_MONTH -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                kotlin.Pair(calendar.timeInMillis, now)
            }
            DateFilterPeriod.THIS_YEAR -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                kotlin.Pair(calendar.timeInMillis, now)
            }
            DateFilterPeriod.CUSTOM -> {
                if (customStartDate != null && customEndDate != null) {
                    // Add a full day to end date to include the entire day
                    kotlin.Pair(customStartDate!!, customEndDate!! + 24 * 60 * 60 * 1000 - 1)
                } else {
                    null
                }
            }
        }
    }

    private fun saveFilterState() {
        preferences.edit()
            .putStringSet(PREF_SELECTED_TAGS, selectedTags)
            .putBoolean(PREF_USE_AND_FILTER, useAndFilter)
            .putString(PREF_DATE_FILTER, dateFilterPeriod.name)
            .apply()
        Log_OC.d(TAG, "Filter state saved: ${selectedTags.size} tags, mode: ${if (useAndFilter) "AND" else "OR"}, date: $dateFilterPeriod")
    }

    private fun restoreFilterState() {
        selectedTags = preferences.getStringSet(PREF_SELECTED_TAGS, emptySet())?.toMutableSet() ?: mutableSetOf()
        useAndFilter = preferences.getBoolean(PREF_USE_AND_FILTER, true)
        val dateFilterName = preferences.getString(PREF_DATE_FILTER, DateFilterPeriod.ALL.name)
        dateFilterPeriod = try {
            DateFilterPeriod.valueOf(dateFilterName ?: DateFilterPeriod.ALL.name)
        } catch (e: IllegalArgumentException) {
            DateFilterPeriod.ALL
        }
        Log_OC.d(TAG, "Filter state restored: ${selectedTags.size} tags, mode: ${if (useAndFilter) "AND" else "OR"}, date: $dateFilterPeriod")
    }

    private fun updateResultCount(count: Int) {
        binding.resultCountText.text = if (count == 1) {
            getString(R.string.sentinel_file_count_singular, count)
        } else {
            getString(R.string.sentinel_file_count, count)
        }
    }

    private fun updateClearButtonVisibility() {
        binding.clearFiltersButton.visibility = if (selectedTags.isNotEmpty()) View.VISIBLE else View.GONE
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
        // Step 1: Apply tag filter
        var filteredFiles = if (useAndFilter) {
            SentinelTagFilterHelper.filterFilesByTags(allMediaFiles, selectedTags)
        } else {
            SentinelTagFilterHelper.filterFilesByTagsOr(allMediaFiles, selectedTags)
        }

        // Step 2: Apply text search filter
        if (searchQuery.isNotBlank()) {
            filteredFiles = filteredFiles.filter { file ->
                file.fileName.contains(searchQuery, ignoreCase = true)
            }
        }

        // Step 3: Apply date filter
        val dateRange = getDateRangeForPeriod(dateFilterPeriod)
        if (dateRange != null) {
            filteredFiles = filteredFiles.filter { file ->
                file.modificationTimestamp in dateRange.first..dateRange.second
            }
        }

        // Step 4: Apply sorting
        filteredFiles = currentSortOrder.sortCloudFiles(
            filteredFiles.toMutableList(),
            foldersBeforeFiles = false,
            favoritesFirst = false
        )

        val mode = if (useAndFilter) "AND" else "OR"
        Log_OC.d(TAG, "Displaying ${filteredFiles.size} files (filter: ${selectedTags.size} tags, mode: $mode, search: '$searchQuery', date: $dateFilterPeriod, sort: ${currentSortOrder.name})")

        adapter?.setFiles(filteredFiles)
        updateResultCount(filteredFiles.size)
        updateClearButtonVisibility()
        saveFilterState()

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
        Log_OC.d(TAG, "Media item clicked: ${file.fileName}")
        val user = accountManager.user

        when {
            MimeTypeUtil.isImage(file) -> {
                // Get the currently displayed (filtered) files from the adapter
                val currentFiles = adapter?.getCurrentFiles() ?: emptyList()
                // Filter to only include images for the swipe preview
                val filteredImages = currentFiles.filter { MimeTypeUtil.isImage(it) }

                val intent = PreviewImageActivity.previewFileIntent(requireContext(), user, file).apply {
                    putExtra(PreviewImageActivity.EXTRA_VIRTUAL_TYPE, VirtualFolderType.SENTINEL)
                    putParcelableArrayListExtra(
                        PreviewImageActivity.EXTRA_FILTERED_FILE_LIST,
                        ArrayList(filteredImages)
                    )
                }
                startActivity(intent)
            }
            MimeTypeUtil.isVideo(file) || MimeTypeUtil.isAudio(file) -> {
                val intent = Intent(requireContext(), PreviewMediaActivity::class.java).apply {
                    putExtra(PreviewMediaActivity.EXTRA_FILE, file)
                    putExtra(PreviewMediaActivity.EXTRA_USER, user)
                    putExtra(PreviewMediaActivity.EXTRA_AUTOPLAY, true)
                }
                startActivity(intent)
            }
            else -> {
                Log_OC.w(TAG, "Unsupported file type: ${file.mimeType}")
            }
        }
    }

    private fun updateActionBar() {
        // ActionBar is handled by SentinelGalleryActivity
    }

    fun clearTagFilter() {
        selectedTags.clear()
        saveFilterState()
        buildTagChips()
        applyFilterAndDisplay()
    }

    /**
     * Called by the Activity when the search query changes.
     */
    fun setSearchQuery(query: String) {
        searchQuery = query
        Log_OC.d(TAG, "Search query changed: $query")
        applyFilterAndDisplay()
    }

    /**
     * Called by the Activity when the sort order changes.
     */
    fun setSortOrder(sortOrder: FileSortOrder) {
        currentSortOrder = sortOrder
        Log_OC.d(TAG, "Sort order changed: ${sortOrder.name}")
        applyFilterAndDisplay()
    }

    // Multi-select support

    private fun onMediaItemLongClicked(file: OCFile): Boolean {
        if (activeActionMode == null) {
            startMultiSelectMode(file)
            return true
        }
        return false
    }

    private fun startMultiSelectMode(initialFile: OCFile) {
        Log_OC.d(TAG, "Starting multi-select mode")
        adapter?.enterMultiSelectMode()
        adapter?.toggleSelection(initialFile)

        activeActionMode = requireActivity().startActionMode(multiSelectCallback)
        updateActionModeTitle()
    }

    private fun exitMultiSelectMode() {
        Log_OC.d(TAG, "Exiting multi-select mode")
        adapter?.exitMultiSelectMode()
        activeActionMode?.finish()
        activeActionMode = null
    }

    private fun updateActionModeTitle() {
        val count = adapter?.checkedCount ?: 0
        activeActionMode?.title = "$count selected"
    }

    private val multiSelectCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_sentinel_multiselect, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val selectedFiles = adapter?.checkedItems?.toList() ?: emptyList()
            if (selectedFiles.isEmpty()) {
                Toast.makeText(requireContext(), "No files selected", Toast.LENGTH_SHORT).show()
                return false
            }

            return when (item.itemId) {
                R.id.action_share -> {
                    shareFiles(selectedFiles)
                    mode.finish()
                    true
                }
                R.id.action_download -> {
                    downloadFiles(selectedFiles)
                    mode.finish()
                    true
                }
                R.id.action_favorite -> {
                    toggleFavorite(selectedFiles)
                    mode.finish()
                    true
                }
                R.id.action_delete -> {
                    deleteFiles(selectedFiles)
                    // Don't finish yet - wait for dialog confirmation
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter?.exitMultiSelectMode()
            activeActionMode = null
        }
    }

    private fun shareFiles(files: List<OCFile>) {
        Log_OC.d(TAG, "Sharing ${files.size} files")
        val activity = requireActivity()
        if (activity is FileActivity) {
            activity.fileOperationsHelper?.sendFiles(files.toSet())
        }
    }

    private fun downloadFiles(files: List<OCFile>) {
        Log_OC.d(TAG, "Downloading ${files.size} files")
        val activity = requireActivity()
        if (activity is FileActivity) {
            files.forEach { file ->
                if (!file.isDown) {
                    activity.fileOperationsHelper?.syncFile(file)
                }
            }
            Toast.makeText(
                requireContext(),
                getString(R.string.filedetails_download) + ": ${files.size}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun toggleFavorite(files: List<OCFile>) {
        Log_OC.d(TAG, "Toggling favorite for ${files.size} files")
        val activity = requireActivity()
        if (activity is FileActivity) {
            // Check if all files are already favorites
            val allFavorites = files.all { it.isFavorite }
            files.forEach { file ->
                activity.fileOperationsHelper?.toggleFavoriteFile(file, !allFavorites)
            }
            val message = if (allFavorites) "Removed from favorites" else "Added to favorites"
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteFiles(files: List<OCFile>) {
        Log_OC.d(TAG, "Delete requested for ${files.size} files")
        val dialog = RemoveFilesDialogFragment.newInstance(ArrayList(files))
        dialog.show(parentFragmentManager, "RemoveFilesDialog")
    }

    /**
     * Called by the Activity when files are deleted.
     */
    fun onFilesDeleted() {
        exitMultiSelectMode()
        loadMedia()
    }
}
