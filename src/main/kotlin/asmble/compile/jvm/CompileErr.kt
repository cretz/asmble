package asmble.compile.jvm

import java.util.*

sealed class CompileErr(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    abstract val asmErrString: String

    class StackMismatch(
        val expected: Array<out TypeRef>,
        val actual: TypeRef?
    ) : CompileErr("Expected any type of ${Arrays.toString(expected)}, got $actual") {
        override val asmErrString: String get() = "type mismatch"
    }

    class BlockEndMismatch(
        val expectedStack: List<TypeRef>,
        val possibleExtra: TypeRef?,
        val actualStack: List<TypeRef>
    ) : CompileErr(msgString(expectedStack, possibleExtra, actualStack)) {

        override val asmErrString: String get() = "type mismatch"

        companion object {
            fun msgString(expectedStack: List<TypeRef>, possibleExtra: TypeRef?, actualStack: List<TypeRef>) =
                if (possibleExtra == null) "At block end, expected stack $expectedStack, got $actualStack"
                else "At block end, expected stack $expectedStack and maybe $possibleExtra, got $actualStack"
        }
    }

    class UnusedStackOnReturn(
        val leftover: List<TypeRef>
    ) : CompileErr("Expected empty stack on return, still leftover with: $leftover") {
        override val asmErrString: String get() = "type mismatch"
    }
}