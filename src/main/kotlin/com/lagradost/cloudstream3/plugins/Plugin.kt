package com.lagradost.cloudstream3.plugins

import android.content.Context

/**
 * Desktop JVM counterpart of the Android Plugin stub.
 *
 * Every .cs3 plugin class extends com.lagradost.cloudstream3.plugins.Plugin,
 * which is NOT part of cloudstream-api.jar — the host app must provide it.
 * Without this class no plugin can even load on the JVM.
 *
 * openSettings lambdas usually render Android dialogs, so they are never
 * invoked on desktop; the field exists purely to satisfy the class shape.
 */
abstract class Plugin : BasePlugin() {
    open var openSettings: ((Context) -> Unit)? = null

    /**
     * Plugin-owned resources backed by the .cs3 archive (attached by the
     * loader via [com.cncverse.stremiobridge.plugin.PluginResources]).
     */
    var resources: android.content.res.Resources? = null

    open fun load(context: Context) {
        load()
    }
}
