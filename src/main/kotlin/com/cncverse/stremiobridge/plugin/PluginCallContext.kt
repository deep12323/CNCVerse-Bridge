package com.cncverse.stremiobridge.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps plugin classloaders to plugin names so we can attribute DataStore
 * (CloudStreamApp.getKey/setKey) calls to the plugin that made them — this is
 * how the desktop settings dialog discovers each plugin's keys.
 */
object PluginCallContext {

    // Map class loader to plugin name
    val classLoaders: MutableMap<ClassLoader, String> = ConcurrentHashMap()

    /**
     * When true, [com.lagradost.cloudstream3.CloudStreamApp]'s SettingsHookMap
     * skips its redundant String-typed registration. FilePreferences.raw()
     * sets this around its CloudStreamApp.getSettings() reads because the
     * typed register() call already happened — without the guard every
     * Boolean/Int/etc read also spawns a ghost "String" duplicate.
     */
    val suppressSettingsHook: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    fun getCallingPluginName(): String? {
        return try {
            val walker = java.lang.StackWalker.getInstance(java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE)
            walker.walk { stream ->
                stream
                    .map { frame -> frame.declaringClass }
                    .filter { clazz -> clazz.classLoader != null && classLoaders.containsKey(clazz.classLoader) }
                    .map { clazz -> classLoaders[clazz.classLoader] }
                    .findFirst()
                    .orElse(null)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
