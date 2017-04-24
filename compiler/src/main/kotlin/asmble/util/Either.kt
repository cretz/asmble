package asmble.util

sealed class Either<out L, out R> {
    data class Left<out T>(val v: T) : Either<T, Nothing>()
    data class Right<out T>(val v: T) : Either<Nothing, T>()
}
