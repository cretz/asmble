package asmble.run.jvm.interpret

import asmble.ast.Node
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer

interface Imports {
    fun invokeFunction(module: String, field: String, type: Node.Type.Func, args: List<Number>): Number?
    fun getGlobal(module: String, field: String, type: Node.Type.Global): Number
    fun setGlobal(module: String, field: String, type: Node.Type.Global, value: Number)
    fun getMemory(module: String, field: String, type: Node.Type.Memory): ByteBuffer
    fun getTable(module: String, field: String, type: Node.Type.Table): Array<MethodHandle?>

    object None : Imports {
        override fun invokeFunction(
            module: String,
            field: String,
            type: Node.Type.Func,
            args: List<Number>
        ) = throw NotImplementedError("Import function $module.$field not implemented")

        override fun getGlobal(module: String, field: String, type: Node.Type.Global) =
            throw NotImplementedError("Import global $module.$field not implemented")

        override fun setGlobal(module: String, field: String, type: Node.Type.Global, value: Number) {
            throw NotImplementedError("Import global $module.$field not implemented")
        }

        override fun getMemory(module: String, field: String, type: Node.Type.Memory) =
            throw NotImplementedError("Import memory $module.$field not implemented")

        override fun getTable(module: String, field: String, type: Node.Type.Table) =
            throw NotImplementedError("Import table $module.$field not implemented")
    }
}