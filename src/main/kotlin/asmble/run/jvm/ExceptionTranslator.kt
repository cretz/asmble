package asmble.run.jvm

import asmble.AsmErr

open class ExceptionTranslator {
    fun translate(ex: Throwable): String? = when (ex) {
        is IndexOutOfBoundsException -> "out of bounds memory access"
        is StackOverflowError -> "call stack exhausted"
        is ArithmeticException -> when (ex.message) {
            "/ by zero", "BigInteger divide by zero" -> "integer divide by zero"
            else -> ex.message?.decapitalize()
        }
        is UnsupportedOperationException -> "unreachable executed"
        is AsmErr -> ex.asmErrString
        else -> null
    }

    companion object : ExceptionTranslator()
}