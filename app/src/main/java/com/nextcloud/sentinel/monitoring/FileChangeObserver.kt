/*
 * Cardea Sentinelle - Android client
 *
 * SPDX-FileCopyrightText: 2026 Justin Lacerte and Cardea contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Wrapper around Android FileObserver for monitoring file system changes.
 */

package com.nextcloud.sentinel.monitoring

import android.os.Build
import android.os.FileObserver
import android.util.Log
import com.nextcloud.sentinel.killswitch.FileEvent
import com.nextcloud.sentinel.killswitch.KillSwitchManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors file system changes in sync folders and reports to KillSwitchManager.
 *
 * This observer wraps Android's FileObserver to detect:
 * - File creation (CREATE)
 * - File modification (MODIFY)
 * - File deletion (DELETE)
 * - File rename/move (MOVED_FROM, MOVED_TO)
 *
 * Usage:
 * ```kotlin
 * val observer = FileChangeObserver("/sdcard/Nextcloud")
 * observer.startWatching()
 * // ... later
 * observer.stopWatching()
 * ```
 *
 * Note: FileObserver watches a single directory. For recursive monitoring,
 * use FileChangeObserverManager which creates observers for subdirectories.
 */
class FileChangeObserver(
    private val watchPath: String,
    private val recursive: Boolean = false,
    private val onFileEvent: ((File, FileEvent.EventType) -> Unit)? = null
) {
    companion object {
        private const val TAG = "FileChangeObserver"

        // Events we care about
        private const val EVENTS_MASK = (
            FileObserver.CREATE or
                FileObserver.MODIFY or
                FileObserver.DELETE or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or
                FileObserver.CLOSE_WRITE // File finished writing
            )
    }

    private var observer: FileObserver? = null
    private val childObservers = ConcurrentHashMap<String, FileChangeObserver>()
    private var isWatching = false

    /**
     * Start watching the directory for changes
     */
    @Synchronized
    fun startWatching() {
        if (isWatching) {
            Log.w(TAG, "Already watching: $watchPath")
            return
        }

        val watchDir = File(watchPath)
        if (!watchDir.exists() || !watchDir.isDirectory) {
            Log.e(TAG, "Cannot watch non-existent or non-directory: $watchPath")
            return
        }

        observer = createObserver(watchPath)
        observer?.startWatching()
        isWatching = true

        Log.i(TAG, "Started watching: $watchPath")

        // If recursive, add observers for subdirectories
        if (recursive) {
            watchDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                addChildObserver(subDir.absolutePath)
            }
        }
    }

    /**
     * Stop watching the directory
     */
    @Synchronized
    fun stopWatching() {
        if (!isWatching) return

        observer?.stopWatching()
        observer = null
        isWatching = false

        // Stop all child observers
        childObservers.values.forEach { it.stopWatching() }
        childObservers.clear()

        Log.i(TAG, "Stopped watching: $watchPath")
    }

    /**
     * Check if currently watching
     */
    fun isWatching(): Boolean = isWatching

    /**
     * Get the path being watched
     */
    fun getWatchPath(): String = watchPath

    @Suppress("DEPRECATION")
    private fun createObserver(path: String): FileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : FileObserver(File(path), EVENTS_MASK) {
            override fun onEvent(event: Int, path: String?) {
                handleEvent(event, path)
            }
        }
    } else {
        object : FileObserver(path, EVENTS_MASK) {
            override fun onEvent(event: Int, path: String?) {
                handleEvent(event, path)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun handleEvent(event: Int, relativePath: String?) {
        if (relativePath == null) return

        // Ignore temporary/hidden files
        if (relativePath.startsWith(".") || relativePath.endsWith(".tmp")) {
            return
        }

        val fullPath = "$watchPath/$relativePath"
        val file = File(fullPath)

        // Determine event type
        val eventType = when {
            event and FileObserver.CREATE != 0 -> {
                // If a directory was created and we're recursive, watch it
                if (recursive && file.isDirectory) {
                    addChildObserver(fullPath)
                }
                FileEvent.EventType.CREATE
            }

            event and FileObserver.CLOSE_WRITE != 0 -> {
                // CLOSE_WRITE is more reliable than MODIFY for detecting file changes
                FileEvent.EventType.MODIFY
            }

            event and FileObserver.MODIFY != 0 -> {
                FileEvent.EventType.MODIFY
            }

            event and FileObserver.DELETE != 0 -> {
                // If a directory was deleted, remove its observer
                if (recursive) {
                    removeChildObserver(fullPath)
                }
                FileEvent.EventType.DELETE
            }

            event and FileObserver.MOVED_FROM != 0 -> {
                if (recursive) {
                    removeChildObserver(fullPath)
                }
                FileEvent.EventType.DELETE // Treat as delete from this location
            }

            event and FileObserver.MOVED_TO != 0 -> {
                if (recursive && file.isDirectory) {
                    addChildObserver(fullPath)
                }
                FileEvent.EventType.CREATE // Treat as create at this location
            }

            else -> return // Unknown event, ignore
        }

        Log.d(TAG, "File event: $eventType - $fullPath")

        // Notify callback if provided
        onFileEvent?.invoke(file, eventType)

        // Always report to KillSwitchManager
        if (file.exists() || eventType == FileEvent.EventType.DELETE) {
            KillSwitchManager.analyzeFile(file, eventType)
        }
    }

    private fun addChildObserver(path: String) {
        if (childObservers.containsKey(path)) return

        val childObserver = FileChangeObserver(path, recursive = true, onFileEvent = onFileEvent)
        childObserver.startWatching()
        childObservers[path] = childObserver

        Log.d(TAG, "Added child observer: $path")
    }

    private fun removeChildObserver(path: String) {
        childObservers.remove(path)?.let { observer ->
            observer.stopWatching()
            Log.d(TAG, "Removed child observer: $path")
        }
    }
}

/**
 * Manages multiple FileChangeObservers for different sync folders.
 *
 * Usage:
 * ```kotlin
 * val manager = FileChangeObserverManager()
 * manager.addWatchPath("/sdcard/Nextcloud")
 * manager.addWatchPath("/sdcard/Documents")
 * manager.startAll()
 * // ... later
 * manager.stopAll()
 * ```
 */
class FileChangeObserverManager {
    companion object {
        private const val TAG = "FileObserverManager"
    }

    private val observers = ConcurrentHashMap<String, FileChangeObserver>()
    private var globalCallback: ((File, FileEvent.EventType) -> Unit)? = null

    /**
     * Set a global callback for all file events
     */
    fun setGlobalCallback(callback: (File, FileEvent.EventType) -> Unit) {
        globalCallback = callback
    }

    /**
     * Add a path to watch
     * @param path Directory path to watch
     * @param recursive Watch subdirectories as well
     */
    fun addWatchPath(path: String, recursive: Boolean = true) {
        if (observers.containsKey(path)) {
            Log.w(TAG, "Path already being watched: $path")
            return
        }

        val observer = FileChangeObserver(path, recursive, globalCallback)
        observers[path] = observer
        Log.i(TAG, "Added watch path: $path (recursive=$recursive)")
    }

    /**
     * Remove a watch path
     */
    fun removeWatchPath(path: String) {
        observers.remove(path)?.let { observer ->
            observer.stopWatching()
            Log.i(TAG, "Removed watch path: $path")
        }
    }

    /**
     * Start all observers
     */
    fun startAll() {
        observers.values.forEach { it.startWatching() }
        Log.i(TAG, "Started ${observers.size} observers")
    }

    /**
     * Stop all observers
     */
    fun stopAll() {
        observers.values.forEach { it.stopWatching() }
        Log.i(TAG, "Stopped ${observers.size} observers")
    }

    /**
     * Get list of watched paths
     */
    fun getWatchedPaths(): List<String> = observers.keys.toList()

    /**
     * Check if a specific path is being watched
     */
    fun isWatching(path: String): Boolean = observers[path]?.isWatching() == true
}
