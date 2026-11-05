package dev.app8.cloud.internal.data

import dev.app8.cloud.internal.Diagnostics

/** Identity attributes sent as `X-App8-Identity` (iOS `AttributeBag`). Thread-safe. */
internal class AttributeBag(private val log: Diagnostics) {
    private val lock = Any()
    private val values = LinkedHashMap<String, String>()

    /** Merges [attributes]; reserved keys (`$…`) are dropped and returned. */
    fun setAttributes(attributes: Map<String, String>): List<String> {
        val rejected = attributes.keys.filter { it.startsWith(RESERVED_PREFIX) }
        if (rejected.isNotEmpty()) {
            log.warning { "AttributeBag: rejected reserved keys: ${rejected.sorted().joinToString(", ")}" }
        }
        synchronized(lock) {
            attributes.forEach { (k, v) -> if (!k.startsWith(RESERVED_PREFIX)) values[k] = v }
        }
        return rejected
    }

    fun clearAttributes() {
        synchronized(lock) { values.clear() }
    }

    val snapshot: Map<String, String> get() = synchronized(lock) { LinkedHashMap(values) }

    companion object {
        /** The `$` namespace stays reserved for future SDK-managed attributes. */
        const val RESERVED_PREFIX: String = "$"
    }
}
