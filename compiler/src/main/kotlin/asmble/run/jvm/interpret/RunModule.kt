package asmble.run.jvm.interpret

import asmble.ast.Node
import asmble.compile.jvm.jclass
import asmble.run.jvm.Module
import asmble.run.jvm.ModuleBuilder
import asmble.util.Logger
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.ByteBuffer

class RunModule(
    override val name: String?,
    val ctx: Interpreter.Context
) : Module {
    override fun exportedFunc(field: String) =
        ctx.exportIndex(field, Node.ExternalKind.FUNCTION)?.let { ctx.boundFuncMethodHandleAtIndex(it) }

    override fun exportedGlobal(field: String) = ctx.exportIndex(field, Node.ExternalKind.GLOBAL)?.let { index ->
        val type = ctx.globalTypeAtIndex(index)
        val lookup = MethodHandles.lookup()
        var getter = lookup.bind(ctx, "getGlobal",
            MethodType.methodType(Number::class.java, Int::class.javaPrimitiveType))
        var setter = if (!type.mutable) null else lookup.bind(ctx, "setGlobal", MethodType.methodType(
            Void::class.javaPrimitiveType, Int::class.javaPrimitiveType, Number::class.java))
        // Cast number to specific type
        getter = MethodHandles.explicitCastArguments(getter,
            MethodType.methodType(type.contentType.jclass, Int::class.javaPrimitiveType))
        if (setter != null)
            setter = MethodHandles.explicitCastArguments(setter, MethodType.methodType(
                Void::class.javaPrimitiveType, Int::class.javaPrimitiveType, type.contentType.jclass))
        // Insert the index argument up front
        getter = MethodHandles.insertArguments(getter, 0, index)
        if (setter != null) setter = MethodHandles.insertArguments(setter, 0, index)
        getter to setter
    }

    @SuppressWarnings("UNCHECKED_CAST")
    override fun <T> exportedMemory(field: String, memClass: Class<T>) =
        ctx.exportIndex(field, Node.ExternalKind.MEMORY)?.let { index ->
            require(index == 0 && memClass == ByteBuffer::class.java)
            ctx.maybeMem as? T?
        }

    override fun exportedTable(field: String) =
        ctx.exportIndex(field, Node.ExternalKind.TABLE)?.let { index ->
            require(index == 0)
            ctx.table
        }

    class Builder(
        val logger: Logger = Logger.Print(Logger.Level.OFF),
        val defaultMaxMemPages: Int = 1,
        val memByteBufferDirect: Boolean = true
    ) : ModuleBuilder<RunModule> {
        override fun build(
            imports: Module.ImportResolver,
            mod: Node.Module,
            className: String,
            name: String?
        ) = RunModule(
            name = name,
            ctx = Interpreter.Context(
                mod = mod,
                logger = logger,
                imports = ResolverImports(imports),
                defaultMaxMemPages = defaultMaxMemPages,
                memByteBufferDirect = memByteBufferDirect
            ).also { ctx ->
                // Run start function if present
                mod.startFuncIndex?.also { Interpreter.execFunc(ctx, it) }
            }
        )
    }

    class ResolverImports(val res: Module.ImportResolver) : Imports {
        override fun invokeFunction(module: String, field: String, type: Node.Type.Func, args: List<Number>) =
            res.resolveImportFunc(module, field, type).invokeWithArguments(args) as Number?

        override fun getGlobal(module: String, field: String, type: Node.Type.Global) =
            res.resolveImportGlobal(module, field, type).first.invokeWithArguments() as Number

        override fun setGlobal(module: String, field: String, type: Node.Type.Global, value: Number) {
            res.resolveImportGlobal(module, field, type).second!!.invokeWithArguments(value)
        }

        override fun getMemory(module: String, field: String, type: Node.Type.Memory) =
            res.resolveImportMemory(module, field, type, ByteBuffer::class.java)

        override fun getTable(module: String, field: String, type: Node.Type.Table) =
            res.resolveImportTable(module, field, type)
    }
}