package android.net

/**
 * Functional android.net.Uri backed by java.net.URI. Plugins parse and inspect
 * stream URLs through Uri at runtime.
 */
class Uri private constructor(private val delegate: java.net.URI?) {

    override fun toString(): String = delegate?.toString() ?: ""

    fun getScheme(): String? = delegate?.scheme
    fun getHost(): String? = delegate?.host
    fun getPort(): Int = delegate?.port ?: -1
    fun getPath(): String? = delegate?.path
    fun getQuery(): String? = delegate?.rawQuery
    fun getFragment(): String? = delegate?.fragment
    fun getUserInfo(): String? = delegate?.userInfo
    fun isAbsolute(): Boolean = delegate?.isAbsolute ?: false
    fun isRelative(): Boolean = !(delegate?.isAbsolute ?: false)
    fun getLastPathSegment(): String? = delegate?.path?.trimEnd('/')?.substringAfterLast('/')

    fun getQueryParameter(key: String): String? = try {
        delegate?.rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == key }
            ?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    } catch (_: Exception) { null }

    fun getQueryParameters(key: String): List<String> = try {
        delegate?.rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.filter { it[0] == key }
            ?.mapNotNull { it.getOrNull(1) }
            ?.map { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun getQueryParameterNames(): Set<String> = try {
        delegate?.rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2)[0] }
            ?.toSet()
            ?: emptySet()
    } catch (_: Exception) { emptySet() }

    fun buildUpon(): Builder = Builder().apply {
        delegate?.let {
            scheme = it.scheme; authority = it.rawAuthority; path = it.rawPath
            query = it.rawQuery; fragment = it.rawFragment
        }
    }

    class Builder {
        internal var scheme: String? = null
        internal var authority: String? = null
        internal var path: String? = null
        internal var query: String? = null
        internal var fragment: String? = null
        private val extraQuery = mutableListOf<Pair<String, String>>()

        fun scheme(scheme: String?): Builder = this.also { it.scheme = scheme }
        fun authority(authority: String?): Builder = this.also { it.authority = authority }
        fun path(path: String?): Builder = this.also { it.path = path }
        fun query(query: String?): Builder = this.also { it.query = query }
        fun fragment(fragment: String?): Builder = this.also { it.fragment = fragment }
        fun appendPath(path: String?): Builder = this.also {
            it.path = (it.path?.trimEnd('/') ?: "") + "/" + (path?.trim('/') ?: "")
        }

        fun appendQueryParameter(key: String, value: String?): Builder = this.also {
            extraQuery.add(key to (value ?: ""))
        }

        fun build(): Uri {
            val fullQuery = listOfNotNull(query) + extraQuery.map { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            val sb = StringBuilder()
            scheme?.let { sb.append(it).append("://") }
            authority?.let { sb.append(it) }
            path?.let { sb.append(it) }
            if (fullQuery.isNotEmpty()) sb.append('?').append(fullQuery.joinToString("&"))
            fragment?.let { sb.append('#').append(it) }
            return parse(sb.toString())
        }
    }

    companion object {
        @JvmStatic
        fun parse(uriString: String?): Uri = Uri(runCatching { java.net.URI(uriString) }.getOrNull())
    }
}
