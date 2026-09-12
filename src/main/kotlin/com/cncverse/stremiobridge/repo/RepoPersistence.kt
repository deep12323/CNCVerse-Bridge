package com.cncverse.stremiobridge.repo

import java.io.File

const val DEFAULT_REPO_URL =
    "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"

private val repoUrlsFile: File
    get() {
        val dir = File(System.getProperty("user.home"), ".cncverse")
        dir.mkdirs()
        return File(dir, "repos.txt")
    }

fun loadRepoUrls(): List<String> {
    val f = repoUrlsFile
    if (!f.exists()) return emptyList()
    return f.readLines().filter { it.isNotBlank() }
}

fun saveRepoUrls(urls: List<String>) {
    repoUrlsFile.writeText(urls.joinToString("\n"))
}

private val extSettingsFile: File
    get() {
        val dir = File(System.getProperty("user.home"), ".cncverse")
        dir.mkdirs()
        return File(dir, "ext_settings.txt")
    }

fun loadExtensionSettings(): Map<String, String> {
    val f = extSettingsFile
    if (!f.exists()) return emptyMap()
    val map = mutableMapOf<String, String>()
    f.readLines().forEach { line ->
        val idx = line.indexOf('=')
        if (idx > 0) {
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1)
            map[key] = value
        }
    }
    return map
}

fun saveExtensionSettings(settings: Map<String, String>) {
    extSettingsFile.writeText(settings.map { "${it.key}=${it.value}" }.joinToString("\n"))
}
