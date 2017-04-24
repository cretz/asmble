package asmble.util

fun <T : Any> List<T>.add(index: Int, v: T): List<T> = this.toMutableList().let {
    it.add(index, v)
    it
}