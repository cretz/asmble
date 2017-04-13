package asmble.run.jvm

import asmble.ast.Node
import asmble.ast.Script
import asmble.compile.jvm.*
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
import asmble.util.Logger
import asmble.util.toRawIntBits
import asmble.util.toRawLongBits
import java.io.PrintWriter
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
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
    val exceptionTranslator: ExceptionTranslator = ExceptionTranslator,
    val defaultMaxMemPages: Int = 1
) : Logger by logger {
    fun withHarnessRegistered(out: PrintWriter = PrintWriter(System.out, true)) =
        copy(registrations = registrations + (
            "spectest" to NativeModule(Harness::class.java, Harness(out))
        ))

    fun runCommand(cmd: Script.Cmd) = when (cmd) {
        is Script.Cmd.Module ->
            // We ask for the module instance because some things are built on <init> expectation
            compileModule(cmd.module, "Module${modules.size}", cmd.name).also { it.instance }.let {
                copy(modules = modules + it)
            }
        is Script.Cmd.Register ->
            copy(registrations = registrations + (
                cmd.string to (
                    (if (cmd.name != null) modules.find { it.name == cmd.name } else modules.lastOrNull()) ?:
                        error("No module to register")
                )
            ))
        is Script.Cmd.Action ->
            doAction(cmd).let { this }
        is Script.Cmd.Assertion ->
            doAssertion(cmd).let { this }
        else -> TODO("BOO: $cmd")
    }

    fun doAssertion(cmd: Script.Cmd.Assertion) {
        debug { "Performing assertion: " + SExprToStr.fromSExpr(AstToSExpr.fromAssertion(cmd)) }
        when (cmd) {
            is Script.Cmd.Assertion.Return -> assertReturn(cmd)
            is Script.Cmd.Assertion.ReturnNan -> assertReturnNan(cmd)
            is Script.Cmd.Assertion.Trap -> assertTrap(cmd)
            is Script.Cmd.Assertion.Malformed -> assertMalformed(cmd)
            is Script.Cmd.Assertion.Invalid -> assertInvalid(cmd)
            is Script.Cmd.Assertion.Unlinkable -> assertUnlinkable(cmd)
            is Script.Cmd.Assertion.TrapModule -> assertTrapModule(cmd)
            is Script.Cmd.Assertion.Exhaustion -> assertExhaustion(cmd)
            else -> TODO("Assertion misssing: $cmd")
        }
    }

    fun assertReturn(ret: Script.Cmd.Assertion.Return) {
        require(ret.exprs.size < 2)
        val (retType, retVal) = doAction(ret.action)
        when (retType) {
            null ->
                if (ret.exprs.isNotEmpty())
                    throw ScriptAssertionError(ret, "Got empty return, expected not empty", retVal)
            else -> {
                if (ret.exprs.isEmpty()) throw ScriptAssertionError(ret, "Got return, expected empty", retVal)
                val expectedVal = runExpr(ret.exprs.first(), retType)
                if (expectedVal is Float && expectedVal.isNaN() && retVal is Float && retVal.isNaN()) {
                    if (expectedVal.toRawIntBits() != retVal.toRawIntBits())
                        throw ScriptAssertionError(
                            ret,
                            "Mismatch NaN bits, got ${retVal.toRawIntBits().toString(16)}, " +
                                "expected ${expectedVal.toRawIntBits().toString(16)}",
                            retVal,
                            expectedVal
                        )
                } else if (expectedVal is Double && expectedVal.isNaN() && retVal is Double && retVal.isNaN()) {
                    if (expectedVal.toRawLongBits() != retVal.toRawLongBits())
                        throw ScriptAssertionError(
                            ret,
                            "Mismatch NaN bits, got ${retVal.toRawLongBits().toString(16)}, " +
                                "expected ${expectedVal.toRawLongBits().toString(16)}",
                            retVal,
                            expectedVal
                        )
                } else if (retVal != expectedVal)
                    throw ScriptAssertionError(ret, "Expected $expectedVal, got $retVal", retVal, expectedVal)
            }
        }
    }

    fun assertReturnNan(ret: Script.Cmd.Assertion.ReturnNan) {
        // TODO: validate canonical vs arithmetic
        val (retType, retVal) = doAction(ret.action)
        when (retType) {
            Node.Type.Value.F32 ->
                if (!(retVal as Float).isNaN()) throw ScriptAssertionError(ret, "Expected NaN, got $retVal", retVal)
            Node.Type.Value.F64 ->
                if (!(retVal as Double).isNaN()) throw ScriptAssertionError(ret, "Expected NaN, got $retVal", retVal)
            else ->
                throw ScriptAssertionError(ret, "Expected NaN, got $retVal", retVal)
        }
    }

    fun assertTrap(trap: Script.Cmd.Assertion.Trap) {
        try {
            doAction(trap.action).also {
                throw ScriptAssertionError(trap, "Expected exception but completed successfully")
            }
        } catch (e: Throwable) { assertFailure(trap, e, trap.failure) }
    }

    fun assertMalformed(malformed: Script.Cmd.Assertion.Malformed) {
        try {
            debug { "Compiling malformed: " + SExprToStr.Compact.fromSExpr(AstToSExpr.fromModule(malformed.module.value)) }
            val className = "malformed" + UUID.randomUUID().toString().replace("-", "")
            compileModule(malformed.module.value, className, null)
            throw ScriptAssertionError(
                malformed,
                "Expected malformed module with error '${malformed.failure}', was valid"
            )
        } catch (e: Exception) { assertFailure(malformed, e, malformed.failure) }
    }

    fun assertInvalid(invalid: Script.Cmd.Assertion.Invalid) {
        try {
            debug { "Compiling invalid: " + SExprToStr.Compact.fromSExpr(AstToSExpr.fromModule(invalid.module.value)) }
            val className = "invalid" + UUID.randomUUID().toString().replace("-", "")
            compileModule(invalid.module.value, className, null)
            throw ScriptAssertionError(invalid, "Expected invalid module with error '${invalid.failure}', was valid")
        } catch (e: Exception) { assertFailure(invalid, e, invalid.failure) }
    }

    fun assertUnlinkable(unlink: Script.Cmd.Assertion.Unlinkable) {
        try {
            val className = "unlinkable" + UUID.randomUUID().toString().replace("-", "")
            compileModule(unlink.module, className, null).instance
            throw ScriptAssertionError(unlink, "Expected module link error with '${unlink.failure}', was valid")
        } catch (e: Throwable) { assertFailure(unlink, e, unlink.failure) }
    }

    fun assertTrapModule(trap: Script.Cmd.Assertion.TrapModule) {
        try {
            val className = "trapmod" + UUID.randomUUID().toString().replace("-", "")
            compileModule(trap.module, className, null).instance
            throw ScriptAssertionError(trap, "Expected module init error with '${trap.failure}', was valid")
        } catch (e: Throwable) { assertFailure(trap, e, trap.failure) }
    }

    fun assertExhaustion(exhaustion: Script.Cmd.Assertion.Exhaustion) {
        try { doAction(exhaustion.action).also { throw ScriptAssertionError(exhaustion, "Expected exception") } }
        catch (e: Throwable) { assertFailure(exhaustion, e, exhaustion.failure) }
    }

    private fun exceptionFromCatch(e: Throwable) =
        e as? ScriptAssertionError ?: (e as? InvocationTargetException)?.targetException ?: e

    private fun assertFailure(a: Script.Cmd.Assertion, e: Throwable, expectedString: String) {
        val innerEx = exceptionFromCatch(e)
        val msgs = exceptionTranslator.translate(innerEx)
        if (!msgs.any { it.contains(expectedString) })
            throw ScriptAssertionError(a, "Expected failure '$expectedString' in $msgs", cause = innerEx)
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
        return CompiledModule(mod, classLoader.fromBuiltContext(ctx), name, ctx.mem)
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

    fun resolveImportGlobal(import: Node.Import, globalType: Node.Type.Global): MethodHandle {
        // Find a getter that matches the name
        val module = registrations[import.module] ?: error("Unable to find module ${import.module}")
        return MethodHandles.lookup().bind(
            module.instance,
            "get" + import.field.javaIdent.capitalize(),
            MethodType.methodType(globalType.contentType.jclass)
        )
    }

    fun resolveImportMemory(import: Node.Import, memoryType: Node.Type.Memory, mem: Mem): Any {
        // Find a getter that matches the name
        val module = registrations[import.module] ?: error("Unable to find module ${import.module}")
        val getter = module.instance.javaClass.getDeclaredMethod("get" + import.field.javaIdent.capitalize())
        val memInst = getter.invoke(module.instance)
        mem.assertValidImport(memInst, memoryType)
        return memInst
    }

    fun resolveImportTable(import: Node.Import, tableType: Node.Type.Table): Any {
        // Find a getter that matches the name
        val module = registrations[import.module] ?: error("Unable to find module ${import.module}")
        val getter = module.instance.javaClass.getDeclaredMethod("get" + import.field.javaIdent.capitalize())
        require(getter.returnType == Array<MethodHandle>::class.java)
        return getter.invoke(module.instance)
    }

    interface Module {
        val cls: Class<*>
        val instance: Any
    }

    class NativeModule(override val cls: Class<*>, override val instance: Any) : Module

    inner class CompiledModule(
        val mod: Node.Module,
        override val cls: Class<*>,
        val name: String?,
        val mem: Mem
    ) : Module {
        override val instance by lazy {
            // Find the constructor
            var constructorParams = emptyList<Any>()
            var constructor: Constructor<*>?
            // If there is a memory import, we have to get the one with the mem class as the first
            val memImport = mod.imports.find { it.kind is Node.Import.Kind.Memory }
            if (memImport != null) {
                constructor = cls.declaredConstructors.find { it.parameterTypes.firstOrNull()?.ref == mem.memType }
                constructorParams += resolveImportMemory(memImport, memImport.kind as Node.Type.Memory, mem)
            } else {
                // Find the constructor with no max mem amount (i.e. methodhandle or nothing as first param)
                constructor = cls.declaredConstructors.find {
                    when (it.parameterTypes.firstOrNull()) {
                        null, MethodHandle::class.java -> true
                        else -> false
                    }
                }
                // If it is not there, find the one w/ the max mem amount
                if (constructor == null) {
                    val maxMem = Math.max(mod.memories.firstOrNull()?.limits?.initial ?: 0, defaultMaxMemPages)
                    constructor = cls.declaredConstructors.find { it.parameterTypes.firstOrNull() == Int::class.java }
                    constructorParams += maxMem * Mem.PAGE_SIZE
                }
            }
            if (constructor == null) error("Unable to find suitable module constructor")

            // Function imports
            constructorParams += mod.imports.mapNotNull {
                if (it.kind is Node.Import.Kind.Func) resolveImportFunc(it, mod.types[it.kind.typeIndex])
                else null
            }

            // Global imports
            constructorParams += mod.imports.mapNotNull {
                if (it.kind is Node.Import.Kind.Global) resolveImportGlobal(it, it.kind.type)
                else null
            }

            // Table imports
            constructorParams += mod.imports.mapNotNull {
                if (it.kind is Node.Import.Kind.Table) resolveImportTable(it, it.kind.type)
                else null
            }

            // Construct
            debug { "Instantiating $cls using $constructor with params $constructorParams" }
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