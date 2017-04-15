package asmble.run.jvm

import asmble.AsmErr

sealed class RunErr(message: String, cause: Throwable? = null) : RuntimeException(message, cause), AsmErr {

    class ImportTableTooSmall(
        val expected: Int,
        val actual: Int
    ) : RunErr("Import table sized $actual but expecting at least $expected") {
        override val asmErrString get() = "actual size smaller than declared"
    }

    class ImportTableTooLarge(
        val expected: Int,
        val actual: Int
    ) : RunErr("Import table sized $actual but expecting no more than $expected") {
        override val asmErrString get() = "maximum size larger than declared"
    }
}