/*
 * Nextcloud Sentinel Android - Kill Switch Settings Activity
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package com.nextcloud.sentinel.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.owncloud.android.R

/**
 * Activity that hosts the KillSwitchSettingsFragment.
 *
 * Provides a container for the Sentinel Kill Switch settings UI.
 */
class KillSwitchSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_killswitch_settings)

        // Setup action bar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.killswitch_settings_title)
        }

        // Load fragment if not already loaded
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.killswitch_settings_container, KillSwitchSettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
