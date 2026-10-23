/*
 * Nextcloud Sentinel Android - Kill Switch Settings Fragment
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud Sentinel Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Settings fragment for Kill Switch configuration.
 * Uses a simple UI approach compatible with Nextcloud's architecture.
 */

package com.nextcloud.sentinel.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nextcloud.sentinel.integration.KillSwitchIntegration
import com.nextcloud.sentinel.killswitch.KillSwitchManager
import com.nextcloud.sentinel.killswitch.ThreatLevel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Settings fragment for Kill Switch protection configuration.
 *
 * Features:
 * - Enable/disable Kill Switch protection
 * - View current threat level and status
 * - Reset after threat detection
 */
class KillSwitchSettingsFragment : Fragment() {

    companion object {
        private const val TAG = "KillSwitchSettings"

        fun newInstance(): KillSwitchSettingsFragment {
            return KillSwitchSettingsFragment()
        }
    }

    private var enabledSwitch: Switch? = null
    private var statusText: TextView? = null
    private var threatLevelText: TextView? = null
    private var resetButton: Button? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return createUI()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe threat level changes
        viewLifecycleOwner.lifecycleScope.launch {
            KillSwitchManager.threatLevel.collectLatest { level ->
                updateThreatLevelDisplay(level)
            }
        }

        // Observe enabled state
        viewLifecycleOwner.lifecycleScope.launch {
            KillSwitchManager.enabled.collectLatest { enabled ->
                enabledSwitch?.isChecked = enabled
                updateStatusDisplay()
            }
        }
    }

    private fun createUI(): View {
        val context = requireContext()

        val scrollView = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // Title
        rootLayout.addView(TextView(context).apply {
            text = "Kill Switch Protection"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        })

        // Description
        rootLayout.addView(TextView(context).apply {
            text = "Detect and block ransomware attacks before files are synced to your cloud storage."
            textSize = 14f
            alpha = 0.7f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        })

        // Enable switch row
        val enableRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }

        enableRow.addView(TextView(context).apply {
            text = "Enable Kill Switch"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })

        enabledSwitch = Switch(context).apply {
            isChecked = KillSwitchIntegration.isEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                KillSwitchIntegration.setEnabled(isChecked)
                updateStatusDisplay()
            }
        }
        enableRow.addView(enabledSwitch)
        rootLayout.addView(enableRow)

        // Divider
        rootLayout.addView(createDivider(context))

        // Status section
        rootLayout.addView(createSectionHeader(context, "Current Status"))

        statusText = TextView(context).apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        rootLayout.addView(statusText)

        // Threat level section
        rootLayout.addView(createSectionHeader(context, "Threat Level"))

        threatLevelText = TextView(context).apply {
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        }
        rootLayout.addView(threatLevelText)

        // Divider
        rootLayout.addView(createDivider(context))

        // Reset button
        resetButton = Button(context).apply {
            text = "Reset Kill Switch"
            isEnabled = KillSwitchIntegration.isTriggered()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener { showResetConfirmation() }
        }
        rootLayout.addView(resetButton)

        // View threats button
        rootLayout.addView(Button(context).apply {
            text = "View Threat Details"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener {
                startActivity(AlertActivity.createIntent(requireContext()))
            }
        })

        // Divider
        rootLayout.addView(createDivider(context))

        // About section
        rootLayout.addView(createSectionHeader(context, "About"))
        rootLayout.addView(TextView(context).apply {
            text = "Kill Switch protects your cloud storage by detecting ransomware patterns " +
                   "and pausing sync before encrypted files are uploaded. This prevents " +
                   "ransomware from spreading through your Nextcloud.\n\n" +
                   "Detection methods:\n" +
                   "• Entropy analysis (detects encrypted files)\n" +
                   "• Mass deletion detection\n" +
                   "• Canary file monitoring"
            textSize = 14f
            alpha = 0.7f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        // Update displays
        updateStatusDisplay()
        updateThreatLevelDisplay(KillSwitchIntegration.getCurrentThreatLevel())

        scrollView.addView(rootLayout)
        return scrollView
    }

    private fun createSectionHeader(context: android.content.Context, title: String): TextView {
        return TextView(context).apply {
            text = title
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            alpha = 0.6f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
                bottomMargin = 8
            }
        }
    }

    private fun createDivider(context: android.content.Context): View {
        return View(context).apply {
            setBackgroundColor(0x20000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                topMargin = 24
                bottomMargin = 8
            }
        }
    }

    private fun updateStatusDisplay() {
        val status = when {
            !KillSwitchIntegration.isEnabled() -> "Disabled"
            KillSwitchIntegration.isTriggered() -> "⚠️ ALERT: Threat detected - sync paused"
            else -> "Active - monitoring for threats"
        }
        statusText?.text = status
        resetButton?.isEnabled = KillSwitchIntegration.isTriggered()
    }

    private fun updateThreatLevelDisplay(level: ThreatLevel) {
        val (text, color) = when (level) {
            ThreatLevel.CRITICAL -> "CRITICAL - Ransomware detected!" to 0xFFFF0000.toInt()
            ThreatLevel.HIGH -> "HIGH - Suspicious activity" to 0xFFFF6600.toInt()
            ThreatLevel.MEDIUM -> "MEDIUM - Unusual patterns" to 0xFFFFCC00.toInt()
            ThreatLevel.LOW -> "LOW - Minor anomaly" to 0xFF0099FF.toInt()
            ThreatLevel.NONE -> "None - All clear" to 0xFF00AA00.toInt()
        }

        threatLevelText?.text = text
        threatLevelText?.setTextColor(color)
        resetButton?.isEnabled = level >= ThreatLevel.HIGH
    }

    private fun showResetConfirmation() {
        val context = context ?: return

        AlertDialog.Builder(context)
            .setTitle("Reset Kill Switch?")
            .setMessage(
                "Are you sure you want to reset Kill Switch protection?\n\n" +
                "This will resume sync operations. Only do this if you have verified " +
                "the threat was a false positive or you have resolved the issue."
            )
            .setPositiveButton("Reset & Resume Sync") { _, _ ->
                KillSwitchIntegration.reset()
                updateStatusDisplay()
                updateThreatLevelDisplay(ThreatLevel.NONE)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
