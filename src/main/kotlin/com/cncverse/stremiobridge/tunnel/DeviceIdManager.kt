package com.cncverse.stremiobridge.tunnel

import com.cncverse.stremiobridge.repo.loadExtensionSettings
import com.cncverse.stremiobridge.repo.saveExtensionSettings

object DeviceIdManager {
    private const val KEY_DEVICE_ID = "KEY_DEVICE_ID"
    private const val KEY_CUSTOM_DOMAIN = "KEY_CUSTOM_DOMAIN"
    const val DEFAULT_DOMAIN = "cncverse.dpdns.org"

    fun getDeviceId(): String {
        val settings = loadExtensionSettings()
        val existing = settings[KEY_DEVICE_ID]
        if (!existing.isNullOrBlank()) {
            return existing
        }

        // Generate a new persistent 8-character hex device ID
        val charPool = "abcdefghijklmnopqrstuvwxyz0123456789"
        val randomStr = (1..8)
            .map { kotlin.random.Random.nextInt(0, charPool.length) }
            .map(charPool::get)
            .joinToString("")

        val newDeviceId = "cnc-$randomStr"
        val mutable = settings.toMutableMap()
        mutable[KEY_DEVICE_ID] = newDeviceId
        saveExtensionSettings(mutable)
        return newDeviceId
    }

    fun getCustomDomain(): String {
        val settings = loadExtensionSettings()
        return settings[KEY_CUSTOM_DOMAIN]?.ifBlank { DEFAULT_DOMAIN } ?: DEFAULT_DOMAIN
    }

    fun setCustomDomain(domain: String) {
        val settings = loadExtensionSettings().toMutableMap()
        settings[KEY_CUSTOM_DOMAIN] = domain
        saveExtensionSettings(settings)
    }

    fun getDeviceSubdomainUrl(): String {
        val deviceId = getDeviceId()
        val domain = getCustomDomain()
        return "https://$deviceId.$domain"
    }

    fun getTunnelToken(): String? {
        return loadExtensionSettings()["KEY_TUNNEL_TOKEN"]
    }

    fun setTunnelToken(token: String) {
        val settings = loadExtensionSettings().toMutableMap()
        settings["KEY_TUNNEL_TOKEN"] = token
        saveExtensionSettings(settings)
    }
}
