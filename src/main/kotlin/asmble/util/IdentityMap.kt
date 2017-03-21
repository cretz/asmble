package asmble.util

class IdentityMap<K, V>(override val entries: Set<Map.Entry<K, V>> = emptySet()) : AbstractMap<K, V>() {
    override fun containsKey(key: K) = entries.find { it.key === key } != null
    override operator fun get(key: K) = entries.find { it.key === key }?.value
}

public operator fun <K, V> IdentityMap<K, V>.plus(pair: Pair<K, V>): IdentityMap<K, V> {
    return IdentityMap(this.entries + object : Map.Entry<K, V> {
        override val key: K get() = pair.first
        override val value: V get() = pair.second
    })
}