package android.util

/** android.util.LruCache: keeps the most recently used entries up to [maxSize] (measured by [sizeOf]). */
open class LruCache<K : Any, V : Any>(private var maxSize: Int) {
    private val map = LinkedHashMap<K, V>(0, 0.75f, true)
    private var size = 0
    private var hits = 0
    private var misses = 0
    private var puts = 0
    private var evictions = 0

    init { require(maxSize > 0) { "maxSize <= 0" } }

    fun get(key: K): V? {
        synchronized(this) {
            map[key]?.let { hits++; return it }
            misses++
        }
        val created = create(key) ?: return null
        synchronized(this) {
            val previous = map.put(key, created)
            if (previous != null) map[key] = previous else size += safeSizeOf(key, created)
            if (previous != null) {
                entryRemoved(false, key, created, previous)
                return previous
            }
        }
        trimToSize(maxSize)
        return created
    }

    fun put(key: K, value: V): V? {
        val previous: V?
        synchronized(this) {
            puts++
            size += safeSizeOf(key, value)
            previous = map.put(key, value)
            if (previous != null) size -= safeSizeOf(key, previous)
        }
        if (previous != null) entryRemoved(false, key, previous, value)
        trimToSize(maxSize)
        return previous
    }

    open fun trimToSize(maxSize: Int) {
        while (true) {
            val key: K
            val value: V
            synchronized(this) {
                if (size <= maxSize || map.isEmpty()) return
                val eldest = map.entries.iterator().next()
                key = eldest.key; value = eldest.value
                map.remove(key)
                size -= safeSizeOf(key, value)
                evictions++
            }
            entryRemoved(true, key, value, null)
        }
    }

    fun remove(key: K): V? {
        val previous = synchronized(this) { map.remove(key)?.also { size -= safeSizeOf(key, it) } }
        if (previous != null) entryRemoved(false, key, previous, null)
        return previous
    }

    open fun resize(maxSize: Int) {
        require(maxSize > 0) { "maxSize <= 0" }
        synchronized(this) { this.maxSize = maxSize }
        trimToSize(maxSize)
    }

    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) = Unit
    protected open fun create(key: K): V? = null
    protected open fun sizeOf(key: K, value: V): Int = 1

    private fun safeSizeOf(key: K, value: V): Int = sizeOf(key, value).also { check(it >= 0) { "Negative size: $key=$value" } }

    fun evictAll() = trimToSize(-1)
    @Synchronized fun size(): Int = size
    @Synchronized fun maxSize(): Int = maxSize
    @Synchronized fun hitCount(): Int = hits
    @Synchronized fun missCount(): Int = misses
    @Synchronized fun putCount(): Int = puts
    @Synchronized fun evictionCount(): Int = evictions
    @Synchronized fun snapshot(): Map<K, V> = LinkedHashMap(map)
    @Synchronized override fun toString(): String = "LruCache[maxSize=$maxSize,hits=$hits,misses=$misses]"
}
