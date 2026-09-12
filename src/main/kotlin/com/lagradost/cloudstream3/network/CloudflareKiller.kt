package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Desktop JVM counterpart of the Android CloudflareKiller.
 *
 * Many plugins reference this class (it is not part of cloudstream-api.jar),
 * so it must exist on the host classpath for those plugins to load at all.
 * The Android version solves Cloudflare challenges in a WebView, which is not
 * available on desktop — here we pass the request through unchanged and let
 * the caller see the original 403/503 response.
 */
class CloudflareKiller : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}
