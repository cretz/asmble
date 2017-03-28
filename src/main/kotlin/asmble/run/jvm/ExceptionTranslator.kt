package asmble.run.jvm

import asmble.AsmErr

open class ExceptionTranslator {
    fun translate(ex: Throwable): String? = when (ex) {
        is IndexOutOfBoundsException -> "out of bounds memory access"
        is StackOverflowError -> "call stack exhausted"
        is ArithmeticException -> ex.message?.decapitalize()
        is AsmErr -> ex.asmErrString
        else -> null
    }

    companion object : ExceptionTranslator()
}