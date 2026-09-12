package android.content.res

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Parser for Android compiled binary XML (AXML). Produces pull-parser events
 * over the element tree of "res" XML entries inside a .cs3 plugin, which is
 * what plugin settings fragments consume on desktop.
 */
class AxmlParser(data: ByteArray) : XmlResourceParser {

    private val buf = data
    private var pos = 0
    private var strings: Array<String> = emptyArray()

    private var currentEvent = XmlPullParser.START_DOCUMENT
    private var currentName: String? = null
    private var currentAttrs: List<AxAttr> = emptyList()
    private val depthStack = ArrayDeque<String>()

    private data class AxAttr(
        val namespace: String?,
        val name: String,
        val rawValue: String?,
        val dataType: Int,
        val data: Int,
    )

    init {
        parseChunkHeader()
    }

    constructor(inputStream: InputStream) : this(inputStream.readBytes())
    constructor(inputStream: ByteArrayInputStream) : this(inputStream.readBytes())

    // ── chunk plumbing (AXML is little-endian) ────────────────────────────

    private fun u2(offset: Int): Int {
        val p = pos + offset
        return (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)
    }

    private fun u4(offset: Int): Int {
        val p = pos + offset
        return (buf[p].toInt() and 0xFF) or
            ((buf[p + 1].toInt() and 0xFF) shl 8) or
            ((buf[p + 2].toInt() and 0xFF) shl 16) or
            ((buf[p + 3].toInt() and 0xFF) shl 24)
    }

    private fun skip(size: Int) { pos += size }

    private fun parseChunkHeader() {
        // File header chunk: type 0x0003, headerSize, size
        skip(u2(2))
    }

    // ── string pool ────────────────────────────────────────────────────────

    private fun parseStringPool(chunkSize: Int) {
        val stringCount = u4(8)
        val flags = u4(16)
        val stringsStart = u4(20)
        val isUtf8 = flags and 0x100 != 0
        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = u4(28 + i * 4)
        }
        val result = Array(stringCount) { "" }
        for (i in 0 until stringCount) {
            val start = pos + stringsStart + offsets[i]
            result[i] = if (isUtf8) readUtf8String(start) else readUtf16String(start)
        }
        strings = result
        skip(chunkSize)
    }

    private fun readUtf16String(start: Int): String {
        var p = start
        // Length prefixes are little-endian u16s (0x8000 flag = high word follows)
        var len = (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or
                ((buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8))
            p += 2
        }
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            // Characters are little-endian u16s
            val c = (buf[p + i * 2].toInt() and 0xFF) or ((buf[p + i * 2 + 1].toInt() and 0xFF) shl 8)
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readUtf8String(start: Int): String {
        var p = start
        // char count (may be 2 bytes) then byte length
        var n = buf[p].toInt() and 0xFF
        p++
        if (n and 0x80 != 0) p++
        var len = buf[p].toInt() and 0xFF
        p++
        if (len and 0x80 != 0) {
            len = ((len and 0x7F) shl 8) or (buf[p].toInt() and 0xFF)
            p++
        }
        return String(buf, p, len, Charsets.UTF_8)
    }

    private fun str(idx: Int): String? = strings.getOrNull(idx)

    // ── events ─────────────────────────────────────────────────────────────

    override fun next(): Int {
        while (pos + 8 <= buf.size) {
            val type = u2(0)
            val headerSize = u2(2)
            val chunkSize = u4(4)
            if (chunkSize <= 0 || pos + chunkSize > buf.size) {
                currentEvent = XmlPullParser.END_DOCUMENT
                return currentEvent
            }
            when (type) {
                0x0001 -> parseStringPool(chunkSize)
                0x0102 -> { // START_ELEMENT
                    val headerSize = u2(2)
                    val nameIdx = u4(20)
                    val attrStart = u2(24)
                    val attrSize = u2(26)
                    val attrCount = u2(28)
                    val attrs = mutableListOf<AxAttr>()
                    for (i in 0 until attrCount) {
                        // attributeStart is relative to the attrExt structure,
                        // which itself starts at headerSize
                        val base = headerSize + attrStart + i * attrSize
                        val nsIdx = u4(base)
                        val attrNameIdx = u4(base + 4)
                        val rawIdx = u4(base + 8)
                        val vType = buf[pos + base + 15].toInt() and 0xFF
                        val vData = u4(base + 16)
                        attrs.add(
                            AxAttr(
                                namespace = if (nsIdx != -1) str(nsIdx) else null,
                                name = str(attrNameIdx) ?: "",
                                rawValue = if (rawIdx != -1) str(rawIdx) else null,
                                dataType = vType,
                                data = vData,
                            )
                        )
                    }
                    currentName = str(nameIdx)
                    currentAttrs = attrs
                    depthStack.addLast(currentName ?: "")
                    currentEvent = XmlPullParser.START_TAG
                    skip(chunkSize)
                    return currentEvent
                }
                0x0103 -> { // END_ELEMENT
                    if (depthStack.isNotEmpty()) depthStack.removeLast()
                    currentName = str(u4(16))
                    currentAttrs = emptyList()
                    currentEvent = XmlPullParser.END_TAG
                    skip(chunkSize)
                    return currentEvent
                }
                else -> skip(chunkSize)
            }
        }
        currentEvent = XmlPullParser.END_DOCUMENT
        return currentEvent
    }


    override val eventType: Int get() = currentEvent
    override val name: String? get() = currentName
    override val text: String? get() = null
    override val namespace: String get() = ""
    override val prefix: String? get() = null
    override val depth: Int get() = depthStack.size
    override val lineInformation: Int get() = -1
    override fun getAttributeCount(): Int =
        if (currentEvent == XmlPullParser.START_TAG) currentAttrs.size else -1

    override fun nextToken(): Int = next()
    override fun require(type: Int, namespace: String?, name: String?) {}

    private fun attrValue(a: AxAttr): String? {
        // STRING (0x03) → pool index; BOOLEAN (0x12) → true/false; else decimal/hex
        return when (a.dataType) {
            0x03 -> str(a.data)
            0x12 -> if (a.data != 0) "true" else "false"
            0x01, 0x02 -> "0x%08x".format(a.data) // resource reference
            0x10 -> a.data.toString()
            0x04 -> Float.fromBits(a.data).toString()
            else -> a.rawValue ?: a.data.toString()
        }
    }

    override fun getAttributeValue(namespace: String?, name: String?): String? =
        currentAttrs.firstOrNull { it.name == name && (namespace == null || it.namespace == namespace) }
            ?.let { attrValue(it) }

    override fun getAttributeValueByIndex(index: Int): String? =
        currentAttrs.getOrNull(index)?.let { attrValue(it) }

    override fun getAttributeValue(index: Int): String = getAttributeValueByIndex(index) ?: ""

    override fun getAttributeNamespace(index: Int): String = currentAttrs.getOrNull(index)?.namespace ?: ""
    override fun getAttributeName(index: Int): String = currentAttrs.getOrNull(index)?.name ?: ""
    override fun getAttributePrefix(index: Int): String = ""
    override fun getAttributeType(index: Int): String = "cdatat"
    override fun isAttributeDefault(index: Int): Boolean = false
    override fun nextText(): String? = null
    override fun defineEntityReplacementText(entityName: String?, replacementText: String?) {}
    override fun getFeature(name: String?): Boolean = false
    override fun setFeature(name: String?, state: Boolean) {}
    override fun setProperty(name: String?, value: Any?) {}
    override fun getProperty(name: String?): Any? = null
    override fun setInput(inputStream: java.io.InputStream?, encoding: String?) {}
    override fun setInput(reader: java.io.Reader?) {}
    override fun getInputEncoding(): String? = "utf-8"

    override fun close() {}

    // ── AttributeSet ───────────────────────────────────────────────────────

    override fun getAttributeBooleanValue(index: Int, defaultValue: Boolean): Boolean =
        getAttributeValueByIndex(index)?.toBooleanStrictOrNull() ?: defaultValue
    override fun getAttributeBooleanValue(namespace: String?, name: String, defaultValue: Boolean): Boolean =
        getAttributeValue(namespace, name)?.toBooleanStrictOrNull() ?: defaultValue
    override fun getAttributeIntValue(index: Int, defaultValue: Int): Int =
        getAttributeValueByIndex(index)?.toIntOrNull() ?: defaultValue
    override fun getAttributeIntValue(namespace: String?, name: String, defaultValue: Int): Int =
        getAttributeValue(namespace, name)?.toIntOrNull() ?: defaultValue
    override fun getAttributeResourceValue(index: Int, defaultValue: Int): Int =
        currentAttrs.getOrNull(index)?.takeIf { it.dataType == 0x01 }?.data ?: defaultValue
    override fun getAttributeResourceValue(namespace: String?, name: String, defaultValue: Int): Int =
        currentAttrs.firstOrNull { it.name == name && it.dataType == 0x01 }?.data ?: defaultValue
    override fun getIdAttribute(): String? = getAttributeValue(null, "id")
    override fun getClassAttribute(): String? = getAttributeValue(null, "class")
    override fun getAttributeValue(name: String): String = getAttributeValue(null as String?, name) ?: ""
    override fun getAttributeNameResource(index: Int): Int = 0
    override fun getAttributeListValue(namespace: String?, attribute: String?, options: Array<String>?, defaultValue: Int): Int = defaultValue
}
