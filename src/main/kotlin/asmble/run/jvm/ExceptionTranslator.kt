package asmble.run.jvm

import asmble.AsmErr
import java.nio.BufferOverflowException

open class ExceptionTranslator {
    fun translate(ex: Throwable): List<String> = when (ex) {
        is IndexOutOfBoundsException -> listOf("out of bounds memory access")
        is StackOverflowError -> listOf("call stack exhausted")
        is ArithmeticException -> when (ex.message) {
            "/ by zero", "BigInteger divide by zero" -> listOf("integer divide by zero")
            else -> listOf(ex.message!!.decapitalize())
        }
        is UnsupportedOperationException -> listOf("unreachable executed")
        is BufferOverflowException -> listOf("data segment does not fit")
        is IllegalArgumentException -> listOf("data segment does not fit")
        is AsmErr -> ex.asmErrStrings
        else -> emptyList()
    }

    companion object : ExceptionTranslator()
}