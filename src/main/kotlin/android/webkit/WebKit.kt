package android.webkit

import android.content.Context
import android.view.ViewGroup
import java.io.InputStream

/**
 * android.webkit stubs. Desktop has no WebView engine, so these are inert —
 * but plugins referencing WebView-based resolvers still verify and load.
 */
class WebView(context: Context? = null) : ViewGroup() {
    var settings: WebSettings = WebSettings()
        private set

    fun setWebViewClient(client: WebViewClient?) {}
    fun setWebChromeClient(client: WebChromeClient?) {}
    fun loadUrl(url: String?) {}
    fun evaluateJavascript(script: String?, resultCallback: ValueCallback<String>?) {}
    fun destroy() {}
    fun setJavaScriptEnabled(enabled: Boolean) {}
}

open class WebViewClient {
    open fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
}

open class WebChromeClient

class WebSettings {
    enum class LayoutAlgorithm {
        NORMAL, SINGLE_COLUMN, NARROW_COLUMNS, TEXT_AUTOSIZING
    }

    fun setJavaScriptEnabled(flag: Boolean) {}
    fun setDomStorageEnabled(flag: Boolean) {}
    fun setUserAgentString(ua: String?) {}
    fun getUserAgentString(): String? = null
    fun setBlockNetworkImage(flag: Boolean) {}
    fun setMediaPlaybackRequiresUserGesture(require: Boolean) {}
    fun setMixedContentMode(mode: Int) {}
    fun setLoadsImagesAutomatically(flag: Boolean) {}
    fun setAllowContentAccess(allow: Boolean) {}
    
    fun setJavaScriptCanOpenWindowsAutomatically(flag: Boolean) {}
    fun setSupportZoom(flag: Boolean) {}
    fun setBuiltInZoomControls(flag: Boolean) {}
    fun setDisplayZoomControls(flag: Boolean) {}
    fun setUseWideViewPort(flag: Boolean) {}
    fun setLoadWithOverviewMode(flag: Boolean) {}
    fun setCacheMode(mode: Int) {}
    fun setAppCacheEnabled(flag: Boolean) {}
    fun setDatabaseEnabled(flag: Boolean) {}
    fun setAllowFileAccess(flag: Boolean) {}
    fun setAllowFileAccessFromFileURLs(flag: Boolean) {}
    fun setAllowUniversalAccessFromFileURLs(flag: Boolean) {}
    fun setTextZoom(zoom: Int) {}
    fun setLayoutAlgorithm(algorithm: LayoutAlgorithm?) {}
    fun setStandardFontFamily(font: String?) {}
    fun setDefaultFontSize(size: Int) {}
    fun setOffscreenPreRaster(flag: Boolean) {}
    fun setGeolocationEnabled(flag: Boolean) {}
    fun setSafeBrowsingEnabled(flag: Boolean) {}
}

interface WebResourceRequest {
    fun getUrl(): android.net.Uri?
    fun getRequestHeaders(): Map<String, String>?
}

class WebResourceResponse(
    val mimeType: String?,
    val encoding: String?,
    val data: InputStream?,
) {
    constructor(reason: String?, mimeType: String?) : this(mimeType, null, null)
}

interface ValueCallback<T> {
    fun onReceiveValue(value: T?)
}

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class JavascriptInterface

object CookieManager {
    @JvmStatic
    fun getInstance(): CookieManager = this

    fun setCookie(url: String?, value: String?) {}
    fun getCookie(url: String?): String? = null
    fun removeAllWindows() {}
    fun flush() {}
    fun setAcceptCookie(accept: Boolean) {}
    fun setAcceptThirdPartyCookies(webview: WebView?, accept: Boolean) {}
}
