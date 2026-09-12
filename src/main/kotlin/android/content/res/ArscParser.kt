package android.content.res

/**
 * Parser for Android compiled resource tables (resources.arsc) inside a .cs3
 * plugin. Extracts the resource-id table ("id/header_tw" → 0x7f020002) so
 * PluginResources.getIdentifier returns the SAME ids the compiled AXML layout
 * files reference — without this, findViewById lookups by name resolve to 0 and
 * match the first id-less view in the inflated tree. Also extracts string
 * values and res/ entry paths for layout/drawable ids.
 */
class ArscParser(data: ByteArray) {

    /** "type/name" (e.g. "id/header_tw") → resource id. */
    val resourceIds: Map<String, Int>

    /** Resource id → zip entry path (layout/xml/drawable types only). */
    val entryPaths: Map<Int, String>

    /** Resource id → string value (string resources only). */
    val stringValues: Map<Int, String>

    init {
        val ids = mutableMapOf<String, Int>()
        val paths = mutableMapOf<Int, String>()
        val strings = mutableMapOf<Int, String>()
        if (data.size >= 12 && u2(data, 0) == 0x0002) {
            try {
                parse(data, ids, paths, strings)
            } catch (_: Throwable) {
                // Malformed table: keep whatever was collected
            }
        }
        resourceIds = ids
        entryPaths = paths
        stringValues = strings
    }

    private fun parse(
        data: ByteArray,
        ids: MutableMap<String, Int>,
        paths: MutableMap<Int, String>,
        strings: MutableMap<Int, String>,
    ) {
        var pos = u2(data, 2) // skip table header (headerSize)
        var globalStrings: Array<String> = emptyArray()

        while (pos + 8 <= data.size) {
            val type = u2(data, pos)
            val headerSize = u2(data, pos + 2)
            val chunkSize = u4(data, pos + 4)
            if (chunkSize <= 0 || pos + chunkSize > data.size) break

            when (type) {
                0x0001 -> globalStrings = parseStringPool(data, pos) ?: emptyArray()
                0x0200 -> parsePackage(data, pos, chunkSize, headerSize, globalStrings, ids, paths, strings)
            }
            pos += chunkSize
        }
    }

    private fun parsePackage(
        data: ByteArray,
        pos: Int,
        chunkSize: Int,
        headerSize: Int,
        globalStrings: Array<String>,
        ids: MutableMap<String, Int>,
        paths: MutableMap<Int, String>,
        strings: MutableMap<Int, String>,
    ) {
        val pkgId = u4(data, pos + 8)
        // ResTable_package: id(4)@8, name(256)@12, typeStrings(4)@268, lastPublicType(4)@272, keyStrings(4)@276
        val typeStringsOff = u4(data, pos + 268)
        val keyStringsOff = u4(data, pos + 276)
        val typeStrings = parseStringPool(data, pos + typeStringsOff) ?: return
        val keyStrings = parseStringPool(data, pos + keyStringsOff) ?: return

        var ipos = pos + headerSize
        while (ipos + 8 <= pos + chunkSize) {
            val iType = u2(data, ipos)
            val iHeaderSize = u2(data, ipos + 2)
            val iChunkSize = u4(data, ipos + 4)
            if (iChunkSize <= 0 || ipos + iChunkSize > pos + chunkSize) break

            if (iType == 0x0201) { // ResTable_type (0x0202 TYPE_SPEC is skipped)
                val typeId = data[ipos + 8].toInt() and 0xFF
                val entryCount = u4(data, ipos + 12)
                val entriesStart = u4(data, ipos + 16)
                val typeName = typeStrings.getOrNull(typeId - 1) ?: continue
                val offsetsBase = ipos + iHeaderSize
                val entriesBase = ipos + entriesStart

                for (i in 0 until entryCount) {
                    val off = u4(data, offsetsBase + i * 4)
                    if (off == 0xFFFFFFFF.toInt()) continue
                    val e = entriesBase + off
                    val entrySize = u2(data, e)
                    val flags = u2(data, e + 2)
                    val keyIdx = u4(data, e + 4)
                    val keyName = keyStrings.getOrNull(keyIdx) ?: continue
                    val resId = (pkgId shl 24) or (typeId shl 16) or i

                    // First config wins; later configs for the same name are skipped
                    ids.putIfAbsent("$typeName/$keyName", resId)
                    if (typeName == "layout" || typeName == "xml" || typeName == "drawable") {
                        paths.putIfAbsent(resId, "res/$typeName/$keyName.xml")
                    }

                    val isComplex = flags and 0x0001 != 0
                    if (!isComplex && entrySize >= 8 && typeName == "string") {
                        val dataType = data[e + 11].toInt() and 0xFF
                        val value = u4(data, e + 12)
                        if (dataType == 0x03) { // STRING → global pool index
                            globalStrings.getOrNull(value)?.let { strings.putIfAbsent(resId, it) }
                        }
                    }
                }
            }
            ipos += iChunkSize
        }
    }

    private fun parseStringPool(data: ByteArray, pos: Int): Array<String>? {
        if (pos + 28 > data.size || u2(data, pos) != 0x0001) return null
        val chunkSize = u4(data, pos + 4)
        if (chunkSize <= 0 || pos + chunkSize > data.size) return null
        val stringCount = u4(data, pos + 8)
        val flags = u4(data, pos + 16)
        val stringsStart = u4(data, pos + 20)
        val isUtf8 = flags and 0x100 != 0
        val result = Array(stringCount) { "" }
        for (i in 0 until stringCount) {
            val start = pos + stringsStart + u4(data, pos + 28 + i * 4)
            result[i] = if (isUtf8) readUtf8(data, start) else readUtf16(data, start)
        }
        return result
    }

    private fun readUtf16(data: ByteArray, start: Int): String {
        var p = start
        // Length prefixes are little-endian u16s (0x8000 flag = high word follows)
        var len = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or
                ((data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8))
            p += 2
        }
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            // Characters are little-endian u16s
            val c = (data[p + i * 2].toInt() and 0xFF) or ((data[p + i * 2 + 1].toInt() and 0xFF) shl 8)
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readUtf8(data: ByteArray, start: Int): String {
        var p = start
        var n = data[p].toInt() and 0xFF
        p++
        if (n and 0x80 != 0) p++
        var len = data[p].toInt() and 0xFF
        p++
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or (data[p].toInt() and 0xFF)
            p++
        }
        return String(data, p, len.coerceIn(0, data.size - p), Charsets.UTF_8)
    }

    private fun u2(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u4(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
