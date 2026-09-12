package com.cncverse.stremiobridge.server

import io.ktor.server.application.Application
import com.cncverse.stremiobridge.server.hls.installMpdProxyRoutes

fun Application.setupMpdProxyRoutes() {
    installMpdProxyRoutes()
}
