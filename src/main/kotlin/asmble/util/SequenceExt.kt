package asmble.util

fun <T : Any> Sequence<T?>.takeUntilNull(): Sequence<T> {
    @Suppress("UNCHECKED_CAST") // who cares, it's erased and we know there aren't nulls
    return this.takeWhile({ it != null }) as Sequence<T>
}

