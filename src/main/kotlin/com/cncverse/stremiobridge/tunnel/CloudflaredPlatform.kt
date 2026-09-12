package com.cncverse.stremiobridge.tunnel

import java.io.File

fun getCloudflaredBinaryPath(): String {
    val os = System.getProperty("os.name", "").lowercase()
    val isWindows = os.contains("win")
    val fileName = if (isWindows) "cloudflared.exe" else "cloudflared"
    
    val userHome = System.getProperty("user.home", ".")
    val dir = File(userHome, ".cncverse")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return File(dir, fileName).absolutePath
}

fun setFileExecutable(filePath: String) {
    val file = File(filePath)
    if (file.exists()) {
        file.setExecutable(true, false)
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) {
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", filePath)).waitFor()
            } catch (_: Exception) {}
        }
    }
}

fun getPlatformCloudflaredDownloadUrl(): String {
    val os = System.getProperty("os.name", "").lowercase()
    val arch = System.getProperty("os.arch", "").lowercase()
    
    return when {
        os.contains("win") -> {
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
        }
        os.contains("mac") || os.contains("darwin") -> {
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-darwin-arm64"
            } else {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-darwin-amd64"
            }
        }
        else -> { // Linux
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
            } else if (arch.contains("arm")) {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm"
            } else {
                "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
            }
        }
    }
}

fun resolveCloudflareEdgeIps(): List<String> {
    // Desktop systems have /etc/resolv.conf and handle DNS resolution correctly
    return emptyList()
}
