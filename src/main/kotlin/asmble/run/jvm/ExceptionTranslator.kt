package asmble.run.jvm

import asmble.compile.jvm.CompileErr

open class ExceptionTranslator {
    fun translate(ex: Throwable): String? = when (ex) {
        is IndexOutOfBoundsException -> "out of bounds memory access"
        is CompileErr -> ex.asmErrString
        else -> null
    }

    companion object : ExceptionTranslator()
}