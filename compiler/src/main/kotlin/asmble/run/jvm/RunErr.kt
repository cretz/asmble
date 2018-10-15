package asmble.run.jvm

import asmble.AsmErr
import asmble.ast.Node

sealed class RunErr(message: String, cause: Throwable? = null) : RuntimeException(message, cause), AsmErr {

    class ImportMemoryLimitTooSmall(
        val expected: Int,
        val actual: Int
    ) : RunErr("Import memory limit $actual but expecting at least $expected") {
        override val asmErrString get() = "incompatible import type"
    }

    class ImportMemoryCapacityTooLarge(
        val expected: Int,
        val actual: Int
    ) : RunErr("Import table capacity $actual but expecting no more than $expected") {
        override val asmErrString get() = "incompatible import type"
    }

    class InvalidDataIndex(
        val index: Int,
        val dataSize: Int,
        val memSize: Int
    ) : RunErr("Trying to set $dataSize bytes at index $index but mem limit is only $memSize") {
        override val asmErrString get() = "data segment does not fit"
    }

    class ImportTableTooSmall(
        val expected: Int,
        val actual: Int
    ) : RunErr("Import table sized $actual but expecting at least $expected") {
        override val asmErrString get() = "incompatible import type"
    }

    class ImportTableTooLarge(
        val expected: Int,
        val actual: Int
    ) : RunErr("Import table sized $actual but expecting no more than $expected") {
        override val asmErrString get() = "incompatible import type"
    }

    class InvalidElemIndex(
        val offset: Int,
        val elemSize: Int,
        val tableSize: Int
    ) : RunErr("Trying to set $elemSize elems at offset $offset but table size is only $tableSize") {
        override val asmErrString get() = "elements segment does not fit"
    }

    class ImportNotFound(
        val module: String,
        val field: String
    ) : RunErr("Cannot find import for $module::$field") {
        override val asmErrString get() = "unknown import"
        override val asmErrStrings get() = listOf(asmErrString, "incompatible import type")
    }

    class ImportIncompatible(
        val module: String,
        val field: String,
        val expected: Node.Type,
        val actual: Node.Type
    ) : RunErr("Import $module::$field expected type $expected, got $actual") {
        override val asmErrString get() = "incompatible import type"
    }
}