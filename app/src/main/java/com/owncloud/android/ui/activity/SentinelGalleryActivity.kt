/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.ui.activity

import android.os.Bundle
import com.owncloud.android.R
import com.owncloud.android.databinding.ActivitySentinelGalleryBinding
import com.owncloud.android.ui.fragment.SentinelGalleryFragment

/**
 * Activity that hosts the Sentinel Gallery fragment.
 * Displays media files with AI-generated tag filtering.
 */
class SentinelGalleryActivity : DrawerActivity() {

    private lateinit var binding: ActivitySentinelGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySentinelGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        updateActionBarTitleAndHomeButtonByString(getString(R.string.drawer_item_sentinel_gallery))
        setupDrawer()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.sentinel_gallery_container, SentinelGalleryFragment.newInstance())
                .commit()
        }
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
}
