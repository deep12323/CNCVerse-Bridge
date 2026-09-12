package com.cncverse.stremiobridge.repo

import com.cncverse.stremiobridge.model.CncRepository
import com.cncverse.stremiobridge.model.SitePlugin
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

private val repoJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * Low-level HTTP helpers for fetching repo metadata and downloading plugin files.
 */
object PluginRepository {

    internal var httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(repoJson) }
        engine { requestTimeout = 30_000 }
        defaultRequest {
            header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,text/plain,*/*;q=0.8")
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            header("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            header("Sec-Ch-Ua-Mobile", "?0")
            header("Sec-Ch-Ua-Platform", "\"Windows\"")
            header("Sec-Fetch-Dest", "empty")
            header("Sec-Fetch-Mode", "cors")
            header("Sec-Fetch-Site", "cross-site")
        }
    }

    /**
     * Converts a `raw.githubusercontent.com` URL to its jsDelivr CDN mirror.
     * Returns null if the URL is not a GitHub raw URL.
     *
     * `https://raw.githubusercontent.com/USER/REPO/refs/heads/BRANCH/FILE`
     * `https://raw.githubusercontent.com/USER/REPO/BRANCH/FILE`
     * → `https://cdn.jsdelivr.net/gh/USER/REPO@BRANCH/FILE`
     */
    private fun githubRawToJsDelivr(url: String): String? {
        // Match both /refs/heads/BRANCH/PATH and /BRANCH/PATH formats
        val regex = Regex("""https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)""")
        val match = regex.matchEntire(url) ?: return null
        val (user, repo, branch, path) = match.destructured
        return "https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path"
    }

    private fun HttpRequestBuilder.applyGitHubBrowserHeaders(url: String) {
        if (url.contains("githubusercontent.com") || url.contains("github.com")) {
            header(HttpHeaders.Referrer, "https://github.com/")
            header(HttpHeaders.Origin, "https://github.com")
            header(HttpHeaders.CacheControl, "no-cache")
            header(HttpHeaders.Pragma, "no-cache")
        }
    }

    /**
     * Resolves a CloudStream shortcode (e.g., "Hexated") to a full repository URL.
     * If the input is already a URL or invalid, it returns the input unchanged.
     */
    suspend fun resolveShortCode(url: String): String = withContext(Dispatchers.IO) {
        var trimmed = url.trim()
        if (trimmed.startsWith("cloudstreamrepo://")) {
            trimmed = "https://" + trimmed.removePrefix("cloudstreamrepo://")
        } else if (trimmed.startsWith("cloudstream://")) {
            trimmed = "https://" + trimmed.removePrefix("cloudstream://")
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return@withContext trimmed
        if (!trimmed.matches("^[a-zA-Z0-9!_-]+$".toRegex())) return@withContext trimmed

        val isPyMd = trimmed.startsWith("!")
        val baseUrl = if (isPyMd) "https://py.md/${trimmed.removePrefix("!")}" else "https://cutt.ly/$trimmed"
        
        try {
            // We use a temporary client that does not follow redirects to read the Location header.
            val tempClient = HttpClient(CIO) {
                followRedirects = false
                defaultRequest {
                    header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
                    header(HttpHeaders.Accept, "*/*")
                    header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                }
            }
            val response = tempClient.get(baseUrl)
            val location = response.headers["Location"]
            tempClient.close()
            
            if (location != null) {
                if (location.startsWith("https://cutt.ly/404") || location.removeSuffix("/") == "https://cutt.ly" ||
                    location.startsWith("https://py.md/404") || location.removeSuffix("/") == "https://py.md"
                ) {
                    return@withContext trimmed
                }
                return@withContext location
            }
            trimmed
        } catch (e: Exception) {
            ServerState.warn("Failed to resolve shortcode '$trimmed': ${e.message}")
            trimmed
        }
    }

    /**
     * Fetches the top-level [CncRepository] manifest from [url].
     * Falls back to jsDelivr CDN mirror for GitHub raw URLs on failure.
     * Returns null on failure.
     */
    suspend fun fetchRepoMeta(url: String): CncRepository? = withContext(Dispatchers.IO) {
        val urls = buildList {
            add(url)
            githubRawToJsDelivr(url)?.let { add(it) }
        }
        for (candidate in urls) {
            try {
                val text = httpClient.get(candidate) {
                    applyGitHubBrowserHeaders(candidate)
                }.bodyAsText()
                val result = repoJson.decodeFromString<CncRepository>(text)
                if (candidate != url) ServerState.info("Fetched repo meta via CDN mirror")
                return@withContext result
            } catch (e: Exception) {
                if (candidate == urls.last()) {
                    ServerState.warn("Failed to fetch repo meta from $url: ${e.message}")
                }
            }
        }
        null
    }

    /**
     * Fetches all [SitePlugin] entries from a single plugin-list URL.
     * Falls back to jsDelivr CDN mirror for GitHub raw URLs on failure.
     */
    suspend fun fetchPluginsFromUrl(listUrl: String): List<SitePlugin> = withContext(Dispatchers.IO) {
        val urls = buildList {
            add(listUrl)
            githubRawToJsDelivr(listUrl)?.let { add(it) }
        }
        for (candidate in urls) {
            try {
                val text = httpClient.get(candidate) {
                    applyGitHubBrowserHeaders(candidate)
                }.bodyAsText()
                val result = repoJson.decodeFromString<List<SitePlugin>>(text)
                if (candidate != listUrl) ServerState.info("Fetched plugin list via CDN mirror")
                return@withContext result
            } catch (e: Exception) {
                if (candidate == urls.last()) {
                    ServerState.warn("Failed to fetch plugin list from $listUrl: ${e.message}")
                }
            }
        }
        emptyList()
    }

    /**
     * Downloads a single plugin's .cs3 file to the cache directory.
     * Returns the local [File] on success, null on failure.
     * Skips download if file already exists and hash matches.
     */
    suspend fun downloadPlugin(plugin: SitePlugin, cacheDir: String): File? =
        withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "plugins").also { it.mkdirs() }
            val fileName = "${plugin.internalName.sanitize()}.cs3"
            val destFile = File(dir, fileName)

            // Skip download if cached with matching hash
            if (destFile.exists() && !plugin.fileHash.isNullOrBlank()) {
                val existingHash = sha256(destFile)
                if (existingHash.equals(plugin.fileHash.trim(), ignoreCase = true)) {
                    return@withContext destFile
                }
            }

            // Resolve relative or space-containing URLs
            val resolvedUrl = when {
                plugin.url.startsWith("http://", ignoreCase = true) || plugin.url.startsWith("https://", ignoreCase = true) -> plugin.url
                !plugin.repositoryUrl.isNullOrBlank() && (plugin.repositoryUrl.startsWith("http://", ignoreCase = true) || plugin.repositoryUrl.startsWith("https://", ignoreCase = true)) -> {
                    val base = plugin.repositoryUrl.removeSuffix("/")
                    val path = plugin.url.removePrefix("/")
                    "$base/$path"
                }
                else -> plugin.url
            }

            val safeUrl = resolvedUrl.replace(" ", "%20")
            val urls = buildList {
                add(safeUrl)
                githubRawToJsDelivr(safeUrl)?.let { add(it) }
            }

            for (candidate in urls) {
                try {
                    ServerState.info("Downloading '${plugin.name}' from $candidate")
                    val response = httpClient.get(candidate) {
                        applyGitHubBrowserHeaders(candidate)
                    }

                    if (response.status.value !in 200..299) {
                        ServerState.warn("HTTP ${response.status.value} downloading '${plugin.name}' from $candidate")
                        if (candidate != urls.last()) continue
                        return@withContext null
                    }

                    val bytes = response.readRawBytes()
                    if (bytes.isEmpty()) {
                        ServerState.warn("Empty response for '${plugin.name}' from $candidate")
                        if (candidate != urls.last()) continue
                        return@withContext null
                    }

                    if (!plugin.fileHash.isNullOrBlank()) {
                        val downloadedHash = sha256Bytes(bytes)
                        if (!downloadedHash.equals(plugin.fileHash.trim(), ignoreCase = true)) {
                            ServerState.warn("Hash mismatch for '${plugin.name}'! Expected: ${plugin.fileHash}, got: $downloadedHash. Verifying archive integrity…")
                            // Verify if it is a valid zip containing manifest/dex before discarding
                            val isValidZip = runCatching {
                                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                                    var entry = zis.nextEntry
                                    var valid = false
                                    while (entry != null) {
                                        if (entry.name == "manifest.json" || entry.name.endsWith(".dex") || entry.name.endsWith(".class")) {
                                            valid = true
                                            break
                                        }
                                        entry = zis.nextEntry
                                    }
                                    valid
                                }
                            }.getOrDefault(false)

                            if (!isValidZip) {
                                ServerState.error("Corrupted or invalid CS3 archive for '${plugin.name}'")
                                if (candidate != urls.last()) continue
                                return@withContext null
                            }
                        }
                    }

                    destFile.writeBytes(bytes)
                    ServerState.info("Downloaded '${plugin.name}' (${bytes.size / 1024} KB)")
                    return@withContext destFile
                } catch (e: Exception) {
                    if (candidate == urls.last()) {
                        ServerState.error("Failed to download '${plugin.name}': ${e.message}")
                    }
                }
            }
            null
        }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read = stream.read(buf)
            while (read != -1) {
                digest.update(buf, 0, read)
                read = stream.read(buf)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.sanitize(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").lowercase()
}
