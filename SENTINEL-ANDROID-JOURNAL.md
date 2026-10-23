# Nextcloud Sentinel Android - Development Journal

## 2026-01-11 - Kill Switch Core Module Complete

### Accomplishments

**Project Cloned**
- Forked from: https://github.com/nextcloud/android
- Location: D:\nextcloud\android-sentinel

**Kill Switch Core Module - COMPLETE**

Ported all core components from C++/Qt to Kotlin:

| Desktop (C++/Qt) | Android (Kotlin) | Status |
|------------------|------------------|--------|
| KillSwitchManager.cpp | KillSwitchManager.kt | ✅ Done |
| EntropyDetector.cpp | EntropyDetector.kt | ✅ Done |
| MassDeleteDetector.cpp | MassDeleteDetector.kt | ✅ Done |
| CanaryDetector.cpp | CanaryDetector.kt | ✅ Done |
| ThreatInfo struct | ThreatInfo.kt | ✅ Done |
| ThreatDetector interface | ThreatDetector.kt | ✅ Done |

**Files Created:**
```
app/src/main/java/com/nextcloud/sentinel/killswitch/
├── KillSwitchManager.kt      # Singleton coordinator with StateFlow
├── ThreatDetector.kt         # Base interface + FileEvent
├── ThreatInfo.kt             # ThreatLevel enum + ThreatInfo data class
└── detectors/
    ├── EntropyDetector.kt    # Shannon entropy analysis (64KB sample)
    ├── MassDeleteDetector.kt # Deletion rate/count detection
    └── CanaryDetector.kt     # Trap file monitoring
```

### Key Design Decisions

**Threading (Qt → Kotlin)**
- QRecursiveMutex → @Synchronized
- QTimer → Handler(Looper.getMainLooper())
- QVector → CopyOnWriteArrayList (thread-safe)

**Events (Qt Signals → Kotlin Flow)**
- enabledChanged → StateFlow<Boolean>
- threatDetected → SharedFlow<ThreatInfo>
- syncPaused → SharedFlow<String>

**Singleton Pattern**
- KillSwitchManager is a Kotlin `object` (singleton)
- Detectors registered at app startup
- Handlers cleanup in shutdown()

### Detection Thresholds (Configurable)

| Detector | Threshold | Level |
|----------|-----------|-------|
| Entropy | ≥ 7.9 bits/byte | CRITICAL |
| Entropy | ≥ 7.5 bits/byte | HIGH |
| MassDelete | ≥ 20 files | CRITICAL |
| MassDelete | ≥ 10 files | HIGH |
| MassDelete | > 5 files/sec | HIGH |
| Canary | Any modification | CRITICAL |

### Android Equivalents Reference

| Qt/C++ | Android/Kotlin |
|--------|----------------|
| QFileSystemWatcher | FileObserver |
| QTimer | Handler / CoroutineScope |
| QSettings | SharedPreferences / DataStore |
| QThread | Coroutines / WorkManager |
| Signals/Slots | Flow / LiveData |
| QRecursiveMutex | synchronized / Mutex |

---

## Architecture

```
app/src/main/java/com/nextcloud/sentinel/
├── killswitch/                    # ✅ COMPLETE
│   ├── KillSwitchManager.kt       # Central coordinator
│   ├── ThreatDetector.kt          # Base interface
│   ├── ThreatInfo.kt              # Threat data class
│   └── detectors/
│       ├── EntropyDetector.kt     # Shannon entropy
│       ├── MassDeleteDetector.kt  # Deletion monitoring
│       └── CanaryDetector.kt      # Trap files
├── monitoring/                    # ✅ COMPLETE
│   ├── FileChangeObserver.kt      # FileObserver wrapper + Manager
│   └── SyncEventListener.kt       # Sync hook + Provider singleton
├── actions/                       # ✅ COMPLETE
│   ├── PauseSyncAction.kt         # Pause sync + SyncController
│   ├── AlertAction.kt             # Show notification
│   └── AlertActionReceiver.kt     # Notification actions handler
├── integration/                   # ✅ COMPLETE
│   ├── KillSwitchIntegration.kt   # Main integration facade
│   └── INTEGRATION.md             # Hook documentation
└── ui/                            # ✅ COMPLETE
    ├── KillSwitchSettingsFragment.kt  # Settings avec toggles
    └── AlertActivity.kt               # Écran détail menace
```

---

## Phase 1: Monitoring - COMPLETE

**FileChangeObserver.kt:**
- Wrapper Android FileObserver
- Support récursif avec child observers
- Détection CREATE, MODIFY, DELETE, MOVED
- Intégration automatique avec KillSwitchManager
- FileChangeObserverManager pour plusieurs dossiers

**SyncEventListener.kt:**
- Interface SyncEventListener
- KillSwitchSyncListener implémentation par défaut
- SyncEventListenerProvider singleton
- Batch filtering avec analyzeBatch()
- Callbacks pour sync paused/resumed

---

## Phase 2: Actions - COMPLETE

**PauseSyncAction.kt:**
- Interface SyncController (pauseSync, resumeSync, isSyncPaused, cancelPendingUploads)
- PauseSyncAction implements SyncAction
- StateFlow<Boolean> pour état pause
- Broadcast intents (SYNC_PAUSED, SYNC_RESUMED)
- DefaultSyncController avec SharedPreferences

**AlertAction.kt:**
- Notification channel IMPORTANCE_HIGH (heads-up)
- Vibration pattern urgente + son alarme
- Actions: View Details, Dismiss, Resume Sync
- PendingIntent pour AlertActivity
- Couleurs par niveau de menace (CRITICAL=rouge, HIGH=orange, etc.)
- BigTextStyle expandable avec détails menace

---

## Phase 3: Integration - COMPLETE

**KillSwitchIntegration.kt:**
- Facade singleton pour intégration Nextcloud
- `initialize(context)` à appeler dans MainApp.onCreate()
- `shouldAllowUpload(file)` pour FileUploadWorker
- `filterBatch(files)` pour uploads batch
- `isTriggered()` / `isEnabled()` / `reset()`
- File monitoring management
- @JvmStatic pour interop Java

**AlertActionReceiver.kt:**
- BroadcastReceiver pour actions notification
- DISMISS_THREAT: dismiss notif, sync reste pausé
- RESUME_SYNC: reset KillSwitch, reprend sync

**INTEGRATION.md:**
- Documentation complète des hooks
- Code snippets pour MainApp.java
- Code snippets pour FileUploadWorker.kt
- Code snippets pour ContentObserverWork.kt
- Configuration AndroidManifest.xml
- Guide de test manuel
- Troubleshooting

---

## Phase 4: UI - COMPLETE

**KillSwitchSettingsFragment.kt:**
- PreferenceFragmentCompat moderne
- Toggle enable/disable Kill Switch
- Affichage status et threat level en temps réel
- Toggles par détecteur (Entropy, MassDelete, Canary)
- Bouton Reset avec confirmation dialog
- Observe StateFlow pour updates live

**AlertActivity.kt:**
- Écran détail menace standalone
- Affichage threat level coloré
- Liste fichiers affectés
- Boutons Resume Sync / Dismiss
- Dialog confirmation avant reset
- Layout programmatique (pas de XML dependency)

**killswitch_preferences.xml:**
- Structure PreferenceScreen moderne
- 4 catégories: Status, Detectors, Actions, Info
- SwitchPreferenceCompat pour toggles
- iconSpaceReserved=false pour layout propre

**killswitch_strings.xml:**
- 50+ strings pour toute l'UI
- Catégories, statuts, boutons, dialogs
- Messages notification et toast
- Support localisation futur

---

## Phase 5: Tests - COMPLETE

**Structure tests:**
```
app/src/test/java/com/nextcloud/sentinel/killswitch/
├── detectors/
│   ├── EntropyDetectorTest.kt      # 20+ tests entropy
│   ├── MassDeleteDetectorTest.kt   # 20+ tests mass delete
│   └── CanaryDetectorTest.kt       # 20+ tests canary
├── KillSwitchManagerTest.kt        # 30+ tests coordinator
└── KillSwitchIntegrationTest.kt    # 15+ tests full system
```

**EntropyDetectorTest.kt:**
- Calcul entropie (empty, uniform, text, random)
- Détection fichiers haute entropie
- Whitelisted extensions (jpg, mp4, zip)
- Enable/disable toggle

**MassDeleteDetectorTest.kt:**
- Comptage suppressions
- Seuils HIGH/CRITICAL
- Rate detection
- Time window expiration
- Thread safety

**CanaryDetectorTest.kt:**
- Registration/unregistration
- Modification/deletion detection
- Multiple canaries
- Path matching

**KillSwitchManagerTest.kt:**
- Enable/disable StateFlow
- Detector/Action registration
- File analysis
- Batch analysis
- Threat level updates
- Reset behavior
- Flow events

**KillSwitchIntegrationTest.kt:**
- Simulation ransomware complet
- False positive prevention
- Multi-detector coordination
- Recovery flow
- Action execution order
- Concurrent access safety
- Performance (100 analyses < 1s)

---

## Phase 6: Build - COMPLETE

**BUILD.md:**
- Guide complet de build
- Prerequisites (Android Studio, JDK, SDK)
- Build variants (generic, gplay, huawei, qa)
- Commandes Gradle pour debug/release
- Structure module Kill Switch
- Running tests
- Integration verification checklist
- Troubleshooting
- ProGuard rules

**Build Commands:**
```bash
# Debug APK
./gradlew assembleGenericDebug

# Release APK
./gradlew assembleGenericRelease

# Run Kill Switch tests
./gradlew testGenericDebugUnitTest --tests "com.nextcloud.sentinel.killswitch.*"
```

---

## Project Complete

All 7 phases implemented:

| Phase | Status | Components |
|-------|--------|------------|
| 1. Monitoring | ✅ | FileChangeObserver, SyncEventListener |
| 2. Actions | ✅ | PauseSyncAction, AlertAction |
| 3. Integration | ✅ | KillSwitchIntegration, AlertActionReceiver |
| 4. UI | ✅ | SettingsFragment, AlertActivity |
| 5. Tests | ✅ | 100+ unit/integration tests |
| 6. Build | ✅ | BUILD.md documentation |
| 7. Hooks | ✅ | MainApp, FileUploadWorker, SettingsActivity |

**Total Files Created:** 22+
- 6 core Kill Switch files
- 2 monitoring files
- 3 action files
- 2 integration files
- 3 UI files (+ KillSwitchSettingsActivity)
- 3 resource files (+ activity layout)
- 5 test files
- 2 documentation files

**Files Modified:**
- MainApp.java
- FileUploadWorker.kt
- SettingsActivity.java
- AndroidManifest.xml
- preferences.xml

---

## Phase 7: Integration Hooks - COMPLETE

**MainApp.java:**
- Added import for KillSwitchIntegration
- Added initKillSwitch() method
- Initialize with synced folder paths from SyncedFolderProvider
- Logs initialization status

**FileUploadWorker.kt:**
- Added import for KillSwitchIntegration
- Added shouldAllowUpload() check before each upload
- Skips blocked files, continues with others
- Logs blocked uploads

**AndroidManifest.xml:**
- Added KillSwitchSettingsActivity
- Added AlertActivity
- Added AlertActionReceiver with intent filters

**SettingsActivity.java:**
- Added setupKillSwitchCategory() method
- Navigates to KillSwitchSettingsActivity
- Themes preference category

**preferences.xml:**
- Added Kill Switch category after Sync
- Links to killswitch_settings preference

**New files:**
- KillSwitchSettingsActivity.kt - Host for settings fragment
- activity_killswitch_settings.xml - Simple FrameLayout container

---

## Phase 8: Build Successful - 2026-01-11

**Build Output:**
```
app/build/outputs/apk/generic/debug/generic-debug-30360000.apk
Size: 88 MB
```

**Build Fixes Applied:**
- Removed fake R class from KillSwitchSettingsFragment
- Added proper import for com.owncloud.android.R
- Changed getThreatHistory() to getThreats() in AlertActivity
- Added getDetectors() method to KillSwitchManager
- Fixed addCanaryFile -> addCanaryPattern in KillSwitchIntegration
- Simplified KillSwitchSettingsFragment to use regular Fragment (not PreferenceFragmentCompat)
  - Nextcloud uses legacy android.preference.* classes, not AndroidX
  - Rewrote as programmatic UI for compatibility

**Build Command:**
```bash
cd D:/nextcloud/android-sentinel
./gradlew clean
./gradlew assembleGenericDebug
```

---

## Next Steps

- [x] ~~Find sync/upload code in Android codebase~~
- [x] ~~Create killswitch/ module structure~~
- [x] ~~Implement EntropyDetector.kt~~
- [x] ~~Implement MassDeleteDetector.kt~~
- [x] ~~Implement CanaryDetector.kt~~
- [x] ~~Implement KillSwitchManager.kt~~
- [x] ~~Implement ThreatInfo.kt and ThreatDetector.kt interface~~
- [x] ~~Create FileChangeObserver wrapper~~
- [x] ~~Create SyncEventListener~~
- [x] ~~Create PauseSyncAction and AlertAction~~
- [x] ~~Hook into sync mechanism (FileUploadWorker.kt)~~
- [x] ~~Add UI settings fragment~~
- [x] ~~Write unit tests~~
- [x] ~~Create build documentation~~
- [x] ~~Hook into MainApp and FileUploadWorker~~
- [x] ~~Add to AndroidManifest and Settings~~
- [x] ~~Build APK successfully~~
- [ ] Test on device (manual step)

---

## Android Sync Integration Points

From codebase exploration:

**Upload Hook:**
- `FileUploadWorker.kt` - Modern WorkManager upload
- `UploadFileOperation.java` - Core upload logic
- Insert KillSwitchManager.analyzeFile() before upload

**File Change Detection:**
- `ContentObserverWork.kt` - Already watches for changes
- Can hook here to feed events to KillSwitchManager

**Sync Queue:**
- `SyncFolderHandler.java` - Manages sync queue
- Check KillSwitchManager.isTriggered() before processing

---

## Commits

_None yet - initial setup_

---

*Developed with Claude Code assistance*
*Last updated: 2026-01-11 20:00*
*Status: BUILD SUCCESSFUL - APK ready for device testing*
