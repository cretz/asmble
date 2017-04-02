package asmble.run.jvm

import asmble.ast.Node
import asmble.ast.Script
import asmble.compile.jvm.*
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
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
    val classLoader: SimpleClassLoader =
        ScriptContext.SimpleClassLoader(ScriptContext::class.java.classLoader, logger),
    val exceptionTranslator: ExceptionTranslator = ExceptionTranslator
) : Logger by logger {
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
        debug { "Performing assertion: " + SExprToStr.fromSExpr(AstToSExpr.fromAssertion(cmd)) }
        when (cmd) {
            is Script.Cmd.Assertion.Return -> assertReturn(cmd)
            is Script.Cmd.Assertion.ReturnNan -> assertReturnNan(cmd)
            is Script.Cmd.Assertion.Trap -> assertTrap(cmd)
            is Script.Cmd.Assertion.Invalid -> assertInvalid(cmd)
            is Script.Cmd.Assertion.Exhaustion -> assertExhaustion(cmd)
            else -> TODO()
        }
    }

    fun assertReturn(ret: Script.Cmd.Assertion.Return) {
        require(ret.exprs.size < 2)
        val (retType, retVal) = doAction(ret.action)
        when (retType) {
            null -> if (ret.exprs.isNotEmpty()) throw AssertionError("Got empty return, expected not empty")
            else -> {
                if (ret.exprs.isEmpty()) throw AssertionError("Got return, expected empty")
                val expectedVal = runExpr(ret.exprs.first(), retType)
                if (expectedVal is Float && expectedVal.isNaN() && retVal is Float) {
                    java.lang.Float.floatToRawIntBits(expectedVal).let { expectedBits ->
                        java.lang.Float.floatToRawIntBits(retVal).let { actualBits ->
                            if (expectedBits != actualBits) throw AssertionError(
                                "Expected NaN ${java.lang.Integer.toHexString(expectedBits)}, " +
                                    "got ${java.lang.Integer.toHexString(actualBits)}"
                            )
                        }
                    }
                } else if (expectedVal is Double && expectedVal.isNaN() && retVal is Double) {
                    java.lang.Double.doubleToRawLongBits(expectedVal).let { expectedBits ->
                        java.lang.Double.doubleToRawLongBits(retVal).let { actualBits ->
                            if (expectedBits != actualBits) throw AssertionError(
                                "Expected NaN ${java.lang.Long.toHexString(expectedBits)}, " +
                                    "got ${java.lang.Long.toHexString(actualBits)}"
                            )
                        }
                    }
                } else if (retVal != expectedVal) throw AssertionError("Expected $expectedVal, got $retVal")
            }
        }
    }

    fun assertReturnNan(ret: Script.Cmd.Assertion.ReturnNan) {
        val (retType, retVal) = doAction(ret.action)
        when (retType) {
            Node.Type.Value.F32 -> if (!(retVal as Float).isNaN()) throw AssertionError("Expected NaN, got $retVal")
            Node.Type.Value.F64 -> if (!(retVal as Double).isNaN()) throw AssertionError("Expected NaN, got $retVal")
            else -> throw AssertionError("Expected NaN, got $retVal")
        }
    }

    fun assertTrap(trap: Script.Cmd.Assertion.Trap) {
        try { doAction(trap.action).also { throw AssertionError("Expected exception but completed successfully") } }
        catch (e: Throwable) { assertFailure(e, trap.failure) }
    }

    fun assertInvalid(invalid: Script.Cmd.Assertion.Invalid) {
        try {
            debug { "Compiling invalid: " + SExprToStr.Compact.fromSExpr(AstToSExpr.fromModule(invalid.module.value)) }
            val className = "invalid" + UUID.randomUUID().toString().replace("-", "")
            compileModule(invalid.module.value, className, null)
            throw AssertionError("Expected invalid module with error '${invalid.failure}', was valid")
        } catch (e: Exception) { assertFailure(e, invalid.failure) }
    }

    fun assertExhaustion(exhaustion: Script.Cmd.Assertion.Exhaustion) {
        try { doAction(exhaustion.action).also { throw AssertionError("Expected exception") } }
        catch (e: Throwable) { assertFailure(e, exhaustion.failure) }
    }

    private fun exceptionFromCatch(e: Throwable) =
        e as? AssertionError ?: (e as? InvocationTargetException)?.targetException ?: e

    private fun assertFailure(e: Throwable, expectedString: String) {
        val innerEx = exceptionFromCatch(e)
        val msg = exceptionTranslator.translate(innerEx) ?: "<unrecognized error>"
        if (msg != expectedString) throw AssertionError("Expected failure '$expectedString' got '$msg'", innerEx)
    }

    fun doAction(cmd: Script.Cmd.Action) = when (cmd) {
        is Script.Cmd.Action.Invoke -> doInvoke(cmd)
        is Script.Cmd.Action.Get -> doGet(cmd)
    }

    fun doGet(cmd: Script.Cmd.Action.Get): Pair<Node.Type.Value, Any> {
        TODO()
    }

    fun doInvoke(cmd: Script.Cmd.Action.Invoke): Pair<Node.Type.Value?, Any?> {
        // If there is a module name, use that index, otherwise just search.
        val (compMod, method) = modules.filter { cmd.name == null || it.name == cmd.name }.flatMap { compMod ->
            compMod.cls.declaredMethods.filter { it.name == cmd.string.javaIdent }.map { compMod to it }
        }.let { methodPairs ->
            // If there are multiple, we get the last one
            if (methodPairs.isEmpty()) error("Unable to find method for invoke named ${cmd.string.javaIdent}")
            else if (methodPairs.size == 1) methodPairs.single()
            else methodPairs.last().also { debug { "Found multiple methods for ${cmd.string.javaIdent}, using last"} }
        }

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
        debug { "Compiling expression: $insns" }
        val mod = Node.Module(
            exports = listOf(Node.Export("expr", Node.ExternalKind.FUNCTION, 0)),
            funcs = listOf(Node.Func(
                type = Node.Type.Func(emptyList(), retType),
                locals = emptyList(),
                instructions = insns
            ))
        )
        val className = "expr" + UUID.randomUUID().toString().replace("-", "")
        val compiled = compileModule(mod, className, null)
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
            import.field.javaIdent,
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
            // TODO: genericize which constructor is the no-mem one
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

    open class SimpleClassLoader(parent: ClassLoader, logger: Logger) : ClassLoader(parent), Logger by logger {
        fun fromBuiltContext(ctx: ClsContext): Class<*> {
            trace { "Computing frames for ASM class:\n" + ctx.cls.toAsmString() }
            return ctx.cls.withComputedFramesAndMaxs().let { bytes ->
                debug { "ASM class:\n" + bytes.asClassNode().toAsmString() }
                defineClass("${ctx.packageName}.${ctx.className}",  bytes, 0, bytes.size)
            }
        }
    }
}