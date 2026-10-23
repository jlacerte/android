/*
 * Cardea Sentinelle - Android client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte and Cardea contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Activity that displays threat details when user taps notification.
 */

package com.nextcloud.sentinel.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nextcloud.sentinel.actions.AlertAction
import com.nextcloud.sentinel.integration.KillSwitchIntegration
import com.nextcloud.sentinel.killswitch.KillSwitchManager
import com.nextcloud.sentinel.killswitch.ThreatInfo
import com.nextcloud.sentinel.killswitch.ThreatLevel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * Activity that displays detailed threat information.
 *
 * Launched when user taps the Kill Switch alert notification.
 *
 * Features:
 * - Displays threat level with color coding
 * - Shows detector name and description
 * - Lists affected files
 * - Provides Resume Sync button (with confirmation)
 * - Shows threat history
 */
@Suppress("MagicNumber", "LongMethod", "NestedBlockDepth")
class AlertActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlertActivity"

        /**
         * Create intent to launch this activity.
         */
        fun createIntent(context: Context): Intent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        /**
         * Create intent from notification.
         */
        fun createIntentFromNotification(context: Context, threatId: Long, notificationId: Int): Intent =
            Intent(context, AlertActivity::class.java).apply {
                action = AlertAction.ACTION_VIEW_THREAT
                putExtra(AlertAction.EXTRA_THREAT_ID, threatId)
                putExtra(AlertAction.EXTRA_NOTIFICATION_ID, notificationId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }

    // Views (created programmatically for simplicity)
    private lateinit var rootLayout: LinearLayout
    private lateinit var threatLevelText: TextView
    private lateinit var threatLevelIndicator: View
    private lateinit var detectorText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var timestampText: TextView
    private lateinit var affectedFilesContainer: LinearLayout
    private lateinit var resumeButton: Button
    private lateinit var dismissButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupUI()
        setupActionBar()
        handleIntent(intent)
        observeState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun setupActionBar() {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Kill Switch Alert"
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun setupUI() {
        // Create layout programmatically (avoids XML dependency for standalone module)
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        // Threat level indicator bar
        threatLevelIndicator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                24
            ).apply { bottomMargin = 32 }
        }
        rootLayout.addView(threatLevelIndicator)

        // Threat level text
        threatLevelText = TextView(this).apply {
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        rootLayout.addView(threatLevelText)

        // Status text
        statusText = TextView(this).apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        }
        rootLayout.addView(statusText)

        // Detector name
        addSectionHeader("Detected By")
        detectorText = TextView(this).apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }
        rootLayout.addView(detectorText)

        // Description
        addSectionHeader("Description")
        descriptionText = TextView(this).apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }
        rootLayout.addView(descriptionText)

        // Timestamp
        addSectionHeader("Detected At")
        timestampText = TextView(this).apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }
        rootLayout.addView(timestampText)

        // Affected files section
        addSectionHeader("Affected Files")
        affectedFilesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 48 }
        }
        rootLayout.addView(affectedFilesContainer)

        // Buttons container
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Dismiss button
        dismissButton = Button(this).apply {
            text = "Dismiss"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginEnd = 16 }
            setOnClickListener { finish() }
        }
        buttonContainer.addView(dismissButton)

        // Resume Sync button
        resumeButton = Button(this).apply {
            text = "Resume Sync"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener { showResumeConfirmation() }
        }
        buttonContainer.addView(resumeButton)

        rootLayout.addView(buttonContainer)

        scrollView.addView(rootLayout)
        setContentView(scrollView)
    }

    private fun addSectionHeader(title: String) {
        val header = TextView(this).apply {
            text = title
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            alpha = 0.7f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        rootLayout.addView(header)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == AlertAction.ACTION_VIEW_THREAT) {
            val threatId = intent.getLongExtra(AlertAction.EXTRA_THREAT_ID, -1)
            val notificationId = intent.getIntExtra(AlertAction.EXTRA_NOTIFICATION_ID, -1)

            // Find threat by ID from history
            // For now, show current threat level
        }

        // Display current state
        updateDisplay()
    }

    private fun observeState() {
        lifecycleScope.launch {
            KillSwitchManager.threatLevel.collectLatest { level ->
                updateThreatLevelDisplay(level)
            }
        }

        lifecycleScope.launch {
            KillSwitchManager.threatDetected.collectLatest { threat ->
                displayThreat(threat)
            }
        }
    }

    private fun updateDisplay() {
        val level = KillSwitchIntegration.getCurrentThreatLevel()
        updateThreatLevelDisplay(level)

        // Get last threat from history if available
        val lastThreat = KillSwitchManager.getThreats().lastOrNull()
        if (lastThreat != null) {
            displayThreat(lastThreat)
        } else {
            displayNoThreat()
        }

        // Update button state
        resumeButton.isEnabled = KillSwitchIntegration.isTriggered()
    }

    private fun updateThreatLevelDisplay(level: ThreatLevel) {
        val (text, color) = when (level) {
            ThreatLevel.CRITICAL -> "CRITICAL" to 0xFFFF0000.toInt()
            ThreatLevel.HIGH -> "HIGH" to 0xFFFF6600.toInt()
            ThreatLevel.MEDIUM -> "MEDIUM" to 0xFFFFCC00.toInt()
            ThreatLevel.LOW -> "LOW" to 0xFF0099FF.toInt()
            ThreatLevel.NONE -> "NONE" to 0xFF00CC00.toInt()
        }

        threatLevelText.text = "Threat Level: $text"
        threatLevelText.setTextColor(color)
        threatLevelIndicator.setBackgroundColor(color)

        statusText.text = when {
            KillSwitchIntegration.isTriggered() -> "Sync is PAUSED for your protection"
            else -> "Sync is running normally"
        }

        resumeButton.isEnabled = level >= ThreatLevel.HIGH
    }

    private fun displayThreat(threat: ThreatInfo) {
        detectorText.text = threat.detectorName
        descriptionText.text = threat.description

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        timestampText.text = threat.timestamp.atZone(java.time.ZoneId.systemDefault())
            .format(formatter)

        // Display affected files
        affectedFilesContainer.removeAllViews()

        if (threat.affectedFiles.isEmpty()) {
            val noFilesText = TextView(this).apply {
                text = "No specific files identified"
                alpha = 0.7f
            }
            affectedFilesContainer.addView(noFilesText)
        } else {
            threat.affectedFiles.take(20).forEach { filePath ->
                val fileName = filePath.substringAfterLast("/")
                val fileText = TextView(this).apply {
                    text = "- $fileName"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4 }
                }
                affectedFilesContainer.addView(fileText)
            }

            if (threat.affectedFiles.size > 20) {
                val moreText = TextView(this).apply {
                    text = "... and ${threat.affectedFiles.size - 20} more files"
                    alpha = 0.7f
                }
                affectedFilesContainer.addView(moreText)
            }
        }

        updateThreatLevelDisplay(threat.level)
    }

    private fun displayNoThreat() {
        detectorText.text = "N/A"
        descriptionText.text = "No threats have been detected."
        timestampText.text = "N/A"

        affectedFilesContainer.removeAllViews()
        val noFilesText = TextView(this).apply {
            text = "No affected files"
            alpha = 0.7f
        }
        affectedFilesContainer.addView(noFilesText)
    }

    private fun showResumeConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Resume Sync?")
            .setMessage(
                "Are you sure you want to resume sync operations?\n\n" +
                    "Only do this if:\n" +
                    "- You've verified this was a false positive, OR\n" +
                    "- You've resolved the security issue\n\n" +
                    "Resuming sync will allow all pending files to be uploaded."
            )
            .setPositiveButton("Resume Sync") { _, _ ->
                KillSwitchIntegration.reset()
                updateDisplay()

                // Show confirmation
                android.widget.Toast.makeText(
                    this,
                    "Sync resumed",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
