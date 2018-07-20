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
        // Since we test section length before section content, we get a different error than the spec
        override val asmErrStrings get() = listOf(asmErrString, "invalid mutability")
    }

    class InvalidCodeLength(funcLen: Int, codeLen: Int) : IoErr("Got $funcLen funcs but only $codeLen bodies") {
        override val asmErrString get() = "function and code section have inconsistent lengths"
    }

    class InvalidMutability : IoErr("Invalid mutability boolean") {
        override val asmErrString get() = "invalid mutability"
    }

    class InvalidReservedArg : IoErr("Invalid reserved arg") {
        override val asmErrString get() = "zero flag expected"
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

    class InvalidAlignPower(val align: Int) : IoErr("Alignment expected to be positive power of 2, but got $align") {
        override val asmErrString get() = "alignment must be positive power of 2"
    }

    class InvalidAlignTooLarge(val align: Int, val allowed: Int) : IoErr("Alignment $align larger than $allowed") {
        override val asmErrString get() = "alignment must not be larger than natural"
    }

    class InvalidResultArity : IoErr("Only single results supported") {
        override val asmErrString get() = "invalid result arity"
    }

    class UnknownType(val index: Int) : IoErr("No type present for index $index") {
        override val asmErrString get() = "unknown type"
    }

    class InvalidType(val str: String) : IoErr("Invalid type: $str") {
        override val asmErrString get() = "unexpected token"
    }

    class MismatchLabelEnd(val expected: String?, val actual: String) :
            IoErr("Expected end for $expected, got $actual") {
        override val asmErrString get() = "mismatching label"
    }

    class ConstantOutOfRange(val actual: Number) : IoErr("Constant out of range: $actual") {
        override val asmErrString get() = "constant out of range"
    }

    class ConstantUnknownOperator(val str: String) : IoErr("Unknown constant operator for: $str") {
        override val asmErrString get() = "unknown operator"
    }

    class FuncTypeRefMismatch : IoErr("Func type for type ref doesn't match explicit params/returns") {
        override val asmErrString get() = "inline function type"
        override val asmErrStrings get() = listOf(asmErrString, "unexpected token")
    }

    class UnrecognizedInstruction(val instr: String) : IoErr("Unrecognized instruction: $instr") {
        override val asmErrString get() = "unexpected token"
        override val asmErrStrings get() = listOf(asmErrString, "unknown operator")
    }

    class ImportAfterNonImport(val nonImportType: String) : IoErr("Import happened after $nonImportType") {
        override val asmErrString get() = "import after $nonImportType"
    }

    class UnknownOperator : IoErr("Unknown operator") {
        override val asmErrString get() = "unknown operator"
        override val asmErrStrings get() = listOf(asmErrString, "unexpected token")
    }

    class InvalidVar(val found: String) : IoErr("Var ref expected, found: $found") {
        override val asmErrString get() = "unknown operator"
    }

    class ResultBeforeParameter : IoErr("Function result before parameter") {
        override val asmErrString get() = "result before parameter"
        override val asmErrStrings get() = listOf(asmErrString, "unexpected token")
    }

    class IndirectCallSetParamNames : IoErr("Indirect call tried to set name to param in func type") {
        override val asmErrString get() = "unexpected token"
    }

    class InvalidUtf8Encoding : IoErr("Some byte sequence was not UTF-8 compatible") {
        override val asmErrString get() = "invalid UTF-8 encoding"
    }

    class InvalidLeb128Number : IoErr("Invalid LEB128 number") {
        override val asmErrString get() = "integer representation too long"
        override val asmErrStrings get() = listOf(asmErrString, "integer too large")
    }

    class InvalidLocalSize(cause: NumberFormatException) : IoErr("Invalid local size", cause) {
        override val asmErrString get() = "too many locals"
    }
}