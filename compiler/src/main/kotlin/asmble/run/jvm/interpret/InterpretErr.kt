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

    class InvalidImportFuncResult(
        val expected: Node.Type.Value?,
        val actual: Number?
    ) : InterpretErr("Expected import result to be $expected, got $actual")

    class EndReached(returned: Number?) : InterpretErr("Reached end of invocation")

    class StartFuncParamMismatch(
        val expected: List<Node.Type.Value>,
        val actual: List<Node.Type.Value>
    ) : InterpretErr("Can't call start func, expected params $expected, got $actual")
}