package asmble.io

import asmble.AsmErr

sealed class IoErr(message: String, cause: Throwable? = null) : RuntimeException(message, cause), AsmErr {
    class UnexpectedEnd : IoErr("Unexpected EOF") {
        override val asmErrString get() = "unexpected end"
        override val asmErrStrings get() = listOf(asmErrString, "length out of bounds")
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
        override val asmErrString get() = "invalid mutability"
    }

    class MultipleMemories : IoErr("Only single memory allowed") {
        override val asmErrString get() = "multiple memories"
    }

    class MultipleTables : IoErr("Only single table allowed") {
        override val asmErrString get() = "multiple tables"
    }

    class MemoryInitMaxMismatch(val init: Int, val max: Int) : IoErr("Memory init $init is over max $max") {
        override val asmErrString get() = "memory size minimum must not be greater than maximum"
    }

    class MemorySizeOverflow(val given: Long) : IoErr("Memory $given cannot exceed 65536 (4GiB)") {
        override val asmErrString get() = "memory size must be at most 65536 pages (4GiB)"
    }

    class InvalidAlign(val align: Int, val allowed: Int) : IoErr("Alignment $align larger than $allowed") {
        override val asmErrString get() = "alignment must not be larger than natural"
    }

    class InvalidResultArity : IoErr("Only single results supported") {
        override val asmErrString get() = "invalid result arity"
    }

    class UnknownType(val index: Int) : IoErr("No type present for index $index") {
        override val asmErrString get() = "unknown type"
    }
}