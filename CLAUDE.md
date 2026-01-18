# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Nextcloud Android client - official Android app for Nextcloud cloud storage. This is a large-scale Android application with multiple build flavors for different distribution channels.

## Build Commands

```bash
# Install git hooks (recommended first step)
./gradlew installGitHooks

# Build and install debug APK (gplay flavor - most common)
./gradlew installGplayDebug

# Build specific flavors
./gradlew assembleGenericDebug    # F-Droid (no Google services)
./gradlew assembleGplayDebug      # Google Play Store
./gradlew assembleVersionDevDebug # Dev/beta builds
./gradlew assembleHuaweiDebug     # Huawei AppGallery

# Run unit tests
./gradlew test

# Run unit tests with coverage
./gradlew createGplayDebugCoverageReport -Pcoverage=true

# Run specific test class
./gradlew createGplayDebugCoverageReport -Pcoverage=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.owncloud.android.ui.helpers.SentinelTagFilterHelperTest

# Run single test method
./gradlew createGplayDebugCoverageReport -Pcoverage=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.owncloud.android.ClassName#testMethodName

# Run instrumented tests (requires device/emulator)
./gradlew connectedGplayDebugAndroidTest

# Code quality checks
./gradlew detekt           # Kotlin linter
./gradlew spotbugsGplayDebug
./gradlew checkstyle
./gradlew pmd

# Performance analysis build (enables LeakCanary + StrictMode)
./gradlew installGplayDebug -P perfAnalysis

# Screenshot tests (requires SHOT_TEST=true env variable)
scripts/androidScreenshotTest
scripts/updateScreenshots.sh

# Clean build
./gradlew clean build
```

Coverage reports: `build/reports/coverage/gplay/debug/index.html`

## Architecture

### Dependency Injection
- **Dagger 2** for automatic DI in Android components (Activity, Fragment, Service, BroadcastReceiver, ContentProvider)
- Fragments must implement `Injectable` interface for DI support
- Non-Android components use constructor injection
- DI configuration: `com.nextcloud.client.di/`

### Key Patterns
- Activities extend `DrawerActivity` for navigation drawer integration
- Use `FileDataStorageManager` for file/media access
- Use `ViewThemeUtils` for Material Design 3 theming
- Use `Log_OC` for logging (not Android `Log`)
- Avoid direct Android platform APIs - use Nextcloud wrappers for accounts, preferences, networking, etc.

### Code Organization
```
com.owncloud.android/
├── ui/
│   ├── activity/     # Activities (extend DrawerActivity)
│   ├── fragment/     # Fragments (implement Injectable)
│   ├── adapter/      # RecyclerView adapters
│   ├── helpers/      # Business logic helpers (testable)
│   └── dialog/       # Dialogs
├── datamodel/        # Data models (OCFile, Tag, etc.)
├── operations/       # Remote operations
└── lib/              # Nextcloud library integration
```

### File Naming Convention
| Component | Pattern |
|-----------|---------|
| Activity | `activity_<name>.xml` |
| Fragment | `fragment_<name>.xml` |
| Dialog | `dialog_<name>.xml` |
| Adapter Item | `item_<type>.xml` |
| Partial | `partial_<name>.xml` |

## Build Flavors

| Flavor | App ID | Purpose |
|--------|--------|---------|
| generic | com.nextcloud.client | F-Droid (no Google services) |
| gplay | com.nextcloud.client | Google Play Store |
| huawei | com.nextcloud.client | Huawei AppGallery |
| versionDev | com.nextcloud.android.beta | Dev/beta builds |
| qa | com.nextcloud.android.qa | QA testing |

## License Headers

New source files require SPDX license headers:

```java
/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Your Name <your@email.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
```

```xml
<!--
  ~ Nextcloud - Android Client
  ~
  ~ SPDX-FileCopyrightText: 2024 Your Name <your@email.com>
  ~ SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
-->
```

## Code Style

- Line length: 120 characters
- Auto-optimize imports enabled
- Standard Android Studio formatter
- Commits must be signed off: `git commit -sm 'Your message'`

## Branching

- All PRs target `master` branch
- Bug fixes for released versions: backport via `/backport to stable-X.Y` comment
- Feature branches from master, bug fix branches from stable-X.Y.x
