package asmble.run.jvm

open class ExceptionTranslator {
    fun translateOrRethrow(ex: Throwable) = translate(ex) ?: throw ex

    fun translate(ex: Throwable): String? = when (ex) {
        is IndexOutOfBoundsException -> "out of bounds memory access"
        else -> null
    }

    companion object : ExceptionTranslator()
}