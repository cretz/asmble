package asmble.io

import asmble.AsmErr

sealed class IoErr(message: String, cause: Throwable? = null) : RuntimeException(message, cause), AsmErr {
    class UnexpectedEnd : IoErr("Unexpected EOF") {
        override val asmErrString get() = "unexpected end"
    }

    class InvalidMagicNumber : IoErr("Invalid magic number") {
        override val asmErrString get() = "magic header not detected"
    }

    class InvalidVersion(actual: Long, expected: List<Long>) : IoErr("Got version $actual, only accepts $expected") {
        override val asmErrString get() = "unknown binary version"
    }

    class InvalidSectionId(id: Int) : IoErr("Invalid section ID of $id") {
        override val asmErrString get() = "invalid section id"
    }

    class InvalidCodeLength(funcLen: Int, codeLen: Int) : IoErr("Got $funcLen funcs but only $codeLen bodies") {
        override val asmErrString get() = "function and code section have inconsistent lengths"
    }

    class InvalidMutability : IoErr("Invalid mutability boolean") {
        override val asmErrString = "invalid mutability"
    }
}