package com.cncverse.stremiobridge.repo

import java.io.File

const val DEFAULT_REPO_URL =
    "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"

fun getStorageDir(folderName: String): File {
    val custom = System.getenv("DATA_DIR")
    if (!custom.isNullOrBlank()) {
        return File(custom, folderName).apply { mkdirs() }
    }
    val hfData = File("/data")
    if (hfData.exists() && hfData.isDirectory && hfData.canWrite()) {
        return File(hfData, folderName).apply { mkdirs() }
    }
    return File(System.getProperty("user.home"), folderName).apply { mkdirs() }
}

val cncverseDir: File get() = getStorageDir(".cncverse")
val bridgeCacheDir: File get() = getStorageDir(".cncverse_bridge")

internal val repoUrlsFile: File
    get() = File(cncverseDir, "repos.txt")

fun loadRepoUrls(): List<String> {
    val f = repoUrlsFile
    if (!f.exists()) return emptyList()
    return f.readLines()
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .distinct()
}

fun saveRepoUrls(urls: List<String>) {
    val f = repoUrlsFile
    f.parentFile?.mkdirs()
    f.writeText(urls.distinct().joinToString("\n"))
}

internal val extSettingsFile: File
    get() = File(cncverseDir, "ext_settings.txt")

fun loadExtensionSettings(): Map<String, String> {
    val f = extSettingsFile
    if (!f.exists()) return emptyMap()
    val map = mutableMapOf<String, String>()
    f.readLines().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isNotBlank() && !line.startsWith("#")) {
            val idx = line.indexOf('=')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
    }
    return map
}

fun saveExtensionSettings(settings: Map<String, String>) {
    val f = extSettingsFile
    f.parentFile?.mkdirs()
    f.writeText(settings.map { "${it.key.trim()}=${it.value.trim()}" }.joinToString("\n"))
    com.lagradost.cloudstream3.CloudStreamApp.syncSettings(settings)
}

