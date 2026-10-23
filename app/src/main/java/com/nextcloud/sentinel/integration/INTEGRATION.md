# Kill Switch Integration Guide

This document explains how to integrate Kill Switch protection into the Nextcloud Android codebase.

## Overview

The Kill Switch system protects against ransomware by:
1. Detecting suspicious file patterns (high entropy, mass deletions, canary modifications)
2. Pausing sync before encrypted/malicious files are uploaded
3. Alerting the user with a high-priority notification

## Integration Points

### 1. Application Startup (MainApp.java)

**File:** `app/src/main/java/com/owncloud/android/MainApp.java`

**Location:** In `onCreate()` method, after Dagger injection is complete.

```java
// Add import at top
import com.nextcloud.sentinel.integration.KillSwitchIntegration;

// In onCreate(), after this line:
//   appComponent.inject(this);
// Add:

// Initialize Kill Switch protection
KillSwitchIntegration.initialize(this);

// Optional: Start monitoring sync folders
List<String> syncPaths = new ArrayList<>();
syncPaths.add(getStoragePath()); // Add your sync folder path
KillSwitchIntegration.startFileMonitoring(syncPaths);
```

### 2. File Upload Worker (FileUploadWorker.kt)

**File:** `app/src/main/java/com/nextcloud/client/jobs/upload/FileUploadWorker.kt`

**Location:** In `uploadFiles()` method, before processing each upload.

```kotlin
// Add import at top
import com.nextcloud.sentinel.integration.KillSwitchIntegration

// In uploadFiles(), inside the for loop, before creating operation:
// (around line 256, after "for ((index, upload) in uploads.withIndex())")

// Kill Switch check - block if threat detected
if (KillSwitchIntegration.isTriggered()) {
    Log_OC.w(TAG, "Upload blocked - Kill Switch triggered")
    return@withContext Result.failure()
}

// Check individual file
val localFile = File(upload.localPath)
if (!KillSwitchIntegration.shouldAllowUpload(localFile)) {
    Log_OC.w(TAG, "File blocked by Kill Switch: ${localFile.name}")
    continue // Skip this file, continue with others
}
```

**Alternative - Block entire batch:**

```kotlin
// At the start of uploadFiles(), after getting uploads list:
val allowedUploads = uploads.filter { upload ->
    val file = File(upload.localPath)
    KillSwitchIntegration.shouldAllowUpload(file)
}

if (allowedUploads.isEmpty() && uploads.isNotEmpty()) {
    Log_OC.w(TAG, "All uploads blocked by Kill Switch")
    return@withContext Result.failure()
}

// Then iterate over allowedUploads instead of uploads
```

### 3. Content Observer (ContentObserverWork.kt)

**File:** `app/src/main/java/com/nextcloud/client/jobs/ContentObserverWork.kt`

**Location:** In `checkAndTriggerAutoUpload()`, before starting auto-upload.

```kotlin
// Add import at top
import com.nextcloud.sentinel.integration.KillSwitchIntegration
import com.nextcloud.sentinel.killswitch.FileEvent

// In checkAndTriggerAutoUpload(), before FilesSyncHelper.startAutoUploadImmediately:

// Kill Switch check
if (KillSwitchIntegration.isTriggered()) {
    Log_OC.w(TAG, "Auto-upload blocked - Kill Switch triggered")
    return@withContext
}

// Optionally analyze the changed files
contentUris.forEach { uri ->
    // Convert content URI to file path and analyze
    // This depends on how content URIs are resolved in your codebase
}
```

### 4. Sync Folder Handler (SyncFolderHandler.java)

**File:** `app/src/main/java/com/owncloud/android/syncadapter/SyncFolderHandler.java`

**Location:** Before processing sync queue.

```java
// Add import
import com.nextcloud.sentinel.integration.KillSwitchIntegration;

// In handleMessage() or sync processing method:
if (KillSwitchIntegration.isTriggered()) {
    Log_OC.w(TAG, "Sync blocked - Kill Switch triggered");
    return; // Or handle appropriately
}
```

## Settings Integration

### Add Kill Switch to Settings UI

**File:** Create or modify settings fragment

```kotlin
// In your settings fragment:
val killSwitchEnabled = findPreference<SwitchPreference>("kill_switch_enabled")
killSwitchEnabled?.setOnPreferenceChangeListener { _, newValue ->
    KillSwitchIntegration.setEnabled(newValue as Boolean)
    true
}

// Show current status
val killSwitchStatus = findPreference<Preference>("kill_switch_status")
if (KillSwitchIntegration.isTriggered()) {
    killSwitchStatus?.summary = "ALERT: Threat detected - sync paused"
} else {
    killSwitchStatus?.summary = "Active - monitoring for threats"
}
```

### Settings XML

```xml
<PreferenceCategory android:title="Security">
    <SwitchPreference
        android:key="kill_switch_enabled"
        android:title="Kill Switch Protection"
        android:summary="Detect and block ransomware attacks"
        android:defaultValue="true" />

    <Preference
        android:key="kill_switch_status"
        android:title="Protection Status"
        android:selectable="false" />
</PreferenceCategory>
```

## Notification Actions

### Register BroadcastReceiver for notification actions

**File:** `AndroidManifest.xml`

```xml
<receiver
    android:name="com.nextcloud.sentinel.actions.AlertActionReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.nextcloud.sentinel.DISMISS_THREAT" />
        <action android:name="com.nextcloud.sentinel.RESUME_SYNC" />
    </intent-filter>
</receiver>
```

**Create AlertActionReceiver.kt:**

```kotlin
package com.nextcloud.sentinel.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nextcloud.sentinel.integration.KillSwitchIntegration

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlertAction.ACTION_DISMISS_THREAT -> {
                val notificationId = intent.getIntExtra(AlertAction.EXTRA_NOTIFICATION_ID, -1)
                // Just dismiss notification, keep sync paused
            }
            AlertAction.ACTION_RESUME_SYNC -> {
                // User confirmed - reset Kill Switch and resume
                KillSwitchIntegration.reset()
            }
        }
    }
}
```

## Testing the Integration

### Manual Test Scenarios

1. **High Entropy Test:**
   - Create a file filled with random bytes
   - Trigger sync
   - Expected: File blocked, notification shown

2. **Mass Delete Test:**
   - Delete 20+ files in quick succession
   - Expected: Sync paused, notification shown

3. **Normal Operation Test:**
   - Create/modify normal text/image files
   - Expected: Files sync normally

### Automated Tests

See `app/src/test/java/com/nextcloud/sentinel/` for unit tests.

## Troubleshooting

### Kill Switch not triggering

1. Check `KillSwitchIntegration.isEnabled()` returns true
2. Verify detectors are registered: check logs for "Detectors registered"
3. Ensure thresholds are appropriate (default: entropy >= 7.9 for CRITICAL)

### False positives

1. Adjust thresholds in detector configurations
2. Add file extension whitelist for known safe encrypted formats
3. Review logs to understand detection patterns

### Notifications not showing

1. Verify notification channel created (Android O+)
2. Check notification permissions
3. Ensure AlertAction is registered with KillSwitchManager

## Architecture Reference

```
com.nextcloud.sentinel/
├── killswitch/
│   ├── KillSwitchManager.kt      # Central coordinator
│   ├── ThreatDetector.kt         # Base interface
│   ├── ThreatInfo.kt             # Threat data
│   └── detectors/
│       ├── EntropyDetector.kt    # High entropy detection
│       ├── MassDeleteDetector.kt # Mass deletion detection
│       └── CanaryDetector.kt     # Trap file detection
├── monitoring/
│   ├── FileChangeObserver.kt     # FileObserver wrapper
│   └── SyncEventListener.kt      # Sync event hooks
├── actions/
│   ├── PauseSyncAction.kt        # Pause sync response
│   └── AlertAction.kt            # Notification response
└── integration/
    └── KillSwitchIntegration.kt  # Integration facade
```

## API Reference

### KillSwitchIntegration

| Method | Description |
|--------|-------------|
| `initialize(context)` | Initialize at app startup |
| `shouldAllowUpload(file)` | Check if file can be uploaded |
| `filterBatch(files)` | Filter list of files for upload |
| `isTriggered()` | Check if threat was detected |
| `isEnabled()` | Check if protection is on |
| `setEnabled(bool)` | Enable/disable protection |
| `reset()` | Reset after user confirmation |
| `startFileMonitoring(paths)` | Start watching folders |
| `stopFileMonitoring()` | Stop watching folders |
