package com.lagradost.cloudstream3

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.utils.Event
import java.io.File

/**
 * Desktop JVM stub of CloudStream's MainActivity.
 *
 * Plugins reference companion members (events, constants) from their
 * non-settings code paths, so the class must exist to keep them loadable.
 * Extends the AppCompatActivity stub to mirror the Android hierarchy for
 * plugins that cast the context.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        const val TAG = "MAINACT"
        const val ANIMATED_OUTLINE: Boolean = false
        var lastError: String? = null

        fun setLastError(context: Any?) {}

        const val API_NAME_EXTRA_KEY = "API_NAME_EXTRA_KEY"

        fun deleteFileOnExit(file: File) {}

        var nextSearchQuery: String? = null

        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()
    }
}
