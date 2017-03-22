package asmble.util

fun <T : Any> Collection<T?>.takeUntilNull(): List<T> {
    return this.asSequence().takeUntilNull().toList()
}

fun <T : Any, R : Any> Collection<T>.takeUntilNullLazy(map: (T) -> R?): List<R> {
    return this.asSequence().map(map).takeUntilNull().toList()
}