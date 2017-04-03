package asmble.compile.jvm

import asmble.AsmErr
import java.util.*

sealed class CompileErr(message: String, cause: Throwable? = null) : RuntimeException(message, cause), AsmErr {

    class StackMismatch(
        val expected: Array<out TypeRef>,
        val actual: TypeRef?
    ) : CompileErr("Expected any type of ${Arrays.toString(expected)}, got $actual") {
        override val asmErrString get() = "type mismatch"
    }

    class StackInjectionMismatch(
        val injectBackwardsCount: Int,
        val attemptedInsn: Insn
    ) : CompileErr("Unable to inject $attemptedInsn back $injectBackwardsCount stack values") {
        override val asmErrString get() = "type mismatch"
    }

    class BlockEndMismatch(
        val expectedStack: List<TypeRef>,
        val possibleExtra: TypeRef?,
        val actualStack: List<TypeRef>
    ) : CompileErr(msgString(expectedStack, possibleExtra, actualStack)) {
        override val asmErrString get() = "type mismatch"

        companion object {
            fun msgString(expectedStack: List<TypeRef>, possibleExtra: TypeRef?, actualStack: List<TypeRef>) =
                if (possibleExtra == null) "At block end, expected stack $expectedStack, got $actualStack"
                else "At block end, expected stack $expectedStack and maybe $possibleExtra, got $actualStack"
        }
    }

    class SelectMismatch(
        val value1: TypeRef,
        val value2: TypeRef
    ) : CompileErr("Select values $value1 and $value2 are not the same type") {
        override val asmErrString get() = "type mismatch"
    }

    class IfThenValueWithoutElse() : CompileErr("If has value but no else clause") {
        override val asmErrString get() = "type mismatch"
    }

    class UnusedStackOnReturn(
        val leftover: List<TypeRef>
    ) : CompileErr("Expected empty stack on return, still leftover with: $leftover") {
        override val asmErrString get() = "type mismatch"
    }

    class NoBlockAtDepth(
        val attemptedDepth: Int
    ) : CompileErr("Attempted to access block at depth $attemptedDepth, but not there") {
        override val asmErrString get() = "unknown label"
    }

    class UnknownFunc(
        val index: Int
    ) : CompileErr("Unknown function at index $index") {
        override val asmErrString get() = "unknown function"
    }

    class UnknownLocal(
        val index: Int
    ) : CompileErr("Unknown local at index $index") {
        override val asmErrString get() = "unknown local"
    }

    class UnknownGlobal(
        val index: Int
    ) : CompileErr("Unknown global at index $index") {
        override val asmErrString get() = "unknown global"
    }
}