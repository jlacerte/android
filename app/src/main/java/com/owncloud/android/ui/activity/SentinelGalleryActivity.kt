/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.activity

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import com.owncloud.android.R
import com.owncloud.android.databinding.ActivitySentinelGalleryBinding
import com.owncloud.android.ui.dialog.SortingOrderDialogFragment
import com.owncloud.android.ui.fragment.SentinelGalleryFragment
import com.owncloud.android.utils.FileSortOrder

/**
 * Activity that hosts the Sentinel Gallery fragment.
 * Displays media files with AI-generated tag filtering.
 * Extends FileActivity to provide file operations support (share, download, delete, favorite).
 */
class SentinelGalleryActivity : FileActivity(), SortingOrderDialogFragment.OnSortingOrderListener {

    private lateinit var binding: ActivitySentinelGalleryBinding
    private var searchView: SearchView? = null
    private var currentSortOrder: FileSortOrder = FileSortOrder.SORT_NEW_TO_OLD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySentinelGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        updateActionBarTitleAndHomeButtonByString(getString(R.string.drawer_item_sentinel_gallery) + " (v2.1)")
        setupDrawer()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.sentinel_gallery_container, SentinelGalleryFragment.newInstance(), FRAGMENT_TAG)
                .commit()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_sentinel_gallery, menu)

        val searchItem = menu.findItem(R.id.action_search)
        searchView = searchItem?.actionView as? SearchView
        searchView?.apply {
            queryHint = getString(R.string.actionbar_search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    getGalleryFragment()?.setSearchQuery(newText ?: "")
                    return true
                }
            })
        }

        return true
    }

    override fun onResume() {
        super.onResume()
        // Set the menu item as checked
        menuItemId = R.id.nav_sentinel_gallery
        setNavigationViewItemChecked()
    }

    override fun onPause() {
        menuItemId = getPreviousMenuItemId()
        setNavigationViewItemChecked()
        super.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (isDrawerOpen) {
                    closeDrawer()
                } else {
                    openDrawer()
                }
                true
            }
            R.id.action_sort -> {
                SortingOrderDialogFragment.newInstance(currentSortOrder)
                    .show(supportFragmentManager, SortingOrderDialogFragment.SORTING_ORDER_FRAGMENT)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSortingOrderChosen(selection: FileSortOrder?) {
        selection?.let {
            currentSortOrder = it
            getGalleryFragment()?.setSortOrder(it)
        }
    }

    private fun getGalleryFragment(): SentinelGalleryFragment? {
        return supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? SentinelGalleryFragment
    }

    companion object {
        private const val FRAGMENT_TAG = "SentinelGalleryFragment"
    }
}
