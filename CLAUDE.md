# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Nextcloud Android client — official Android app for Nextcloud cloud storage. Large-scale Android app (Java + Kotlin) with multiple build flavors. The `sentinel-media-gallery` branch adds a Sentinel Gallery feature for AI-tag-based media filtering.

- **minSdk:** 28 (Android 9 Pie)
- **compileSdk / targetSdk:** 36
- **Java:** 17 (source and target)
- **Build system:** Gradle with Kotlin DSL (`build.gradle.kts`)

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

# Run ALL code quality checks (detekt + spotbugs + checkstyle + pmd + lint + spotless)
./gradlew check
```

## Testing

```bash
# Unit tests (no device required)
./gradlew testGplayDebugUnitTest

# Specific test class
./gradlew testGplayDebugUnitTest --tests "com.owncloud.android.ui.helpers.SentinelTagFilterHelperTest"

# Single test method
./gradlew testGplayDebugUnitTest --tests "com.owncloud.android.ui.helpers.SentinelTagFilterHelperTest.testFilterMethod"

# Instrumented tests (requires device/emulator)
./gradlew createGplayDebugCoverageReport -Pcoverage=true

# Specific instrumented test class
./gradlew createGplayDebugCoverageReport -Pcoverage=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.owncloud.android.datamodel.FileDataStorageManagerTest
```

Unit test reports: `app/build/reports/tests/testGplayDebugUnitTest/index.html`

## Code Quality

```bash
./gradlew detekt             # Kotlin linter (config: app/detekt.yml)
./gradlew spotbugsGplayDebug
./gradlew checkstyle
./gradlew pmd
```

**Detekt is strict:** `maxIssues: 2` — the build fails on the third violation. Key thresholds: `LargeClass: 600 lines`, `LongMethod: 60 lines`, `ComplexMethod: 10`, `MaxLineLength: 120`, `LongParameterList: 7 (functions) / 6 (constructors)`. `ForbiddenComment` catches `TODO:`, `FIXME:`, `STOPSHIP:`.

## Architecture

### Activity Inheritance Chain

```
DrawerActivity (Java, implements Injectable)
  └── FileActivity (Java, abstract)
        ├── FileDisplayActivity (Kotlin) — main file browser
        ├── SentinelGalleryActivity (Kotlin) — Sentinel media gallery
        ├── PreviewImageActivity (Kotlin) — image preview/swipe
        ├── ShareActivity
        └── many others
```

- **DrawerActivity**: Navigation drawer, account switching, toolbar setup, `menuItemId` tracking
- **FileActivity**: `storageManager` (FileDataStorageManager), `fileOperationsHelper` (share/download/delete/favorite), `user`/account management, remote operation listener
- **SentinelGalleryActivity** extends FileActivity (not FileDisplayActivity) — intentionally decoupled from the main file browser

### Dependency Injection (Dagger 2)

- Fragments and Activities implement the `Injectable` marker interface
- **Every injectable class must have a `@ContributesAndroidInjector` method in `ComponentsModule.java`** (`com.nextcloud.client.di/`). Missing this causes a **runtime crash**, not a compile error
- Fragment injection fires in `onFragmentPreAttached` — `@Inject` fields are available from `onAttach()` onwards
- Activity injection fires during `super.onCreate()`
- Non-Android components (helpers, utilities): use constructor injection or plain Kotlin objects — no Dagger

### Key Patterns

- Use `FileDataStorageManager` for file/media DB access
- Use `ViewThemeUtils` for Material Design 3 theming
- Use `Log_OC` for logging (not Android `Log`)
- Avoid direct Android platform APIs — use Nextcloud wrappers for accounts, preferences, networking, notifications
- Commits must be signed off: `git commit -sm 'message'`

### StorageManager Timing

**Critical:** `storageManager` must be accessed in `onResume()`, not `onViewCreated()`. The activity's service binder may not be connected yet during `onViewCreated`. Pattern:

```kotlin
override fun onResume() {
    super.onResume()
    storageManager = (activity as BaseActivity).storageManager
    // Now safe to query files
}
```

## Sentinel Gallery Feature

The Sentinel Gallery provides AI-tag-based media filtering on top of the standard gallery data.

### Key Files

| File | Role |
|------|------|
| `ui/activity/SentinelGalleryActivity.kt` | Host activity, owns SearchView and sort order |
| `ui/fragment/SentinelGalleryFragment.kt` | Main UI: grid, tag chips, filters, multi-select |
| `ui/adapter/SentinelGalleryAdapter.kt` | RecyclerView adapter with DiffUtil, thumbnails, multi-select |
| `ui/helpers/SentinelTagFilterHelper.kt` | Pure Kotlin `object` — stateless tag filtering (unit-testable) |
| `datamodel/VirtualFolderType.java` | Enum: `FAVORITE`, `GALLERY`, `SENTINEL`, `NONE` |
| `ui/preview/PreviewImageActivity.kt` | Modified: accepts `EXTRA_FILTERED_FILE_LIST` for Sentinel |
| `ui/preview/PreviewImagePagerAdapter.kt` | New 4-arg constructor for pre-filtered file list |

### Data Flow

1. `SentinelGalleryFragment` queries `storageManager.allGalleryItems` (same DB as regular Gallery)
2. Client-side filter pipeline: **Tag filter** → **Text search** → **Date filter** → **Sort**
3. Tag filtering supports AND (all tags must match) or OR (any tag matches)
4. Filter state persists in `SharedPreferences("sentinel_gallery_prefs")` across app restarts
5. Image click passes only the **current filtered image list** to `PreviewImageActivity` via Intent Parcelable extra — swipe-through reflects exactly what the user sees in the grid

### VirtualFolderType.SENTINEL

`SENTINEL` bypasses all DB queries in `PreviewImagePagerAdapter`. The pager uses only the pre-filtered list passed via `EXTRA_FILTERED_FILE_LIST` Intent extra. Other types (`GALLERY`, `FAVORITE`) query `FileDataStorageManager` themselves.

## Build Flavors

| Flavor | App ID | Purpose |
|--------|--------|---------|
| generic | com.nextcloud.client | F-Droid (no Google services) |
| gplay | com.nextcloud.client | Google Play Store |
| huawei | com.nextcloud.client | Huawei AppGallery |
| versionDev | com.nextcloud.android.beta | Dev/beta builds |
| qa | com.nextcloud.android.qa | QA testing |

AppScan (document scanner) is only included in `gplay` and `huawei` flavors.

## Local Library Development

Uncomment in `settings.gradle.kts`:
```kotlin
includeBuild("../android-library") {
    dependencySubstitution {
        substitute(module("com.github.nextcloud:android-library")).using(project(":library"))
    }
}
```

## License Headers

New source files require SPDX headers:

```kotlin
/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
```

## Code Style

- Line length: 120 characters (enforced by `.editorconfig` and detekt)
- Android Studio: `Settings > Editor > Code Style > Scheme: Project` + `Enable EditorConfig support`
- Standard Android Studio formatter with auto-optimize imports
- Layout naming: `activity_*.xml`, `fragment_*.xml`, `dialog_*.xml`, `item_*.xml`, `partial_*.xml`
- Menu naming: `activity_*.xml` or `menu_*.xml`

## Branching

- All PRs target `master` branch
- Bug fixes for released versions: backport via `/backport to stable-X.Y` comment
- Feature branches from master, bug fix branches from stable-X.Y.x
