package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.plugin.GlobalPluginManager
import com.cncverse.stremiobridge.plugin.PluginLoader
import com.cncverse.stremiobridge.repo.PluginInstaller
import com.cncverse.stremiobridge.repo.RepoManager
import com.cncverse.stremiobridge.repo.loadExtensionSettings
import com.cncverse.stremiobridge.state.*
import com.cncverse.stremiobridge.tunnel.CloudflaredManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

private val PORT = System.getenv("PORT")?.toIntOrNull() ?: 7860
private val CACHE_DIR = File(System.getProperty("user.home"), ".cncverse_bridge").absolutePath

fun main() {
    runBlocking {
        println("🚀 Starting CNCVerse Bridge Server in Headless Mode...")
        val pluginLoader = PluginLoader()
        GlobalPluginManager.loader = pluginLoader

        val installed = withContext(Dispatchers.IO) { PluginInstaller.loadInstalledPlugins(CACHE_DIR) }
        RepoState.setInstalledPlugins(installed)
        installed.forEach { RepoState.setInstallState(it.internalName, PluginInstallState.Installed) }

        val cs3Files = PluginInstaller.getInstalledFiles(CACHE_DIR)
        GlobalPluginManager.reloadAllPlugins(installed, cs3Files)

        val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        startBridge(appScope)

        println("✅ CNCVerse Bridge Server is running on port $PORT!")
        awaitCancellation()
    }
}

private suspend fun startBridge(appScope: CoroutineScope) {
    ServerState.updateStatus(ServerStatus.Starting("Loading plugin registry…"))
    val installed = withContext(Dispatchers.IO) { PluginInstaller.loadInstalledPlugins(CACHE_DIR) }
    RepoState.setInstalledPlugins(installed)
    installed.forEach { RepoState.setInstallState(it.internalName, PluginInstallState.Installed) }
    ServerState.info("Found ${installed.size} installed plugin(s)")

    RepoManager.loadSavedRepos()
    ServerState.updateStatus(ServerStatus.Starting("Refreshing repos…"))

    // Refresh repos in background (does NOT block server startup)
    appScope.launch(Dispatchers.IO) {
        runCatching {
            RepoManager.refreshAllRepos()
            val toUpdate = RepoState.installedPlugins.value.filter {
                RepoState.getInstallState(it.internalName) is PluginInstallState.UpdateAvailable
            }
            if (toUpdate.isNotEmpty()) {
                ServerState.info("Auto-updating ${toUpdate.size} plugin(s)…")
                PluginInstaller.autoUpdateInstalled(CACHE_DIR)
                val updatedInstalled = PluginInstaller.loadInstalledPlugins(CACHE_DIR)
                val updatedCs3Files = PluginInstaller.getInstalledFiles(CACHE_DIR)
                GlobalPluginManager.reloadAllPlugins(updatedInstalled, updatedCs3Files)
            }

            val autoInstallRaw = System.getenv("AUTO_INSTALL_EXTENSIONS")
            if (!autoInstallRaw.isNullOrBlank()) {
                val targets = autoInstallRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                if (targets.isNotEmpty()) {
                    val available = RepoState.availablePlugins.value
                    val toInstall = available.filter { ap ->
                        targets.contains("all") ||
                        targets.contains(ap.plugin.internalName.lowercase()) ||
                        targets.contains(ap.plugin.name.lowercase())
                    }
                    var newlyInstalled = false
                    toInstall.forEach { ap ->
                        if (!RepoState.isInstalled(ap.plugin.internalName)) {
                            ServerState.info("Auto-installing requested extension '${ap.plugin.name}'…")
                            val ok = PluginInstaller.installPlugin(ap, CACHE_DIR)
                            if (ok) newlyInstalled = true
                        }
                    }
                    if (newlyInstalled) {
                        val updatedInstalled = PluginInstaller.loadInstalledPlugins(CACHE_DIR)
                        val updatedCs3Files = PluginInstaller.getInstalledFiles(CACHE_DIR)
                        GlobalPluginManager.reloadAllPlugins(updatedInstalled, updatedCs3Files)
                    }
                }
            }
        }.onFailure { e ->
            ServerState.warn("Repo refresh error: ${e.message}")
        }
    }

    ServerState.updateStatus(ServerStatus.Starting("Waiting for plugins…"))
    val pluginsReady = withTimeoutOrNull(30_000) {
        GlobalPluginManager.isPluginsLoaded.first { it }
    }
    if (pluginsReady == null) {
        ServerState.warn("Timed out waiting for plugin load — starting server with whatever is available")
    }
    val loadedInfos = ServerState.globalLoadedPlugins.value

    val ipAddress = getLocalIpAddress() ?: "127.0.0.1"
    CloudflaredManager.deviceIp = ipAddress
    val boundPort = StremioServer.start(PORT, CACHE_DIR)

    ServerState.updateStatus(
        ServerStatus.Running(
            port          = boundPort,
            loadedPlugins = loadedInfos,
            ipAddress     = ipAddress,
        )
    )
    ServerState.info("🎬 Bridge running at http://$ipAddress:$boundPort/manifest.json")

    // Periodic Background Extension Auto-Update (every 1 hour)
    appScope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(60 * 60 * 1000L)
            runCatching {
                ServerState.info("Checking for extension updates…")
                RepoManager.refreshAllRepos()
                val toUpdate = RepoState.installedPlugins.value.filter {
                    RepoState.getInstallState(it.internalName) is PluginInstallState.UpdateAvailable
                }
                if (toUpdate.isNotEmpty()) {
                    ServerState.info("Auto-updating ${toUpdate.size} extension(s)…")
                    PluginInstaller.autoUpdateInstalled(CACHE_DIR)
                    val updatedInstalled = PluginInstaller.loadInstalledPlugins(CACHE_DIR)
                    val updatedCs3Files = PluginInstaller.getInstalledFiles(CACHE_DIR)
                    GlobalPluginManager.reloadAllPlugins(updatedInstalled, updatedCs3Files)
                }
            }
        }
    }
}

private fun getLocalIpAddress(): String? = try {
    val interfaces = NetworkInterface.getNetworkInterfaces().asSequence().toList()
    val preferred = interfaces.filter { iface ->
        iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
        (iface.name.contains("wlan", ignoreCase = true) ||
         iface.name.contains("eth", ignoreCase = true) ||
         iface.name.contains("en", ignoreCase = true))
    }
    val candidates = if (preferred.isNotEmpty()) preferred else interfaces.filter { iface ->
        iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
        !iface.name.contains("p2p", ignoreCase = true) &&
        !iface.name.contains("dummy", ignoreCase = true) &&
        !iface.name.contains("tun", ignoreCase = true) &&
        !iface.name.contains("rmnet", ignoreCase = true)
    }
    candidates
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
        .map { it.hostAddress }
        .sorted()
        .firstOrNull()
} catch (_: Exception) { null }
