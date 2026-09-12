package com.cncverse.stremiobridge.repo

import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.state.*
import kotlinx.coroutines.*
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val installedJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Handles install/uninstall/update of individual plugins.
 * Installed plugin metadata is persisted as installed_plugins.json in the cache dir.
 */
object PluginInstaller {

    private fun installedFile(cacheDir: String) =
        File(cacheDir, "installed_plugins.json")

    /** Load persisted installed plugin list from disk. */
    fun loadInstalledPlugins(cacheDir: String): List<InstalledPlugin> {
        val f = installedFile(cacheDir)
        val loaded = if (f.exists()) {
            try {
                installedJson.decodeFromString<List<InstalledPlugin>>(f.readText())
            } catch (e: Exception) {
                ServerState.error("Failed to read installed_plugins.json: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        // Recover any .cs3 files physically present in plugins/ that might be missing from JSON
        val dir = File(cacheDir, "plugins")
        if (!dir.exists()) return loaded

        val loadedMap = loaded.associateBy { it.internalName }.toMutableMap()
        dir.listFiles()?.filter { it.isFile && it.extension.equals("cs3", ignoreCase = true) }?.forEach { cs3File ->
            val manifest = readManifestFromZip(cs3File)
            val internalName = cs3File.nameWithoutExtension
            val alreadyPresent = loadedMap.containsKey(internalName) ||
                    loadedMap.values.any { it.internalName.equals(internalName, ignoreCase = true) }
            if (!alreadyPresent) {
                val displayName = manifest?.name ?: internalName
                val version = manifest?.version ?: 1
                loadedMap[internalName] = InstalledPlugin(
                    internalName = internalName,
                    displayName = displayName,
                    version = version,
                    fileHash = null,
                    repoUrl = "",
                    localPath = cs3File.absolutePath,
                    iconUrl = manifest?.iconUrl,
                    tvTypes = manifest?.tvTypes ?: emptyList(),
                    language = manifest?.language,
                    authors = manifest?.authors ?: emptyList(),
                    description = manifest?.description,
                )
            }
        }

        val finalList = loadedMap.values.toList()
        if (finalList.size != loaded.size) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(installedJson.encodeToString(finalList))
            } catch (_: Exception) {}
        }
        return finalList
    }

    private fun readManifestFromZip(file: File): com.cncverse.stremiobridge.model.PluginManifest? {
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                java.io.InputStreamReader(zip.getInputStream(entry)).use { reader ->
                    installedJson.decodeFromString<com.cncverse.stremiobridge.model.PluginManifest>(reader.readText())
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Persist the current installed plugin list to disk. */
    private fun saveInstalledPlugins(cacheDir: String) {
        try {
            val f = installedFile(cacheDir)
            f.parentFile?.mkdirs()
            f.writeText(
                installedJson.encodeToString(RepoState.installedPlugins.value)
            )
        } catch (e: Exception) {
            ServerState.error("Failed to save installed plugins: ${e.message}")
        }
    }

    /** Install a plugin: download its .cs3 file and mark it as installed. */
    suspend fun installPlugin(ap: AvailablePlugin, cacheDir: String): Boolean {
        val plugin = ap.plugin
        RepoState.setInstallState(plugin.internalName, PluginInstallState.Installing("Downloading…"))
        ServerState.info("Installing '${plugin.name}'…")

        val file = PluginRepository.downloadPlugin(plugin, cacheDir)
        return if (file != null) {
            val installed = InstalledPlugin(
                internalName = plugin.internalName,
                displayName  = plugin.name,
                version      = plugin.version,
                fileHash     = plugin.fileHash,
                repoUrl      = ap.repoEntry.url,
                localPath    = file.absolutePath,
                iconUrl      = plugin.iconUrl,
                tvTypes      = plugin.tvTypes ?: emptyList(),
                language     = plugin.language,
                authors      = plugin.authors,
                description  = plugin.description,
            )
            RepoState.markInstalled(installed)
            saveInstalledPlugins(cacheDir)
            ServerState.info("Installed '${plugin.name}' v${plugin.version}")
            true
        } else {
            RepoState.setInstallState(plugin.internalName, PluginInstallState.Failed("Download failed"))
            false
        }
    }

    /** Uninstall a plugin: remove the .cs3 file from disk and clear install record. */
    suspend fun uninstallPlugin(internalName: String, cacheDir: String) = withContext(Dispatchers.IO) {
        val sanitized = internalName.replace(Regex("[^A-Za-z0-9._-]"), "_").lowercase()
        val dir = File(cacheDir, "plugins")
        if (dir.exists()) {
            dir.listFiles { f -> f.nameWithoutExtension.lowercase() == sanitized || f.name.lowercase().contains(sanitized) }
                ?.forEach { runCatching { it.delete() } }
        }
        val inst = RepoState.installedPlugins.value.find { it.internalName == internalName }
        if (inst != null) {
            runCatching { File(inst.localPath).delete() }
        }
        val jarsDir = File(cacheDir, "jars")
        if (jarsDir.exists()) {
            jarsDir.listFiles { f -> f.name.lowercase().contains(sanitized) }?.forEach { runCatching { it.delete() } }
        }
        RepoState.markUninstalled(internalName)
        saveInstalledPlugins(cacheDir)
        ServerState.info("Uninstalled '$internalName'")
    }

    /**
     * Auto-updates any installed plugin that has UpdateAvailable state.
     * Downloads the new version and replaces the cached file.
     */
    suspend fun autoUpdateInstalled(cacheDir: String) {
        val available = RepoState.availablePlugins.value
        val installed = RepoState.installedPlugins.value
        val toUpdate = installed.filter { inst ->
            RepoState.getInstallState(inst.internalName) is PluginInstallState.UpdateAvailable
        }

        toUpdate.forEach { inst ->
            val ap = available.find { it.plugin.internalName == inst.internalName && it.repoEntry.url == inst.repoUrl }
                ?: available.find { it.plugin.internalName == inst.internalName }
                ?: return@forEach
            ServerState.info("Auto-updating '${inst.displayName}' to v${ap.plugin.version}…")
            installPlugin(ap, cacheDir)
        }
    }

    /** Returns locally available .cs3 files for all installed plugins. */
    fun getInstalledFiles(cacheDir: String): Map<String, File> {
        val dir = File(cacheDir, "plugins")
        if (!dir.exists()) return emptyMap()

        val installed = RepoState.installedPlugins.value
        val result = mutableMapOf<String, File>()

        installed.forEach { inst ->
            val sanitized = inst.internalName.replace(Regex("[^A-Za-z0-9._-]"), "_").lowercase()
            val directFile = File(inst.localPath)
            val bySanitized = File(dir, "${sanitized}.cs3")
            val byName = File(dir, "${inst.internalName}.cs3")
            val fileInDir = dir.listFiles()?.firstOrNull { f ->
                f.isFile && f.extension.equals("cs3", ignoreCase = true) &&
                (f.nameWithoutExtension.equals(sanitized, ignoreCase = true) ||
                 f.nameWithoutExtension.equals(inst.internalName, ignoreCase = true))
            }
            val target = when {
                directFile.exists() -> directFile
                bySanitized.exists() -> bySanitized
                byName.exists() -> byName
                fileInDir != null -> fileInDir
                else -> null
            }
            if (target != null) {
                result[inst.internalName] = target
            }
        }

        // Also add any other .cs3 files in dir
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.extension.equals("cs3", ignoreCase = true)) {
                val baseName = f.nameWithoutExtension
                if (!result.containsKey(baseName)) {
                    result[baseName] = f
                }
            }
        }

        return result
    }
}
