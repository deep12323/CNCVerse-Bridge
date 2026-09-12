package org.xmlpull.v1

/**
 * Minimal org.xmlpull.v1.XmlPullParser interface matching the Android API —
 * plugins and the framework reference it for XML resource parsing.
 */
interface XmlPullParser {
    companion object {
        const val START_DOCUMENT = 0
        const val END_DOCUMENT = 1
        const val START_TAG = 2
        const val END_TAG = 3
        const val TEXT = 4
        const val CDSECT = 5
        const val ENTITY_REF = 6
        const val IGNORABLE_WHITESPACE = 7
        const val PROCESSING_INSTRUCTION = 8
        const val COMMENT = 9
        const val DOCDECL = 10
    }

    val eventType: Int
    val name: String?
    val text: String?
    val namespace: String
    val prefix: String?
    val depth: Int
    val lineInformation: Int

    fun next(): Int
    fun nextToken(): Int
    fun require(type: Int, namespace: String?, name: String?)
    fun getAttributeValue(namespace: String?, name: String?): String?
    fun getAttributeValueByIndex(index: Int): String?
    fun getAttributeNamespace(index: Int): String
    fun getAttributeName(index: Int): String
    fun getAttributePrefix(index: Int): String
    fun getAttributeType(index: Int): String
    fun isAttributeDefault(index: Int): Boolean
    fun nextText(): String?
    fun defineEntityReplacementText(entityName: String?, replacementText: String?)
    fun getFeature(name: String?): Boolean
    fun setFeature(name: String?, state: Boolean)
    fun setProperty(name: String?, value: Any?)
    fun getProperty(name: String?): Any?
    fun setInput(inputStream: java.io.InputStream?, encoding: String?)
    fun setInput(reader: java.io.Reader?)
    fun getInputEncoding(): String?
}
