package asmble.util

fun <T : Any> Collection<T?>.takeUntilNull(): Collection<T> {
    return this.asSequence().takeUntilNull().toList()
}


