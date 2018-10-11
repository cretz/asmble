package asmble.run.jvm.interpret

import asmble.ast.Node

interface Imports {
    fun invokeFunction(module: String, field: String, args: List<Number>, expectedResult: Node.Type.Value?): Number?

    object None : Imports {
        override fun invokeFunction(
            module: String,
            field: String,
            args: List<Number>,
            expectedResult: Node.Type.Value?
        ) = TODO("Import $module.$field not implemented")
    }
}