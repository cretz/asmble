package asmble.run.jvm

import asmble.ast.Node
import asmble.ast.Script
import asmble.compile.jvm.*
import asmble.util.Logger
import java.io.PrintWriter
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationTargetException
import java.util.*

data class ScriptContext(
    val packageName: String,
    val modules: List<CompiledModule> = emptyList(),
    val registrations: Map<String, Module> = emptyMap(),
    val logger: Logger = Logger.Print(Logger.Level.OFF),
    val adjustContext: (ClsContext) -> ClsContext = { it },
    val classLoader: SimpleClassLoader = ScriptContext.SimpleClassLoader(ScriptContext::class.java.classLoader),
    val exceptionTranslator: ExceptionTranslator = ExceptionTranslator
) {
    fun withHarnessRegistered(out: PrintWriter = PrintWriter(System.out, true)) =
        copy(registrations = registrations + (
            "spectest" to NativeModule(Harness::class.java, Harness(out))
        ))

    fun runCommand(cmd: Script.Cmd) = when (cmd) {
        is Script.Cmd.Module -> copy(modules = modules + compileModule(cmd.module, "Module${modules.size}", cmd.name))
        is Script.Cmd.Register -> copy(registrations = registrations + (
            cmd.string to (modules.lastOrNull() ?: error("No module to register"))
        ))
        is Script.Cmd.Action -> doAction(cmd).let { this }
        is Script.Cmd.Assertion -> doAssertion(cmd).let { this }
        else -> TODO("BOO: $cmd")
    }

    fun doAssertion(cmd: Script.Cmd.Assertion) {
        when (cmd) {
            is Script.Cmd.Assertion.Trap -> assertTrap(cmd)
            else -> TODO()
        }
    }

    fun assertTrap(trap: Script.Cmd.Assertion.Trap) {
        try { doAction(trap.action).also { throw AssertionError("Expected exception") } }
        catch (e: Throwable) {
            val innerEx = if (e is InvocationTargetException) e.targetException else e
            exceptionTranslator.translateOrRethrow(innerEx).let {
                if (it != trap.failure) throw AssertionError("Expected failure '${trap.failure}' got '$it'")
            }
        }
    }

    fun doAction(cmd: Script.Cmd.Action) = when (cmd) {
        is Script.Cmd.Action.Invoke -> doInvoke(cmd)
        is Script.Cmd.Action.Get -> doGet(cmd)
    }

    fun doGet(cmd: Script.Cmd.Action.Get): Pair<Node.Type.Value, Any> {
        TODO()
    }

    fun doInvoke(cmd: Script.Cmd.Action.Invoke): Pair<Node.Type.Value?, Any?> {
        // If there is a module name, use that index, otherwise just search
        val (compMod, method) = modules.filter { cmd.name == null || it.name == cmd.name }.flatMap { compMod ->
            compMod.cls.declaredMethods.filter { it.name == cmd.string }.map { compMod to it }
        }.singleOrNull() ?: error("Unable to find single func for $cmd")

        // Invoke all parameter expressions
        require(cmd.exprs.size == method.parameterTypes.size)
        val params = cmd.exprs.zip(method.parameterTypes).map { (expr, paramType) ->
            runExpr(expr, paramType.valueType!!)
        }

        // Run returning the result
        return method.returnType.valueType to method.invoke(compMod.instance, *params.toTypedArray())
    }

    fun runExpr(insns: List<Node.Instr>) {
        MethodHandleUtil.invokeVoid(compileExpr(insns, null))
    }

    fun runExpr(insns: List<Node.Instr>, retType: Node.Type.Value) = compileExpr(insns, retType).let { handle ->
        when (retType) {
            is Node.Type.Value.I32 -> MethodHandleUtil.invokeInt(handle)
            is Node.Type.Value.I64 -> MethodHandleUtil.invokeLong(handle)
            is Node.Type.Value.F32 -> MethodHandleUtil.invokeFloat(handle)
            is Node.Type.Value.F64 -> MethodHandleUtil.invokeDouble(handle)
        }
    }

    fun compileExpr(insns: List<Node.Instr>, retType: Node.Type.Value?): MethodHandle {
        val mod = Node.Module(
            exports = listOf(Node.Export("expr", Node.ExternalKind.FUNCTION, 0)),
            funcs = listOf(Node.Func(
                type = Node.Type.Func(emptyList(), retType),
                locals = emptyList(),
                instructions = insns
            ))
        )
        val name = "expr" + UUID.randomUUID().toString().replace("-", "")
        val compiled = compileModule(mod, name, null)
        return MethodHandles.lookup().bind(compiled.instance, "expr",
            MethodType.methodType(retType?.jclass ?: Void.TYPE))
    }

    fun compileModule(mod: Node.Module, className: String, name: String?): CompiledModule {
        val ctx = ClsContext(
            packageName = packageName,
            className = className,
            mod = mod,
            logger = logger
        ).let(adjustContext)
        AstToAsm.fromModule(ctx)
        return CompiledModule(mod, classLoader.fromBuiltContext(ctx), name)
    }

    fun resolveImportFunc(import: Node.Import, funcType: Node.Type.Func): MethodHandle {
        // Find a method that matches our expectations
        val module = registrations[import.module] ?: error("Unable to find module ${import.module}")
        return MethodHandles.lookup().bind(
            module.instance,
            import.field,
            MethodType.methodType(funcType.ret?.jclass ?: Void.TYPE, funcType.params.map { it.jclass })
        )
    }

    interface Module {
        val cls: Class<*>
        val instance: Any
    }

    class NativeModule(override val cls: Class<*>, override val instance: Any) : Module

    inner class CompiledModule(val mod: Node.Module, override val cls: Class<*>, val name: String?) : Module {
        override val instance by lazy {
            // Find the constructor with no max mem amount (i.e. methodhandle or nothing as first param)
            var constructor = cls.declaredConstructors.find {
                when (it.parameterTypes.firstOrNull()) {
                    null, MethodHandle::class.java -> true
                    else -> false
                }
            }
            var constructorParams = emptyList<Any>()
            // If it is not there, find the one w/ the max mem amount
            if (constructor == null) {
                constructorParams += (mod.memories.firstOrNull()?.limits?.initial ?: 0) * Mem.PAGE_SIZE
                constructor = cls.declaredConstructors.find {
                    it.parameterTypes.firstOrNull() == Int::class.java
                } ?: error("Unable to find no-arg or mem-accepting construtor")
            }
            // Now resolve the imports
            constructorParams += mod.imports.map {
                val kind = it.kind
                when (kind) {
                    is Node.Import.Kind.Func -> resolveImportFunc(it, mod.types[kind.typeIndex])
                    else -> TODO()
                }
            }
            // Construct
            constructor!!.newInstance(*constructorParams.toTypedArray())
        }
    }

    open class SimpleClassLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun fromBuiltContext(ctx: ClsContext) = ctx.cls.withComputedFramesAndMaxs().let { bytes ->
            ctx.debug { "ASM Class:\n" + bytes.asClassNode().toAsmString() }
            defineClass("${ctx.packageName}.${ctx.className}",  bytes, 0, bytes.size)
        }
    }
}