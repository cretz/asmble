package asmble.util

fun <T : Any> Sequence<T?>.takeUntilNull(): Sequence<T> {
    // Unchecked cast, oh well, it's erased
    return this.takeWhile({ it != null }) as Sequence<T>
}

