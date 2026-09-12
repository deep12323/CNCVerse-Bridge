package com.lagradost.cloudstream3

import android.content.Context
import com.cncverse.stremiobridge.plugin.PluginCallContext
import com.cncverse.stremiobridge.repo.loadExtensionSettings
import com.cncverse.stremiobridge.repo.saveExtensionSettings
import com.cncverse.stremiobridge.settings.PluginSettingsSchemaRegistry

class CloudStreamApp {
    companion object {
        @PublishedApi
        internal var inMemorySettings: MutableMap<String, String>? = null

        /**
         * Returns the settings map wrapped in a discovery hook: every read by
         * PLUGIN code (inline getKey is compiled into plugin bytecode and reads
         * this map directly) registers the key in the settings schema so the
         * desktop gear dialog can render it.
         */
        @PublishedApi
        internal fun getSettings(): MutableMap<String, String> {
            if (inMemorySettings == null) {
                inMemorySettings = SettingsHookMap().also { map ->
                    map.putAll(loadExtensionSettings())
                }
            }
            return inMemorySettings!!
        }

        private class SettingsHookMap : LinkedHashMap<String, String>() {
            override fun get(key: String): String? {
                if (!PluginCallContext.suppressSettingsHook.get()) {
                    val caller = PluginCallContext.getCallingPluginName()
                    if (caller != null) {
                        PluginSettingsSchemaRegistry.register(caller, key, "String", null, storageKey = key)
                    }
                }
                return super.get(key)
            }
        }

        /**
         * Attributes a DataStore access to the calling plugin and registers the
         * key in the settings schema so the desktop settings dialog can show it.
         */
        @PublishedApi
        internal fun registerSchemaKey(path: String, value: Any?) {
            val pluginName = PluginCallContext.getCallingPluginName() ?: return
            val type = when (value) {
                is Boolean -> "Boolean"
                is Int -> "Int"
                is Long -> "Long"
                is Float -> "Float"
                is Set<*> -> "StringSet"
                else -> "String"
            }
            // The plugin's path is used verbatim as the settings-map key (no
            // pref prefix), so the dialog must read/write exactly this key
            PluginSettingsSchemaRegistry.register(pluginName, path, type, value, storageKey = path)
        }

        inline fun <reified T> getKey(path: String): T? {
            registerSchemaKey(path, null)
            val strValue = getSettings()[path] ?: return null
            return when (T::class) {
                String::class -> strValue as T
                Int::class -> strValue.toIntOrNull() as? T
                Boolean::class -> strValue.toBooleanStrictOrNull() as? T
                Float::class -> strValue.toFloatOrNull() as? T
                Long::class -> strValue.toLongOrNull() as? T
                Double::class -> strValue.toDoubleOrNull() as? T
                else -> null
            }
        }

        fun setKey(path: String, value: Any?) {
            registerSchemaKey(path, value)
            val settings = getSettings()
            if (value == null) {
                settings.remove(path)
            } else {
                settings[path] = value.toString()
            }
            saveExtensionSettings(settings)
        }

        fun getContext(): Context? {
            return android.content.DesktopContext
        }
    }
}
