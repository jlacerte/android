# Nextcloud Sentinel - Build Guide

## Overview

This guide explains how to build the Nextcloud Android app with the Sentinel Kill Switch module integrated.

## Prerequisites

### Development Environment

- **Android Studio**: Arctic Fox (2020.3.1) or later
- **JDK**: 17 or later
- **Android SDK**: API 36 (compile) / API 28 (minimum)
- **Gradle**: 8.x (wrapper included)

### Required SDK Components

```
SDK Platforms:
- Android 14 (API 34)
- Android 15 (API 35)
- Android 16 (API 36)

SDK Tools:
- Android SDK Build-Tools 36.x
- Android SDK Platform-Tools
- NDK (version from ndk.env)
- CMake 3.22.1
```

## Build Variants

The app supports multiple build variants:

| Flavor | Description |
|--------|-------------|
| `generic` | F-Droid/generic release |
| `gplay` | Google Play release |
| `huawei` | Huawei AppGallery |
| `qa` | QA testing builds |

Combined with build types: `debug`, `release`

## Quick Build

### Debug APK (Development)

```bash
# From project root (D:\nextcloud\android-sentinel)
./gradlew assembleGenericDebug

# Output: app/build/outputs/apk/generic/debug/app-generic-debug.apk
```

### Release APK

```bash
# Requires signing configuration
./gradlew assembleGenericRelease

# Output: app/build/outputs/apk/generic/release/app-generic-release.apk
```

## Building with Kill Switch Module

The Kill Switch module is automatically included when building. No additional configuration is needed.

### Module Structure

```
app/src/main/java/com/nextcloud/sentinel/
├── killswitch/
│   ├── KillSwitchManager.kt       # Core coordinator
│   ├── ThreatDetector.kt          # Base interface
│   ├── ThreatInfo.kt              # Threat data class
│   └── detectors/
│       ├── EntropyDetector.kt     # Shannon entropy analysis
│       ├── MassDeleteDetector.kt  # Bulk deletion detection
│       └── CanaryDetector.kt      # Trap file monitoring
├── monitoring/
│   ├── FileChangeObserver.kt      # FileObserver wrapper
│   └── SyncEventListener.kt       # Sync event hooks
├── actions/
│   ├── PauseSyncAction.kt         # Pause sync on threat
│   ├── AlertAction.kt             # Show notification
│   └── AlertActionReceiver.kt     # Notification actions
├── integration/
│   ├── KillSwitchIntegration.kt   # Main facade
│   └── INTEGRATION.md             # Hook documentation
└── ui/
    ├── KillSwitchSettingsFragment.kt
    └── AlertActivity.kt
```

### Resources

```
app/src/main/res/
├── xml/
│   └── killswitch_preferences.xml
└── values/
    └── killswitch_strings.xml
```

## Running Tests

### Unit Tests

```bash
# Run all Kill Switch tests
./gradlew testGenericDebugUnitTest --tests "com.nextcloud.sentinel.killswitch.*"

# Run specific detector tests
./gradlew testGenericDebugUnitTest --tests "com.nextcloud.sentinel.killswitch.detectors.EntropyDetectorTest"

# Run integration tests
./gradlew testGenericDebugUnitTest --tests "com.nextcloud.sentinel.killswitch.KillSwitchIntegrationTest"
```

### Test Coverage

```bash
# Generate coverage report
./gradlew jacocoTestGenericDebugUnitTestReport

# Output: app/build/reports/jacoco/
```

## Integration Verification

After building, verify the Kill Switch integration:

### 1. Check MainApp.java Integration

```java
// In MainApp.onCreate():
KillSwitchIntegration.initialize(this, getSyncFolderPaths());
```

### 2. Check FileUploadWorker.kt Integration

```kotlin
// Before upload:
if (!KillSwitchIntegration.shouldAllowUpload(file)) {
    return Result.failure()
}
```

### 3. Verify AndroidManifest.xml

```xml
<!-- Kill Switch components -->
<activity
    android:name="com.nextcloud.sentinel.ui.AlertActivity"
    android:exported="false"
    android:theme="@style/Theme.AppCompat.Light.Dialog" />

<receiver
    android:name="com.nextcloud.sentinel.actions.AlertActionReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.nextcloud.sentinel.DISMISS_THREAT" />
        <action android:name="com.nextcloud.sentinel.RESUME_SYNC" />
    </intent-filter>
</receiver>
```

## Troubleshooting

### Build Errors

#### Missing Kotlin Coroutines

If you see coroutines errors, ensure these dependencies exist in `app/build.gradle.kts`:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
```

#### StateFlow/SharedFlow Not Found

Ensure coroutines version is 1.4.0+ for Flow APIs.

#### FileObserver Deprecation Warnings

These are expected on API 29+. The code handles both old and new APIs.

### Runtime Issues

#### Kill Switch Not Triggering

1. Check `KillSwitchManager.isEnabled()` returns true
2. Verify detectors are registered
3. Check file paths are within monitored directories

#### Notifications Not Showing

1. Verify notification channel is created (Android O+)
2. Check notification permissions (Android 13+)
3. Ensure AlertAction is registered with KillSwitchManager

## Testing on Device

### Install Debug APK

```bash
# Using ADB
adb install -r app/build/outputs/apk/generic/debug/app-generic-debug.apk
```

### Manual Testing Checklist

1. **Settings UI**
   - Navigate to Settings > Kill Switch
   - Toggle enable/disable
   - Verify detector toggles work

2. **Entropy Detection**
   - Create a file with random bytes (encrypted simulation)
   - Sync the file
   - Verify notification appears

3. **Mass Delete Detection**
   - Delete 10+ files rapidly
   - Verify sync pauses

4. **Canary Detection**
   - Create a canary file in synced folder
   - Modify the canary
   - Verify immediate CRITICAL alert

5. **Recovery Flow**
   - Trigger Kill Switch
   - Tap "Resume Sync" in notification
   - Verify sync resumes

## Production Checklist

Before releasing:

- [ ] All unit tests pass
- [ ] Integration tests pass
- [ ] Manual testing complete
- [ ] ProGuard rules added (if using R8)
- [ ] Strings localized
- [ ] Performance tested (100 analyses < 1 second)

## ProGuard Rules

If using code shrinking, add to `proguard-rules.pro`:

```proguard
# Keep Kill Switch classes
-keep class com.nextcloud.sentinel.** { *; }

# Keep ThreatInfo for serialization
-keepclassmembers class com.nextcloud.sentinel.killswitch.ThreatInfo {
    <fields>;
    <init>(...);
}
```

---

*Nextcloud Sentinel Kill Switch - Protection against ransomware and malicious file operations*
