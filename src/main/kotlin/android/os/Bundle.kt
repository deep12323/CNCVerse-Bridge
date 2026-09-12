package android.os

/** Minimal android.os.Bundle stub backed by a map. */
class Bundle {
    private val map = mutableMapOf<String, Any?>()

    constructor()
    constructor(b: Bundle) { map.putAll(b.map) }

    fun putString(key: String, value: String?) { map[key] = value }
    fun getString(key: String): String? = map[key] as? String
    fun putInt(key: String, value: Int) { map[key] = value }
    fun getInt(key: String): Int = map[key] as? Int ?: 0
    fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    fun putLong(key: String, value: Long) { map[key] = value }
    fun getLong(key: String): Long = map[key] as? Long ?: 0L
    fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    fun putBoolean(key: String, value: Boolean) { map[key] = value }
    fun getBoolean(key: String): Boolean = map[key] as? Boolean ?: false
    fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun remove(key: String) { map.remove(key) }
    fun keySet(): Set<String> = map.keys
    fun isEmpty(): Boolean = map.isEmpty()
}
