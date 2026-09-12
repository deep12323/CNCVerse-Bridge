package com.cncverse.stremiobridge.tunnel

import com.cncverse.stremiobridge.state.ServerState
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object CloudflaredManager {
    private const val WORKER_URL = "xxxxxxxxx"

    private var tunnelProcess: Process? = null
    private var tunnelScope: CoroutineScope? = null

    var deviceIp: String = "127.0.0.1"

    private val httpClient by lazy {
        HttpClient(CIO)
    }

    fun isInstalled(): Boolean {
        val path = getCloudflaredBinaryPath()
        val file = File(path)
        return file.exists() && file.length() > 100_000 // Cloudflared binary is usually >10MB
    }

    suspend fun downloadCloudflared(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val downloadUrl = getPlatformCloudflaredDownloadUrl()
        val targetPath = getCloudflaredBinaryPath()
        val targetFile = File(targetPath)
        val tempFile = File("$targetPath.tmp")

        ServerState.info("Downloading Cloudflared from $downloadUrl ...")

        try {
            val response = httpClient.get(downloadUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    if (contentLength != null && contentLength > 0L) {
                        onProgress(bytesSentTotal.toFloat() / contentLength.toFloat())
                    }
                }
            }

            if (response.status.value in 200..299) {
                val bytes = response.readRawBytes()
                tempFile.writeBytes(bytes)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)
                setFileExecutable(targetFile.absolutePath)
                ServerState.info("Cloudflared downloaded successfully (${bytes.size / (1024 * 1024)} MB)")
                return@withContext true
            } else {
                ServerState.error("Cloudflared download failed: HTTP ${response.status.value}")
                return@withContext false
            }
        } catch (e: Exception) {
            ServerState.error("Cloudflared download error: ${e.message}")
            if (tempFile.exists()) tempFile.delete()
            return@withContext false
        }
    }

    private suspend fun fetchTunnelToken(deviceId: String): String? = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = httpClient.post(WORKER_URL) {
                header("Content-Type", "application/json")
                setBody("{\"deviceId\":\"$deviceId\"}")
            }
            if (response.status.value in 200..299) {
                val responseText = response.bodyAsText()
                ServerState.info("Tunnel Token fetched successfully: $responseText")
                // Simple regex to extract token from JSON {"success":true,"token":"...","domain":"..."}
                val match = Regex("\"token\":\"([^\"]+)\"").find(responseText)
                return@withContext match?.groupValues?.get(1)
            }
            else{
                ServerState.error("Failed to fetch tunnel token: HTTP ${response.status.value} Body: ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            ServerState.warn("Failed to fetch tunnel token: ${e.message}")
        }
        return@withContext null
    }

    fun startTunnel(port: Int) {
        if (tunnelProcess != null && tunnelProcess?.isAlive == true) {
            ServerState.info("Cloudflare Tunnel is already running")
            return
        }

        stopTunnel()

        val binaryPath = getCloudflaredBinaryPath()
        setFileExecutable(binaryPath)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        tunnelScope = scope

        scope.launch {
            try {
                // Always fetch a fresh token from the worker on every start
                ServerState.info("Fetching Tunnel Token from Worker...")
                val deviceId = DeviceIdManager.getDeviceId()
                val token = fetchTunnelToken(deviceId)
                if (token != null) {
                    DeviceIdManager.setTunnelToken(token)
                } else {
                    ServerState.error("Failed to provision Cloudflare Tunnel")
                    return@launch
                }

                ServerState.info("Starting Cloudflare Tunnel on port $port ...")
                
                // Get edge IPs (bypasses DNS issue on Android 10+)
                val edgeIps = resolveCloudflareEdgeIps()
                val cmd = mutableListOf(
                    binaryPath, "tunnel", 
                    "--url", "http://${deviceIp}:$port", 
                    "--edge-ip-version", "4",
                    "--no-autoupdate"
                )
                
                // Add up to 4 edge IPs
                for (ip in edgeIps.take(4)) {
                    cmd.addAll(listOf("--edge", ip))
                }
                
                cmd.addAll(listOf("run", "--token", token))
                
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)

                val process = pb.start()
                tunnelProcess = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?

                val tryCloudflareRegex = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")

                while (isActive && process.isAlive) {
                    line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        ServerState.info("[cloudflared] $line")

                        if (line.contains("Registered tunnel connection") || (line.contains("Connection ") && line.contains("registered"))) {
                            val url = DeviceIdManager.getDeviceSubdomainUrl()
                            ServerState.activeTunnelUrl.value = url
                            ServerState.info("🚀 Cloudflare Tunnel connected: $url")
                        } else {
                            val match = tryCloudflareRegex.find(line)
                            if (match != null) {
                                val url = match.value
                                ServerState.activeTunnelUrl.value = url
                                ServerState.info("🚀 Cloudflare Tunnel active: $url")
                            }
                        }
                    }
                }

                val exitCode = process.waitFor()
                ServerState.info("Cloudflared process exited with code $exitCode")
            } catch (e: CancellationException) {
                // Expected when tunnel is stopped
            } catch (e: Exception) {
                ServerState.error("Cloudflared tunnel error: ${e.message}")
            } finally {
                ServerState.activeTunnelUrl.value = null
            }
        }
    }

    fun stopTunnel() {
        tunnelScope?.cancel()
        tunnelScope = null

        try {
            tunnelProcess?.let { process ->
                if (process.isAlive) {
                    process.destroy()
                    if (process.isAlive) {
                        process.destroyForcibly()
                    }
                }
            }
        } catch (e: Exception) {
            ServerState.warn("Error stopping cloudflared process: ${e.message}")
        } finally {
            tunnelProcess = null
            ServerState.activeTunnelUrl.value = null
            ServerState.info("Cloudflare Tunnel stopped")
        }
    }
}
