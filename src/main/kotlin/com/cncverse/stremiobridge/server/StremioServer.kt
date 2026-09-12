package com.cncverse.stremiobridge.server

import com.cncverse.stremiobridge.model.*
import com.cncverse.stremiobridge.plugin.GlobalPluginManager
import com.cncverse.stremiobridge.repo.DEFAULT_REPO_URL
import com.cncverse.stremiobridge.repo.PluginInstaller
import com.cncverse.stremiobridge.repo.RepoManager
import com.cncverse.stremiobridge.repo.loadExtensionSettings
import com.cncverse.stremiobridge.repo.saveExtensionSettings
import com.cncverse.stremiobridge.state.PluginInstallState
import com.cncverse.stremiobridge.state.RepoState
import com.cncverse.stremiobridge.state.ServerState
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket

private val serverJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private val httpClient by lazy {
    HttpClient(io.ktor.client.engine.cio.CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(serverJson)
        }
        try {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
        } catch (e: Throwable) {}
    }
}

/**
 * Manages the Ktor-based embedded HTTP server exposing the Stremio addon protocol.
 *
 * Routes:
 *  GET /manifest.json
 *  GET /catalog/{type}/{id}.json[?search=query]
 *  GET /meta/{type}/{id}.json
 *  GET /stream/{type}/{id}.json
 *  GET /                         (status HTML page)
 */
object StremioServer {

    private var engine: EmbeddedServer<*, *>? = null
    private var activePort: Int = 8080
    val isRunning: Boolean get() = engine != null

    /**
     * Holds live references to loaded [MainApiWrapper] instances.
     * Populated by the platform-specific [PluginLoader] after loading.
     */
    val loadedApis: MutableList<MainApiWrapper> = mutableListOf()

    val disabledPlugins: MutableSet<String> = mutableSetOf()
    private var disabledPluginsFile: File? = null
    var currentCacheDir: String? = null

    private fun loadDisabledPlugins() {
        val file = disabledPluginsFile ?: return
        if (file.exists()) {
            try {
                val json = file.readText()
                disabledPlugins.clear()
                disabledPlugins.addAll(serverJson.decodeFromString<Set<String>>(json))
            } catch (e: Exception) {
                ServerState.warn("Failed to load disabled plugins: ${e.message}")
            }
        }
    }

    private fun saveDisabledPlugins() {
        val file = disabledPluginsFile ?: return
        try {
            val json = serverJson.encodeToString(disabledPlugins)
            file.writeText(json)
        } catch (e: Exception) {
            ServerState.warn("Failed to save disabled plugins: ${e.message}")
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
                true
            }
        } catch (e: Throwable) {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(port))
                    true
                }
            } catch (e2: Throwable) {
                false
            }
        }
    }

    private suspend fun findAvailablePort(defaultPort: Int): Int {
        // Retry default port a few times in case an old server instance is actively shutting down
        for (attempt in 1..3) {
            if (isPortAvailable(defaultPort)) return defaultPort
            if (attempt < 3) delay(300)
        }

        // If default port is occupied by another process, scan fallback ports
        for (port in (defaultPort + 1)..(defaultPort + 50)) {
            if (isPortAvailable(port)) {
                ServerState.info("Port $defaultPort is in use, falling back to port $port")
                return port
            }
        }

        // As a last resort, ask OS for an ephemeral port
        try {
            ServerSocket(0).use { socket ->
                val randomPort = socket.localPort
                ServerState.info("Default ports in use, using dynamic port $randomPort")
                return randomPort
            }
        } catch (e: Throwable) {
            // ignore
        }

        throw BindException("No available ports found between $defaultPort and ${defaultPort + 50}")
    }

    suspend fun start(port: Int = 8080, cacheDir: String? = null): Int {
        if (engine != null) return activePort
        currentCacheDir = cacheDir
        if (cacheDir != null) {
            disabledPluginsFile = File(cacheDir, "disabled_plugins.json")
            loadDisabledPlugins()
        }
        val targetPort = findAvailablePort(port)
        activePort = targetPort
        engine = embeddedServer(CIO, port = targetPort, host = "0.0.0.0") {
            setupPlugins()
            setupRoutes()
        }.start(wait = false)
        ServerState.serverPort = targetPort
        ServerState.info("Stremio server started on port $targetPort")

        if (ServerState.isStremioMode.value) {
            com.cncverse.stremiobridge.tunnel.CloudflaredManager.startTunnel(targetPort)
        }

        return targetPort
    }

    fun stop() {
        com.cncverse.stremiobridge.tunnel.CloudflaredManager.stopTunnel()
        engine?.stop(0, 500)
        engine = null
        ServerState.info("Stremio server stopped")
    }

    private fun Application.setupPlugins() {
        install(ContentNegotiation) { json(serverJson) }
        
        // Stremio Web (and sometimes Desktop) can be very strict or send 'Origin: null'. 
        // Manually appending these headers ensures maximum compatibility across all Stremio clients.
        intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
            call.response.header("Access-Control-Allow-Origin", "*")
            call.response.header("Access-Control-Allow-Headers", "*")
            
            if (call.request.httpMethod == HttpMethod.Options) {
                call.respond(HttpStatusCode.OK)
                return@intercept // End pipeline for OPTIONS
            }
        }
    }

    private fun Application.setupRoutes() {
        setupMpdProxyRoutes()
        routing {
            // 📺 Status Page 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺
            get("/") {
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                call.response.headers.append(HttpHeaders.Pragma, "no-cache")
                call.response.headers.append(HttpHeaders.Expires, "0")
                call.respondText(buildStatusHtml(), ContentType.Text.Html)
            }

            get("/api/toggle-plugin") {
                val id = call.request.queryParameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                if (disabledPlugins.contains(id)) {
                    disabledPlugins.remove(id)
                } else {
                    disabledPlugins.add(id)
                }
                saveDisabledPlugins()
                call.respondRedirect("/?v=${System.currentTimeMillis()}#extensions")
            }

            get("/api/install-plugin") {
                val internalName = call.request.queryParameters["internalName"]?.trim() ?: return@get call.respond(HttpStatusCode.BadRequest)
                val cDir = currentCacheDir ?: File(System.getProperty("user.home"), ".cncverse_bridge").absolutePath
                var ap = RepoState.availablePlugins.value.find { it.plugin.internalName.equals(internalName, ignoreCase = true) }
                if (ap == null) {
                    withContext(Dispatchers.IO) { RepoManager.refreshAllRepos() }
                    ap = RepoState.availablePlugins.value.find { it.plugin.internalName.equals(internalName, ignoreCase = true) }
                }
                if (ap != null) {
                    withContext(Dispatchers.IO) {
                        val success = PluginInstaller.installPlugin(ap, cDir)
                        if (success) {
                            val installed = PluginInstaller.loadInstalledPlugins(cDir)
                            RepoState.setInstalledPlugins(installed)
                            installed.forEach { RepoState.setInstallState(it.internalName, PluginInstallState.Installed) }
                            val cs3Files = PluginInstaller.getInstalledFiles(cDir)
                            GlobalPluginManager.reloadAllPlugins(installed, cs3Files)
                            ServerState.info("Successfully installed and loaded '${ap.plugin.name}'")
                        } else {
                            ServerState.error("Failed to install '${ap.plugin.name}'")
                        }
                    }
                } else {
                    ServerState.warn("Could not find extension '$internalName' in available repositories")
                }
                call.respondRedirect("/?v=${System.currentTimeMillis()}#extensions")
            }

            get("/api/uninstall-plugin") {
                val internalName = call.request.queryParameters["internalName"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val cDir = currentCacheDir ?: File(System.getProperty("user.home"), ".cncverse_bridge").absolutePath
                withContext(Dispatchers.IO) {
                    PluginInstaller.uninstallPlugin(internalName, cDir)
                    val installed = PluginInstaller.loadInstalledPlugins(cDir)
                    RepoState.setInstalledPlugins(installed)
                    val cs3Files = PluginInstaller.getInstalledFiles(cDir)
                    GlobalPluginManager.reloadAllPlugins(installed, cs3Files)
                    ServerState.info("Uninstalled '$internalName'")
                }
                call.respondRedirect("/?v=${System.currentTimeMillis()}#extensions")
            }

            post("/api/add-repo") {
                val params = call.receiveParameters()
                val url = params["url"]?.trim()
                if (!url.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        val entry = RepoManager.addRepo(url)
                        if (entry != null) {
                            ServerState.info("Added repo: $url (${entry.name})")
                        }
                    }
                }
                call.respondRedirect("/?v=${System.currentTimeMillis()}#extensions")
            }

            get("/api/remove-repo") {
                val url = call.request.queryParameters["url"]?.trim()
                if (!url.isNullOrBlank()) {
                    RepoManager.removeRepo(url)
                    ServerState.info("Removed repo: $url")
                }
                call.respondRedirect("/?v=${System.currentTimeMillis()}#extensions")
            }

            get("/api/refresh-repos") {
                withContext(Dispatchers.IO) {
                    RepoManager.refreshAllRepos()
                    val cDir = currentCacheDir ?: File(System.getProperty("user.home"), ".cncverse_bridge").absolutePath
                    val toUpdate = RepoState.installedPlugins.value.filter {
                        RepoState.getInstallState(it.internalName) is PluginInstallState.UpdateAvailable
                    }
                    if (toUpdate.isNotEmpty()) {
                        ServerState.info("Auto-updating ${toUpdate.size} extension(s)…")
                        PluginInstaller.autoUpdateInstalled(cDir)
                        val updatedInstalled = PluginInstaller.loadInstalledPlugins(cDir)
                        val updatedCs3Files = PluginInstaller.getInstalledFiles(cDir)
                        GlobalPluginManager.reloadAllPlugins(updatedInstalled, updatedCs3Files)
                    }
                }
                call.respondRedirect("/?v=${System.currentTimeMillis()}#extensions")
            }

            get("/api/settings") {
                val settings = loadExtensionSettings()
                call.respond(settings)
            }

            post("/api/settings") {
                val params = call.receiveParameters()
                val current = loadExtensionSettings().toMutableMap()
                params.entries().forEach { (k, v) ->
                    val valStr = v.firstOrNull()?.trim()
                    if (valStr.isNullOrEmpty()) {
                        current.remove(k)
                    } else {
                        current[k] = valStr
                    }
                }
                saveExtensionSettings(current)
                ServerState.info("Extension settings updated via Web Dashboard")
                call.respondRedirect("/?saved=1#settings")
            }

            // 📺 Manifest 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺─────────────────────────────────────────────────────
            get("/manifest.json") {
                call.response.header(io.ktor.http.HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                call.respond(buildManifest())
            }

            // 📺 Catalog 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺
            get("/catalog/{path...}") {
                val pathSegments = call.parameters.getAll("path") ?: emptyList()
                if (pathSegments.size < 2) return@get call.respond(HttpStatusCode.BadRequest)
                
                val type = pathSegments[0]
                
                if (pathSegments.size == 2) {
                    val idWithExt = pathSegments[1]
                    if (!idWithExt.endsWith(".json")) return@get call.respond(HttpStatusCode.NotFound)
                    
                    val id = idWithExt.removeSuffix(".json")
                    val search = call.request.queryParameters["search"]
                    val skip = call.request.queryParameters["skip"]?.toIntOrNull() ?: 0

                    val metas = withContext(Dispatchers.IO) { buildCatalog(type, id, search, skip, null) }
                    call.respond(StremioCatalogResponse(metas))
                } else if (pathSegments.size == 3) {
                    val id = pathSegments[1]
                    val extraWithExt = pathSegments[2]
                    if (!extraWithExt.endsWith(".json")) return@get call.respond(HttpStatusCode.NotFound)
                    
                    val extraStr = extraWithExt.removeSuffix(".json")
                    val parsedExtra = io.ktor.http.parseQueryString(extraStr)
                    
                    val search = parsedExtra["search"] ?: call.request.queryParameters["search"]
                    val skip = (parsedExtra["skip"] ?: call.request.queryParameters["skip"])?.toIntOrNull() ?: 0
                    val genre = parsedExtra["genre"]

                    val metas = withContext(Dispatchers.IO) { buildCatalog(type, id, search, skip, genre) }
                    call.respond(StremioCatalogResponse(metas))
                } else {
                    call.respond(HttpStatusCode.BadRequest)
                }
            }

            // ── Meta ─────────────────────────────────────────────────────────
            get("/meta/{type}/{id}.json") {
                val type = call.parameters["type"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id   = call.parameters["id"]   ?: return@get call.respond(HttpStatusCode.BadRequest)

                val meta = withContext(Dispatchers.IO) { buildMeta(type, id) }
                if (meta != null) {
                    call.respond(StremioMetaResponse(meta))
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            // ── Stream ───────────────────────────────────────────────────────
            get("/stream/{type}/{id}.json") {
                val type = call.parameters["type"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id   = call.parameters["id"]   ?: return@get call.respond(HttpStatusCode.BadRequest)

                val streams = withContext(Dispatchers.IO) { buildStreams(type, id) }
                call.respond(StremioStreamResponse(streams))
            }

            // ── Subtitles ────────────────────────────────────────────────────
            get("/subtitles/{type}/{id}.json") {
                val type = call.parameters["type"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val id   = call.parameters["id"]   ?: return@get call.respond(HttpStatusCode.BadRequest)

                val streams = withContext(Dispatchers.IO) { buildStreams(type, id) }
                val subtitles = streams.flatMap { it.subtitles ?: emptyList() }.distinctBy { it.id }
                
                call.respond(StremioSubtitleResponse(subtitles))
            }
        }
    }

    // 📺 Manifest builder 📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺📺

    private suspend fun buildManifest(): StremioManifest {
        val activeApis = loadedApis.filter { !disabledPlugins.contains(it.internalName) }
        val types = listOf("movie", "series", "tv", "other")

        val catalogs = activeApis.flatMap { api ->
            api.supportedTypes
                .map { cs3TvTypeToStremio(it) }
                .distinct()
                .flatMap { stremioType ->
                    val extra = mutableListOf<ExtraEntry>()
                    val sections = api.getMainPageSections()
                    if (sections.isNotEmpty() && (sections.size > 1 || sections.first().isNotBlank())) {
                        extra.add(ExtraEntry(name = "genre", options = sections))
                    }
                    extra.add(ExtraEntry("search"))
                    extra.add(ExtraEntry("skip"))

                    val typeLabel = when (stremioType) {
                        "movie" -> "Movies"
                        "series" -> "Series"
                        "tv" -> "Live TV"
                        else -> stremioType.replaceFirstChar { it.uppercase() }
                    }
                    val catName = if (api.supportedTypes.size > 1) "${api.name} ($typeLabel)" else api.name

                    listOf(
                        StremioCatalogDef(
                            type = stremioType,
                            id   = "cnc_${api.internalName}_$stremioType",
                            name = catName,
                            extra = extra
                        )
                    )
                }
        }.distinctBy { it.id }
            .ifEmpty {
                listOf(StremioCatalogDef("movie", "cnc_all_movie", "CNCVerse (Movie)"))
            }

        val manifestVersion = "1.${activeApis.size}.${kotlin.math.abs(activeApis.sumOf { it.internalName.hashCode() }) % 1000}"

        return StremioManifest(
            id          = "com.cncverse.stremiobridge",
            version     = manifestVersion,
            name        = "CNCVerse Bridge",
            description = "CS3 plugin bridge for Stremio — powered by CNCVerse extensions (${activeApis.size} active)",
            logo        = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/cnc.png",
            types       = types,
            resources   = listOf("catalog", "meta", "stream", "subtitles"),
            catalogs    = catalogs,
        )
    }

    // ── Catalog builder ───────────────────────────────────────────────────────

    private suspend fun buildCatalog(
        type: String, id: String, search: String?, skip: Int, genre: String?
    ): List<StremioMeta> {
        val prefix = "cnc_"
        if (!id.startsWith(prefix)) return emptyList()
        val rest = id.removePrefix(prefix)
        
        val api = loadedApis.sortedByDescending { it.internalName.length }.find { rest.startsWith(it.internalName) }
            ?: loadedApis.firstOrNull()
            ?: return emptyList()

        if (disabledPlugins.contains(api.internalName)) return emptyList()

        val sectionName = genre

        return try {
            if (!search.isNullOrBlank()) {
                val results = api.search(search)
                // If plugin supports multiple types, filter strictly; otherwise return all
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r ->
                        val rType = cs3TvTypeToStremio(r.type)
                        rType == type || (type == "movie" && rType == "other")
                    }.ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(api.internalName, type) }
            } else {
                val results = api.getMainPage(page = (skip / 20) + 1, type = type, sectionName = sectionName)
                // If plugin supports multiple types, filter strictly; otherwise return all
                val filtered = if (api.supportedTypes.size > 1) {
                    results.filter { r ->
                        val rType = cs3TvTypeToStremio(r.type)
                        rType == type || (type == "movie" && rType == "other")
                    }.ifEmpty { results }
                } else results
                filtered.map { it.toStremiMeta(api.internalName, type) }
            }
        } catch (e: Throwable) {
            ServerState.warn("Catalog error for ${api.name}: ${e.message}")
            emptyList()
        }
    }

    // ── Meta builder ──────────────────────────────────────────────────────────

    private suspend fun buildMeta(type: String, id: String): StremioMeta? {
        val (internalName, dataUrl) = StremioIds.decode(id) ?: return null
        val api = loadedApis.find { it.internalName == internalName } ?: return null
        if (disabledPlugins.contains(api.internalName)) return null
        return try {
            api.load(dataUrl)?.toStremiMeta(api.internalName, type)
        } catch (e: Throwable) {
            ServerState.warn("Meta error for $internalName: ${e.message}")
            null
        }
    }

    private fun sortStreamsByQuality(streams: List<StremioStream>): List<StremioStream> {
        fun extractScore(stream: StremioStream): Int {
            val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()}".lowercase()
            
            // Base resolution score
            val resScore = when {
                text.contains("4k") || text.contains("2160p") || text.contains("uhd") -> 216000
                text.contains("1440p") || text.contains("2k") || text.contains("qhd") -> 144000
                text.contains("1080p") || text.contains("fhd") || text.contains("full hd") -> 108000
                text.contains("720p") || text.contains("hd") -> 72000
                text.contains("576p") -> 57600
                text.contains("480p") || text.contains("sd") -> 48000
                text.contains("360p") -> 36000
                text.contains("240p") -> 24000
                else -> {
                    val match = Regex("""\b(2160|1440|1080|720|576|480|360|240)\b""").find(text)
                    if (match != null) {
                        (match.groupValues[1].toIntOrNull() ?: 500) * 100
                    } else {
                        50000
                    }
                }
            }

            // Quality feature bonuses
            var featureBonus = 0
            if (text.contains("remux") || text.contains("bluray") || text.contains("bdrip")) featureBonus += 500
            if (text.contains("web-dl") || text.contains("webrip")) featureBonus += 300
            if (text.contains("hdr") || text.contains("hdr10") || text.contains("dolby vision") || text.contains("dv")) featureBonus += 200
            if (text.contains("imax")) featureBonus += 100
            if (text.contains("10bit")) featureBonus += 50
            if (text.contains("gofile") || text.contains("driveleech") || text.contains("fast")) featureBonus += 20

            return resScore + featureBonus
        }

        return streams.sortedWith(compareByDescending<StremioStream> { extractScore(it) })
    }

    // ── Stream builder ────────────────────────────────────────────────────────

    private suspend fun buildStreams(type: String, id: String): List<StremioStream> {
        val decoded = StremioIds.decode(id)
        if (decoded != null) {
            val (internalName, dataUrl) = decoded
            val api = loadedApis.find { it.internalName == internalName } ?: return emptyList()
            if (disabledPlugins.contains(api.internalName)) return emptyList()
            return try {
                sortStreamsByQuality(api.loadLinks(dataUrl))
            } catch (e: Throwable) {
                ServerState.warn("Stream error for $internalName: ${e.message}")
                emptyList()
            }
        }

        // Handle generic Stremio requests with TMDB/IMDB IDs
        val baseId = id.substringBefore(":")
        val mediaType = if (type == "series") "tv" else "movie"
        val tmdbId = if (baseId.startsWith("tmdb:")) baseId.removePrefix("tmdb:") else baseId
        
        ServerState.info("Generic request: id=$id, type=$type, baseId=$baseId, tmdbId=$tmdbId")
        
        return try {
            val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
            val isImdbId = tmdbId.startsWith("tt")
            val tmdbUrl = if (isImdbId) {
                "https://api.themoviedb.org/3/find/$tmdbId?api_key=$TMDB_API_KEY&external_source=imdb_id"
            } else {
                "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$TMDB_API_KEY"
            }
            
            ServerState.info("Fetching TMDB: $tmdbUrl")
            val responseText = httpClient.get(tmdbUrl).bodyAsText()
            val jsonObject = serverJson.parseToJsonElement(responseText).jsonObject
            
            val mediaObj = if (isImdbId) {
                val movieResults = jsonObject["movie_results"] as? kotlinx.serialization.json.JsonArray
                val tvResults = jsonObject["tv_results"] as? kotlinx.serialization.json.JsonArray
                (movieResults?.firstOrNull() ?: tvResults?.firstOrNull())?.jsonObject
            } else {
                jsonObject
            }
            
            if (mediaObj == null) {
                ServerState.warn("TMDB resolve failed: no media found for $tmdbId")
                return emptyList()
            }

            val title = mediaObj["title"]?.jsonPrimitive?.content 
                ?: mediaObj["name"]?.jsonPrimitive?.content
                
            if (title == null) {
                ServerState.warn("TMDB resolve failed: no title found")
                return emptyList()
            }
                
            val year = mediaObj["release_date"]?.jsonPrimitive?.content?.substringBefore("-")?.toIntOrNull() 
                ?: mediaObj["first_air_date"]?.jsonPrimitive?.content?.substringBefore("-")?.toIntOrNull()

            ServerState.info("TMDB resolve success: title='$title', year=$year")

            val allStreams = mutableListOf<StremioStream>()
            val activePlugins = loadedApis.filter { !disabledPlugins.contains(it.internalName) }
            ServerState.info("Searching across ${activePlugins.size} plugins...")
            
            coroutineScope {
                activePlugins.map { api ->
                    async {
                        try {
                            ServerState.info("[${api.name}] Searching for '$title'")
                            val searchResults = api.search(title)
                            ServerState.info("[${api.name}] Found ${searchResults.size} results")
                            
                            val bestMatch = searchResults.find { it.name.equals(title, ignoreCase = true) && (year == null || it.year == null || it.year == year) } 
                                ?: searchResults.firstOrNull { it.name.contains(title, ignoreCase = true) }
                                ?: searchResults.firstOrNull()

                            if (bestMatch != null) {
                                ServerState.info("[${api.name}] Best match: '${bestMatch.name}' (url: ${bestMatch.url})")
                                val mediaInfo = api.load(bestMatch.url)
                                if (mediaInfo != null) {
                                    var dataUrlToLoad = mediaInfo.dataUrl
                                    if (type == "series" && id.contains(":")) {
                                        val parts = id.split(":")
                                        val season = parts.getOrNull(1)?.toIntOrNull()
                                        val episode = parts.getOrNull(2)?.toIntOrNull()
                                        if (season != null && episode != null) {
                                            val ep = mediaInfo.episodes?.find { it.season == season && it.episode == episode }
                                            if (ep != null) {
                                                dataUrlToLoad = ep.dataUrl
                                                ServerState.info("[${api.name}] Found episode S${season}E${episode}")
                                            } else {
                                                ServerState.warn("[${api.name}] Episode S${season}E${episode} not found in mediaInfo")
                                                return@async emptyList<StremioStream>()
                                            }
                                        }
                                    }
                                    ServerState.info("[${api.name}] Loading links for $dataUrlToLoad")
                                    val links = api.loadLinks(dataUrlToLoad)
                                    ServerState.info("[${api.name}] Found ${links.size} streams")
                                    links.map { stream ->
                                        val newName = "${bestMatch.name}" + (if (!stream.name.isNullOrBlank()) "\n${stream.name}" else "")
                                        stream.copy(name = newName)
                                    }
                                } else {
                                    ServerState.warn("[${api.name}] MediaInfo load failed for ${bestMatch.url}")
                                    emptyList()
                                }
                            } else {
                                ServerState.info("[${api.name}] No matching search result")
                                emptyList()
                            }
                        } catch (e: Exception) {
                            ServerState.warn("Search/load error in ${api.name}: ${e.message}")
                            emptyList<StremioStream>()
                        }
                    }
                }.awaitAll().forEach { allStreams.addAll(it) }
            }
            val sortedStreams = sortStreamsByQuality(allStreams)
            ServerState.info("Returning total ${sortedStreams.size} sorted streams")
            sortedStreams
        } catch (e: Exception) {
            ServerState.warn("TMDB resolve error for $id: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    // ── HTML status page ──────────────────────────────────────────────────────

    private fun buildStatusHtml(): String {
        val settings = loadExtensionSettings()
        val febboxToken = settings["token"] ?: ""
        val showboxToken = settings["showbox_ui_token"] ?: ""
        val wyzieKey = settings["wyzie_subs_api_key"] ?: ""
        val movieboxHost = settings["moviebox_host"] ?: "https://api3.aoneroom.com"
        val concurrency = settings["ScrapeConcurrency"] ?: "10"
        val tmdbEnabled = settings["ProviderTmdb"] != "false"
        val cineStreamEnabled = settings["ProviderCineStream"] != "false"
        val simklEnabled = settings["ProviderSimkl"] != "false"

        val available = RepoState.availablePlugins.value
        val installed = RepoState.installedPlugins.value
        val repos = RepoState.repos.value

        val repoChipsHtml = repos.joinToString("") { r ->
            val displayName = if (r.name.isNotBlank()) r.name else r.url.removePrefix("https://").removePrefix("http://").take(28)
            val isDefault = r.url == DEFAULT_REPO_URL
            val delBtn = if (!isDefault)
                """<a href="/api/remove-repo?url=${r.url}" class="repo-del" title="Remove Repository" onclick="return confirm('Remove repository ${r.name}?')">✕</a>"""
            else ""
            """<div class="repo-pill"><span class="repo-dot"></span><span class="repo-name">$displayName</span>$delBtn</div>"""
        }

        val extCards = if (available.isNotEmpty()) {
            available.sortedBy { it.plugin.name }.joinToString("\n") { ap ->
                val p = ap.plugin
                val isInst = installed.any { it.internalName == p.internalName }
                val isLoaded = loadedApis.any { it.internalName == p.internalName || it.name == p.name }
                val isEnabled = isLoaded && !disabledPlugins.contains(p.internalName)

                val tvBadges = (p.tvTypes ?: emptyList()).take(3).joinToString(" ") { type ->
                    """<span class="tag tag-type">${type.uppercase()}</span>"""
                }
                val langBadge = if (!p.language.isNullOrBlank() && p.language != "all")
                    """<span class="tag tag-lang">${p.language.uppercase()}</span>"""
                else ""

                val statusBadge = if (p.status == 3)
                    """<span class="tag tag-beta">● Beta</span>"""
                else if (p.status == 1)
                    """<span class="tag tag-ok">● OK</span>"""
                else ""

                val actionHtml = if (isInst) {
                    val toggleBtn = if (isEnabled)
                        """<a class="btn-sm btn-outline-warning" href="/api/toggle-plugin?id=${p.internalName}" onclick="performAction(this, 'Updating…')">Disable</a>"""
                    else
                        """<a class="btn-sm btn-outline-success" href="/api/toggle-plugin?id=${p.internalName}" onclick="performAction(this, 'Updating…')">Enable</a>"""

                    """
                    <div class="ext-actions-group">
                      <span class="badge badge-success">✓ Installed</span>
                      $toggleBtn
                      <a class="btn-sm btn-outline-danger" href="/api/uninstall-plugin?internalName=${p.internalName}" onclick="if(confirm('Uninstall ${p.name}?')){performAction(this, '⏳ Deleting…'); return true;} else return false;">Uninstall</a>
                    </div>
                    """
                } else {
                    """<a class="btn-sm btn-install" href="/api/install-plugin?internalName=${p.internalName}" onclick="performAction(this, '⏳ Installing…')">⬇ Install</a>"""
                }

                val iconHtml = if (!p.iconUrl.isNullOrBlank()) {
                    """<img src="${p.iconUrl}" class="ext-icon" alt="${p.name}" onerror="this.style.display='none';this.nextElementSibling.style.display='flex';" /><div class="ext-avatar" style="display:none;">${p.name.take(1).uppercase()}</div>"""
                } else {
                    """<div class="ext-avatar">${p.name.take(1).uppercase()}</div>"""
                }

                val descHtml = if (!p.description.isNullOrBlank()) {
                    """<div class="ext-desc">${p.description.take(140)}</div>"""
                } else ""

                val author = p.authors.firstOrNull() ?: "NivinCNC"

                """
                <div class="ext-card ${if (isInst) "is-installed" else ""}" data-name="${p.name.lowercase()}" data-desc="${(p.description ?: "").lowercase()}" data-installed="${if (isInst) "1" else "0"}">
                  <div class="ext-icon-wrapper">
                    $iconHtml
                  </div>
                  <div class="ext-content">
                    <div class="ext-title-row">
                      <span class="ext-name">${p.name}</span>
                      <span class="ext-version">v${p.version}</span>
                    </div>
                    $descHtml
                    <div class="ext-tags">
                      $tvBadges
                      $langBadge
                      $statusBadge
                    </div>
                    <div class="ext-author">$author</div>
                  </div>
                  <div class="ext-actions">
                    $actionHtml
                  </div>
                </div>
                """
            }
        } else {
            loadedApis.joinToString("\n") { api ->
                val isEnabled = !disabledPlugins.contains(api.internalName)
                val statusBadge = if (isEnabled)
                    """<span class="badge badge-success">● Enabled</span>"""
                else
                    """<span class="badge badge-danger">● Disabled</span>"""

                val toggleBtn = if (isEnabled)
                    """<a class="btn-sm btn-outline-danger" href="/api/toggle-plugin?id=${api.internalName}">Disable</a>"""
                else
                    """<a class="btn-sm btn-outline-success" href="/api/toggle-plugin?id=${api.internalName}">Enable</a>"""

                val typeBadges = api.supportedTypes.joinToString(" ") { type ->
                    """<span class="tag tag-type">${type.uppercase()}</span>"""
                }

                """
                <div class="ext-card is-installed" data-name="${api.name.lowercase()}" data-desc="" data-installed="1">
                  <div class="ext-icon-wrapper">
                    <div class="ext-avatar">${api.name.take(1).uppercase()}</div>
                  </div>
                  <div class="ext-content">
                    <div class="ext-title-row">
                      <span class="ext-name">${api.name}</span>
                    </div>
                    <div class="ext-tags">$typeBadges</div>
                    <div class="ext-author"><code>${api.internalName}</code></div>
                  </div>
                  <div class="ext-actions">
                    $statusBadge
                    $toggleBtn
                  </div>
                </div>
                """
            }
        }

        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>CNCVerse Extensions & Dashboard</title>
  <style>
    :root {
      --bg: #000000;
      --card-bg: #0d0d14;
      --card-bg-2: #141420;
      --card-border: #1e1e2d;
      --accent: #8b5cf6;
      --accent-hover: #7c3aed;
      --accent-glow: rgba(139, 92, 246, 0.15);
      --text-main: #ffffff;
      --text-muted: #8e8ea0;
      --text-sub: #b0b0c2;
      --success: #10b981;
      --warning: #f59e0b;
      --danger: #ef4444;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
    body { background-color: var(--bg); color: var(--text-main); min-height: 100vh; padding: 1.5rem 1rem; }
    .container { max-width: 820px; margin: 0 auto; }
    header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.5rem; }
    .header-left h1 { font-size: 1.65rem; font-weight: 800; color: #fff; letter-spacing: -0.5px; }
    .header-left .sub { font-size: 0.85rem; color: var(--text-muted); margin-top: 2px; }
    
    .install-banner { background: linear-gradient(135deg, rgba(139, 92, 246, 0.12), rgba(67, 56, 202, 0.12)); border: 1px solid rgba(139, 92, 246, 0.25); border-radius: 1rem; padding: 1.25rem 1.5rem; margin-bottom: 1.5rem; }
    .install-banner h2 { font-size: 1.05rem; font-weight: 700; margin-bottom: 0.25rem; }
    .install-banner p { font-size: 0.85rem; color: var(--text-muted); margin-bottom: 1rem; }
    .install-actions { display: flex; flex-wrap: wrap; gap: 0.6rem; }
    .btn { display: inline-flex; align-items: center; justify-content: center; gap: 0.4rem; font-size: 0.85rem; font-weight: 600; padding: 0.55rem 1.1rem; border-radius: 0.5rem; text-decoration: none; cursor: pointer; transition: all 0.2s; border: none; }
    .btn-primary { background-color: var(--accent); color: #fff; }
    .btn-primary:hover { background-color: var(--accent-hover); }
    .btn-secondary { background-color: #171724; color: #fff; border: 1px solid var(--card-border); }
    .btn-secondary:hover { background-color: #202032; }

    .nav-tabs { display: flex; gap: 0.5rem; margin-bottom: 1.25rem; border-bottom: 1px solid var(--card-border); padding-bottom: 0.65rem; }
    .tab-btn { background: none; border: none; color: var(--text-muted); font-size: 0.92rem; font-weight: 600; padding: 0.5rem 1rem; border-radius: 0.5rem; cursor: pointer; transition: all 0.15s; }
    .tab-btn.active { color: #fff; background: var(--accent); }
    .tab-content { display: none; }
    .tab-content.active { display: block; }

    /* Extensions Screen UI Matching Mobile App */
    .search-bar { position: relative; margin-bottom: 1rem; }
    .search-bar input { width: 100%; background: #0c0c14; border: 1.5px solid var(--card-border); border-radius: 0.85rem; padding: 0.75rem 1rem 0.75rem 2.6rem; color: #fff; font-size: 0.95rem; outline: none; transition: border-color 0.2s; }
    .search-bar input:focus { border-color: var(--accent); }
    .search-icon { position: absolute; left: 0.9rem; top: 50%; transform: translateY(-50%); color: var(--text-muted); font-size: 0.95rem; }

    .filter-chips { display: flex; gap: 0.5rem; margin-bottom: 1.25rem; align-items: center; flex-wrap: wrap; }
    .chip { background: #12121e; border: 1px solid var(--card-border); color: var(--text-muted); font-size: 0.82rem; font-weight: 600; padding: 0.4rem 0.9rem; border-radius: 9999px; cursor: pointer; text-decoration: none; display: inline-flex; align-items: center; gap: 0.35rem; }
    .chip.active { background: var(--accent); color: #fff; border-color: var(--accent); }
    .chip-refresh { margin-left: auto; border-color: rgba(139, 92, 246, 0.4); color: #c4b5fd; }
    .chip-refresh:hover { background: var(--accent-glow); }

    .extensions-list { display: flex; flex-direction: column; gap: 0.85rem; }
    .ext-card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 0.95rem; padding: 1rem 1.2rem; display: flex; gap: 1rem; align-items: flex-start; transition: border-color 0.2s; }
    .ext-card.is-installed { border-color: rgba(139, 92, 246, 0.35); }
    .ext-card:hover { border-color: rgba(139, 92, 246, 0.5); }

    .ext-icon-wrapper { width: 50px; height: 50px; min-width: 50px; border-radius: 0.75rem; background: var(--card-bg-2); border: 1px solid var(--card-border); overflow: hidden; display: flex; align-items: center; justify-content: center; }
    .ext-icon { width: 100%; height: 100%; object-fit: cover; }
    .ext-avatar { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; font-size: 1.35rem; font-weight: 700; color: var(--accent); background: #16152a; }

    .ext-content { flex: 1; min-width: 0; }
    .ext-title-row { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.25rem; }
    .ext-name { font-size: 1.05rem; font-weight: 700; color: #fff; }
    .ext-version { font-size: 0.78rem; color: var(--text-muted); font-weight: 600; }
    .ext-desc { font-size: 0.82rem; color: var(--text-sub); line-height: 1.35; margin-bottom: 0.5rem; }
    .ext-tags { display: flex; flex-wrap: wrap; gap: 0.35rem; margin-bottom: 0.35rem; }
    .tag { font-size: 0.68rem; font-weight: 700; padding: 0.18rem 0.45rem; border-radius: 0.3rem; text-transform: uppercase; letter-spacing: 0.3px; }
    .tag-type { background: #231d3d; color: #c4b5fd; }
    .tag-lang { background: #1a2a44; color: #93c5fd; }
    .tag-ok { background: rgba(16, 185, 129, 0.15); color: #34d399; }
    .tag-beta { background: rgba(59, 130, 246, 0.15); color: #60a5fa; }
    .ext-author { font-size: 0.75rem; color: var(--text-muted); }

    .ext-actions { display: flex; flex-direction: column; align-items: flex-end; justify-content: center; gap: 0.5rem; margin-left: 0.5rem; }
    .ext-actions-group { display: flex; flex-direction: column; gap: 0.4rem; align-items: flex-end; }
    .badge { font-size: 0.72rem; font-weight: 700; padding: 0.2rem 0.55rem; border-radius: 9999px; }
    .badge-success { background: rgba(16, 185, 129, 0.15); color: #34d399; }
    .badge-danger { background: rgba(239, 68, 68, 0.15); color: #f87171; }

    .btn-sm { font-size: 0.8rem; font-weight: 600; padding: 0.4rem 0.85rem; border-radius: 0.5rem; text-decoration: none; cursor: pointer; border: 1px solid transparent; transition: all 0.15s; white-space: nowrap; }
    .btn-install { background: var(--accent); color: #fff; }
    .btn-install:hover { background: var(--accent-hover); }
    .btn-outline-danger { border-color: rgba(239, 68, 68, 0.4); color: #f87171; background: transparent; }
    .btn-outline-danger:hover { background: rgba(239, 68, 68, 0.15); }
    .btn-outline-success { border-color: rgba(16, 185, 129, 0.4); color: #34d399; background: transparent; }
    .btn-outline-success:hover { background: rgba(16, 185, 129, 0.15); }
    .btn-outline-warning { border-color: rgba(245, 158, 11, 0.4); color: #fbbf24; background: transparent; }
    .btn-outline-warning:hover { background: rgba(245, 158, 11, 0.15); }

    /* Settings Card */
    .card { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 0.95rem; padding: 1.5rem; }
    .form-group { margin-bottom: 1.25rem; }
    .form-group label { display: block; font-size: 0.9rem; font-weight: 700; margin-bottom: 0.25rem; color: #fff; }
    .form-group .desc { font-size: 0.8rem; color: var(--text-muted); margin-bottom: 0.45rem; }
    .form-control { width: 100%; background: #141420; border: 1px solid var(--card-border); border-radius: 0.6rem; padding: 0.7rem 0.9rem; color: #fff; font-size: 0.9rem; outline: none; }
    .form-control:focus { border-color: var(--accent); }
    .checkbox-group { display: flex; align-items: center; gap: 0.6rem; margin-top: 0.5rem; }
    .checkbox-group input { width: 18px; height: 18px; accent-color: var(--accent); cursor: pointer; }
    .alert-saved { background: rgba(16, 185, 129, 0.15); border: 1px solid rgba(16, 185, 129, 0.4); color: #34d399; padding: 0.75rem 1rem; border-radius: 0.6rem; margin-bottom: 1.25rem; font-size: 0.88rem; font-weight: 600; }
    /* Repo Bar Styles */
    .repo-bar { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 0.95rem; padding: 1rem 1.2rem; margin-bottom: 1.25rem; }
    .repo-bar-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
    .repo-bar-title { font-size: 0.92rem; font-weight: 700; color: #fff; }
    .repo-chips { display: flex; flex-wrap: wrap; gap: 0.5rem; }
    .repo-pill { background: #141420; border: 1px solid var(--card-border); border-radius: 9999px; padding: 0.35rem 0.8rem; font-size: 0.8rem; display: inline-flex; align-items: center; gap: 0.4rem; color: #e2e8f0; }
    .repo-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--success); }
    .repo-del { color: var(--danger); text-decoration: none; font-weight: 700; margin-left: 0.25rem; cursor: pointer; }
    .repo-del:hover { color: #ff6b6b; }
    .add-repo-form { display: flex; gap: 0.5rem; margin-top: 0.85rem; padding-top: 0.85rem; border-top: 1px solid var(--card-border); }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="header-left">
        <h1>Extensions</h1>
        <div class="sub">${installed.size} installed &bull; ${if (available.isNotEmpty()) available.size else loadedApis.size} available</div>
      </div>
    </header>

    <div class="install-banner">
      <h2>🚀 Stremio Integration</h2>
      <p>Connect this bridge directly to your Stremio app to stream from all installed CloudStream extensions.</p>
      <div class="install-actions">
        <a class="btn btn-primary" id="btn-stremio" href="#">📲 Install on Stremio App</a>
        <a class="btn btn-secondary" id="btn-web" href="#" target="_blank">🌐 Install on Stremio Web</a>
        <button class="btn btn-secondary" onclick="copyManifestUrl()">📋 Copy Manifest URL</button>
      </div>
    </div>

    <div class="nav-tabs">
      <button class="tab-btn active" onclick="switchTab('extensions', this)">🧩 Extensions Store (${if (available.isNotEmpty()) available.size else loadedApis.size})</button>
      <button class="tab-btn" onclick="switchTab('settings', this)">⚙️ Extension Settings</button>
    </div>

    <!-- Extensions Store Tab -->
    <div id="tab-extensions" class="tab-content active">
      <div class="repo-bar">
        <div class="repo-bar-header">
          <span class="repo-bar-title">📦 Repositories (${repos.size})</span>
          <button class="btn-sm btn-secondary" onclick="toggleRepoForm()">➕ Add Repository</button>
        </div>
        <div class="repo-chips">
          $repoChipsHtml
        </div>
        <form id="addRepoForm" action="/api/add-repo" method="POST" class="add-repo-form" style="display:none;">
          <input type="text" name="url" placeholder="Enter repo URL or shortcode (e.g. !tamil, https://.../CNC.json)" required class="form-control" style="flex:1;">
          <button type="submit" class="btn btn-primary" onclick="performAction(this, 'Adding…');">Add</button>
        </form>
      </div>

      <div class="search-bar">
        <span class="search-icon">🔍</span>
        <input type="text" id="extSearch" placeholder="Search extensions..." oninput="filterExtensions()">
      </div>

      <div class="filter-chips">
        <button class="chip active" onclick="setFilter('all', this)">All (${if (available.isNotEmpty()) available.size else loadedApis.size})</button>
        <button class="chip" onclick="setFilter('installed', this)">Installed (${installed.size})</button>
        <a class="chip chip-refresh" href="/api/refresh-repos" onclick="performAction(this, '🔄 Refreshing…')">🔄 Refresh Repos</a>
      </div>

      <div class="extensions-list" id="extensionsContainer">
        $extCards
      </div>
    </div>

    <!-- Extension Settings Tab -->
    <div id="tab-settings" class="tab-content">
      <div class="card">
        <h3 style="margin-bottom: 1.25rem; font-size: 1.15rem; font-weight: 700;">⚙️ Extension Configuration</h3>
        <form action="/api/settings" method="POST">
          <div class="form-group">
            <label for="token">FebBox Authentication Token</label>
            <div class="desc">Enter your FebBox token to stream premium hoster links.</div>
            <input type="text" class="form-control" id="token" name="token" value="$febboxToken" placeholder="Paste FebBox token">
          </div>

          <div class="form-group">
            <label for="showbox_ui_token">ShowBox Token</label>
            <div class="desc">Token for ShowBox / FebBox UI integrations.</div>
            <input type="text" class="form-control" id="showbox_ui_token" name="showbox_ui_token" value="$showboxToken" placeholder="Paste ShowBox token">
          </div>

          <div class="form-group">
            <label for="wyzie_subs_api_key">Wyzie Subtitles API Key</label>
            <div class="desc">API key for automatic multi-language subtitle fetching.</div>
            <input type="text" class="form-control" id="wyzie_subs_api_key" name="wyzie_subs_api_key" value="$wyzieKey" placeholder="Paste Wyzie API Key">
          </div>

          <div class="form-group">
            <label for="moviebox_host">MovieBox Server Mirror</label>
            <div class="desc">Select the API mirror used for MovieBox scraper engines.</div>
            <select class="form-control" id="moviebox_host" name="moviebox_host">
              <option value="https://api3.aoneroom.com" ${if (movieboxHost.contains("api3")) "selected" else ""}>Server 1 (api3.aoneroom.com)</option>
              <option value="https://api.aoneroom.com" ${if (movieboxHost == "https://api.aoneroom.com") "selected" else ""}>Server 2 (api.aoneroom.com)</option>
              <option value="https://api1.aoneroom.com" ${if (movieboxHost.contains("api1")) "selected" else ""}>Server 3 (api1.aoneroom.com)</option>
              <option value="https://api2.aoneroom.com" ${if (movieboxHost.contains("api2")) "selected" else ""}>Server 4 (api2.aoneroom.com)</option>
            </select>
          </div>

          <div class="form-group">
            <label for="ScrapeConcurrency">Scrape Concurrency</label>
            <div class="desc">Maximum parallel search threads (-1 = unlimited, default: 10).</div>
            <input type="number" class="form-control" id="ScrapeConcurrency" name="ScrapeConcurrency" value="$concurrency">
          </div>

          <div class="form-group">
            <label>Sub-provider Catalogs</label>
            <div class="checkbox-group">
              <input type="checkbox" id="ProviderTmdb" name="ProviderTmdb" value="true" ${if (tmdbEnabled) "checked" else ""}>
              <label for="ProviderTmdb" style="margin:0; font-weight: normal; color: var(--text-sub);">Enable TMDB Catalog</label>
            </div>
            <div class="checkbox-group">
              <input type="checkbox" id="ProviderCineStream" name="ProviderCineStream" value="true" ${if (cineStreamEnabled) "checked" else ""}>
              <label for="ProviderCineStream" style="margin:0; font-weight: normal; color: var(--text-sub);">Enable CineStream Catalog</label>
            </div>
            <div class="checkbox-group">
              <input type="checkbox" id="ProviderSimkl" name="ProviderSimkl" value="true" ${if (simklEnabled) "checked" else ""}>
              <label for="ProviderSimkl" style="margin:0; font-weight: normal; color: var(--text-sub);">Enable Simkl Catalog</label>
            </div>
          </div>

          <button type="submit" class="btn btn-primary" style="width: 100%; margin-top: 1rem; padding: 0.75rem;">💾 Save Extension Settings</button>
        </form>
      </div>
    </div>
  </div>

  <script>
    const manifestUrl = window.location.origin + '/manifest.json';
    const stremioProtocolUrl = manifestUrl.replace(/^https?:\/\//, 'stremio://');
    const stremioWebUrl = 'https://web.stremio.com/#/addons?addon=' + encodeURIComponent(manifestUrl);

    document.getElementById('btn-stremio').href = stremioProtocolUrl;
    document.getElementById('btn-web').href = stremioWebUrl;

    function copyManifestUrl() {
      navigator.clipboard.writeText(manifestUrl).then(() => {
        alert('Copied Manifest URL to clipboard!\n' + manifestUrl);
      });
    }

    function performAction(el, text) {
      el.innerText = text;
      el.style.opacity = '0.6';
      el.style.pointerEvents = 'none';
    }

    function toggleRepoForm() {
      const f = document.getElementById('addRepoForm');
      f.style.display = (f.style.display === 'none' || f.style.display === '') ? 'flex' : 'none';
    }

    function switchTab(tabId, el) {
      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
      document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
      document.getElementById('tab-' + tabId).classList.add('active');
      if (el) el.classList.add('active');
      window.location.hash = tabId;
    }

    let currentFilter = 'all';

    function setFilter(type, el) {
      document.querySelectorAll('.chip:not(.chip-refresh)').forEach(c => c.classList.remove('active'));
      el.classList.add('active');
      currentFilter = type;
      filterExtensions();
    }

    function filterExtensions() {
      const q = (document.getElementById('extSearch').value || '').toLowerCase().trim();
      const cards = document.querySelectorAll('.ext-card');
      cards.forEach(c => {
        const name = c.getAttribute('data-name') || '';
        const desc = c.getAttribute('data-desc') || '';
        const isInst = c.getAttribute('data-installed') === '1';

        const matchesQuery = !q || name.includes(q) || desc.includes(q);
        const matchesFilter = (currentFilter === 'all') || (currentFilter === 'installed' && isInst);

        if (matchesQuery && matchesFilter) {
          c.style.display = 'flex';
        } else {
          c.style.display = 'none';
        }
      });
    }

    if (window.location.hash === '#settings') {
      const btn = document.querySelectorAll('.tab-btn')[1];
      if (btn) switchTab('settings', btn);
    }
  </script>
</body>
</html>"""
    }
}

// ── MainApiWrapper ─────────────────────────────────────────────────────────────

/**
 * Platform-agnostic wrapper around a loaded CS3 MainAPI instance.
 * Implemented by each platform's PluginLoader actual.
 */
interface MainApiWrapper {
    val name: String
    val internalName: String
    val supportedTypes: List<String>
    suspend fun getMainPageSections(): List<String>

    suspend fun search(query: String): List<SearchResult>
    suspend fun getMainPage(page: Int, type: String, sectionName: String? = null): List<SearchResult>
    suspend fun load(url: String): MediaInfo?
    suspend fun loadLinks(dataUrl: String): List<StremioStream>
}

data class SearchResult(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val year: Int?,
    /** True when the HomePageList had isHorizontalImages = true */
    val isHorizontal: Boolean = false,
    /** The HomePageList section name — forwarded as genres in Stremio */
    val sectionName: String? = null,
)

data class MediaInfoEpisode(
    val name: String?,
    val season: Int?,
    val episode: Int?,
    val dataUrl: String,
    val posterUrl: String?
)

data class MediaInfo(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val description: String?,
    val year: Int?,
    val dataUrl: String,
    val episodes: List<MediaInfoEpisode>? = null
)

fun SearchResult.toStremiMeta(pluginInternalName: String, stremioType: String): StremioMeta {
    val encodedId = StremioIds.encode(pluginInternalName, url)
    val resolvedType = cs3TvTypeToStremio(type)
    val finalType = if (resolvedType == "other" || resolvedType.isBlank()) stremioType else resolvedType
    return StremioMeta(
        id          = encodedId,
        type        = finalType,
        name        = name,
        poster      = posterUrl,
        background  = if (isHorizontal) posterUrl else null,
        posterShape = if (isHorizontal) "landscape" else "poster",
        genres      = if (sectionName != null) listOf(sectionName) else null,
        year        = year,
        // For TV/live items, set defaultVideoId so Stremio can auto-play without extra navigation
        behaviorHints = if (finalType == "tv" || isHorizontal) {
            MetaBehaviorHints(defaultVideoId = encodedId)
        } else null,
    )
}

fun MediaInfo.toStremiMeta(pluginInternalName: String, stremioType: String): StremioMeta {
    val resolvedType = cs3TvTypeToStremio(type).let { if (it == "other" || it.isBlank()) stremioType else it }
    return StremioMeta(
        id          = StremioIds.encode(pluginInternalName, dataUrl),
        type        = resolvedType,
        name        = name,
        poster      = posterUrl,
        description = description,
        year        = year,
        videos      = episodes?.map { ep ->
            StremioVideo(
                id       = StremioIds.encode(pluginInternalName, ep.dataUrl),
                title    = ep.name ?: "Episode ${ep.episode}",
                season   = ep.season ?: 1,
                episode  = ep.episode ?: 1,
                thumbnail= ep.posterUrl ?: posterUrl
            )
        }
    )
}



