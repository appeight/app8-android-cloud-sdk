package dev.app8.cloud.internal.cache

import dev.app8.cloud.internal.Diagnostics
import java.io.File

/** Byte-budget LRU blob cache (iOS `AssetCache`). IO errors become misses. */
internal class AssetCache(
    private val blobsDir: File,
    private val byteBudget: Long,
    private val indexFile: File,
    private val log: Diagnostics,
) {
    private val lock = Any()
    private val lruOrder = ArrayList<String>()
    private val sizes = HashMap<String, Long>()

    init {
        loadIndex()
    }

    fun read(key: String): ByteArray? {
        val data = File(blobsDir, sanitizedPathComponent(key)).readBytesOrNull() ?: return null
        synchronized(lock) {
            // Seed the size of an orphan blob the index didn't know, or eviction under-counts the budget.
            if (sizes[key] == null) sizes[key] = data.size.toLong()
            markAccess(key)
        }
        return data
    }

    fun write(key: String, data: ByteArray): Boolean {
        val file = File(blobsDir, sanitizedPathComponent(key))
        val size = data.size.toLong()
        if (size > byteBudget) {
            log.warning { "AssetCache: blob $key ($size bytes) > budget $byteBudget; skipping write." }
            return false
        }
        // Decide evictions under the lock; do the IO outside it.
        val evict = synchronized(lock) { computeEvictions(size, key) }
        for (victim in evict) {
            File(blobsDir, sanitizedPathComponent(victim)).delete()
            log.debug { "AssetCache: evicted $victim to make room." }
        }
        if (!atomicWrite(data, file)) return false
        val snapshot = synchronized(lock) {
            sizes[key] = size
            markAccess(key)
            lruOrder.map { it to (sizes[it] ?: 0L) }
        }
        persistIndex(snapshot)
        return true
    }

    /** Drops state, index and blobs. */
    fun reset() {
        synchronized(lock) {
            lruOrder.clear()
            sizes.clear()
        }
        indexFile.delete()
        blobsDir.deleteRecursively()
        blobsDir.mkdirs()
    }

    private fun markAccess(key: String) {
        lruOrder.remove(key)
        lruOrder.add(key)
    }

    private fun computeEvictions(incoming: Long, newKey: String): List<String> {
        var total = sizes.values.sum()
        sizes[newKey]?.let { total -= it }
        val evict = ArrayList<String>()
        while (total + incoming > byteBudget && lruOrder.isNotEmpty() && lruOrder.first() != newKey) {
            val victim = lruOrder.removeAt(0)
            sizes.remove(victim)?.let { total -= it }
            evict += victim
        }
        return evict
    }

    /** Index: one `size<TAB>key` line per entry, oldest first. */
    private fun loadIndex() {
        val raw = indexFile.readBytesOrNull()?.decodeToString() ?: return
        synchronized(lock) {
            for (line in raw.split('\n')) {
                if (line.isEmpty()) continue
                val parts = line.split('\t', limit = 2)
                val size = parts.getOrNull(0)?.toLongOrNull() ?: continue
                val key = parts.getOrNull(1) ?: continue
                sizes[key] = size
                lruOrder += key
            }
        }
    }

    private fun persistIndex(snapshot: List<Pair<String, Long>>) {
        val text = buildString { snapshot.forEach { (key, size) -> append(size).append('\t').append(key).append('\n') } }
        atomicWrite(text.encodeToByteArray(), indexFile)
    }
}
