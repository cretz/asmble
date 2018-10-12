package asmble.run.jvm.interpret

import asmble.AsmErr
import asmble.ast.Node

sealed class InterpretErr(message: String, cause: Throwable? = null) : RuntimeException(message, cause), AsmErr {

    class IndirectCallTypeMismatch(
        val expected: Node.Type.Func,
        val actual: Node.Type.Func
    ) : InterpretErr("Expecting func type $expected, got $actual") {
        override val asmErrString get() = "indirect call type mismatch"
    }

    class InvalidCallResult(
        val expected: Node.Type.Value?,
        val actual: Number?
    ) : InterpretErr("Expected call result to be $expected, got $actual")

    class EndReached(returned: Number?) : InterpretErr("Reached end of invocation")

    class StartFuncParamMismatch(
        val expected: List<Node.Type.Value>,
        val actual: List<Node.Type.Value>
    ) : InterpretErr("Can't call start func, expected params $expected, got $actual")

    class OutOfBoundsMemory(
        val index: Int,
        val offset: Long
    ) : InterpretErr("Unable to access mem $index + offset $offset") {
        override val asmErrString get() = "out of bounds memory access"
    }

    class UndefinedElement(
        val index: Int
    ) : InterpretErr("No table element for index $index") {
        override val asmErrString get() = "undefined element"
    }
}