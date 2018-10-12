package asmble.run.jvm

import asmble.ast.Node
import asmble.ast.Script
import asmble.compile.jvm.valueType
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
import asmble.util.Logger
import asmble.util.toRawIntBits
import asmble.util.toRawLongBits
import java.io.PrintWriter
import java.lang.invoke.MethodHandle
import java.lang.reflect.InvocationTargetException
import java.util.*

data class ScriptContext(
    val modules: List<Module> = emptyList(),
    val registrations: Map<String, Module> = emptyMap(),
    val logger: Logger = Logger.Print(Logger.Level.OFF),
    val exceptionTranslator: ExceptionTranslator = ExceptionTranslator,
    val builder: ModuleBuilder<*> = ModuleBuilder.Compiled(logger = logger),
    val assertionExclusionFilter: (Script.Cmd.Assertion) -> Boolean = { false }
) : Module.ImportResolver, Logger by logger {
    fun withHarnessRegistered(out: PrintWriter = PrintWriter(System.out, true)) =
        withModuleRegistered(Module.Native("spectest", TestHarness(out)))

    fun withModuleRegistered(mod: Module) =
        copy(registrations = registrations + ((mod.name ?: error("Missing module name")) to mod))

    fun runCommand(cmd: Script.Cmd) = when (cmd) {
        is Script.Cmd.Module ->
            copy(modules = modules + buildModule(cmd.module, "Module${modules.size}", cmd.name))
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
        is Script.Cmd.Meta -> throw NotImplementedError("Meta commands cannot be run")
    }

    fun doAssertion(cmd: Script.Cmd.Assertion) {
        if (assertionExclusionFilter(cmd)) {
            debug { "Ignoring assertion: " + SExprToStr.fromSExpr(AstToSExpr.fromAssertion(cmd)) }
            return
        }
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
            else -> TODO("Assertion missing: $cmd")
        }
    }

    fun assertReturn(ret: Script.Cmd.Assertion.Return) {
        require(ret.exprs.size < 2)
        val retVal = doAction(ret.action)
        val retType = retVal?.javaClass?.valueType
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
        val retVal = doAction(ret.action)
        when (retVal?.javaClass?.valueType) {
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
            buildModule(malformed.module.value, className, null)
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
            buildModule(invalid.module.value, className, null)
            throw ScriptAssertionError(invalid, "Expected invalid module with error '${invalid.failure}', was valid")
        } catch (e: Exception) { assertFailure(invalid, e, invalid.failure) }
    }

    fun assertUnlinkable(unlink: Script.Cmd.Assertion.Unlinkable) {
        try {
            val className = "unlinkable" + UUID.randomUUID().toString().replace("-", "")
            buildModule(unlink.module, className, null)
            throw ScriptAssertionError(unlink, "Expected module link error with '${unlink.failure}', was valid")
        } catch (e: Throwable) { assertFailure(unlink, e, unlink.failure) }
    }

    fun assertTrapModule(trap: Script.Cmd.Assertion.TrapModule) {
        try {
            val className = "trapmod" + UUID.randomUUID().toString().replace("-", "")
            buildModule(trap.module, className, null)
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
        if (innerEx is ScriptAssertionError) throw innerEx
        val msgs = exceptionTranslator.translate(innerEx)
        if (msgs.isEmpty())
            throw ScriptAssertionError(a, "Expected failure '$expectedString' but got unknown err", cause = innerEx)
        var msgToFind = expectedString
        // Special case for "uninitialized element" error match. This is because the error is expected to
        //  be "uninitialized number #" where # is the indirect call number. But it is at runtime where this fails
        //  so it is not worth it for us to store the index of failure. So we generalize it.
        if (msgToFind.startsWith("uninitialized element")) msgToFind = "uninitialized element"
        if (!msgs.any { it.contains(msgToFind) })
            throw ScriptAssertionError(a, "Expected failure '$expectedString' in $msgs", cause = innerEx)
    }

    fun doAction(cmd: Script.Cmd.Action) = when (cmd) {
        is Script.Cmd.Action.Invoke -> doInvoke(cmd)
        is Script.Cmd.Action.Get -> doGet(cmd)
    }

    fun doGet(cmd: Script.Cmd.Action.Get): Number {
        // Grab last module or named one
        val module = if (cmd.name == null) modules.last() else modules.first { it.name == cmd.name }
        return module.exportedGlobal(cmd.string)!!.first.invokeWithArguments() as Number
    }

    fun doInvoke(cmd: Script.Cmd.Action.Invoke): Number? {
        // If there is a module name, use that index, otherwise just search.
        val module = if (cmd.name == null) modules.last() else modules.first { it.name == cmd.name }
        // Invoke all parameter expressions
        val mh = module.exportedFunc(cmd.string)!!
        val paramTypes = mh.type().parameterList()
        require(cmd.exprs.size == paramTypes.size)
        val params = cmd.exprs.zip(paramTypes).map { (expr, paramType) -> runExpr(expr, paramType.valueType!!)!! }
        // Run returning the result
        return mh.invokeWithArguments(*params.toTypedArray()) as Number?
    }

    fun runExpr(insns: List<Node.Instr>, retType: Node.Type.Value?) =
        buildExpr(insns, retType).exportedFunc("expr")!!.invokeWithArguments() as Number?

    fun buildExpr(insns: List<Node.Instr>, retType: Node.Type.Value?): Module {
        debug { "Building expression: $insns" }
        val mod = Node.Module(
            exports = listOf(Node.Export("expr", Node.ExternalKind.FUNCTION, 0)),
            funcs = listOf(Node.Func(
                type = Node.Type.Func(emptyList(), retType),
                locals = emptyList(),
                instructions = insns
            ))
        )
        return buildModule(mod, "expr" + UUID.randomUUID().toString().replace("-", ""), null)
    }

    fun withBuiltModule(mod: Node.Module, className: String, name: String?) =
        copy(modules = modules + buildModule(mod, className, name))

    fun buildModule(mod: Node.Module, className: String, name: String?) = builder.build(this, mod, className, name)

    override fun resolveImportFunc(module: String, field: String, type: Node.Type.Func): MethodHandle {
        val hnd = registrations[module]?.exportedFunc(field) ?: throw RunErr.ImportNotFound(module, field)
        val hndType = Node.Type.Func(
            params = hnd.type().parameterList().map { it.valueType!! },
            ret = hnd.type().returnType().valueType
        )
        if (hndType != type) throw RunErr.ImportIncompatible(module, field, type, hndType)
        return hnd
    }

    override fun resolveImportGlobal(
        module: String,
        field: String,
        type: Node.Type.Global
    ): Pair<MethodHandle, MethodHandle?> {
        val hnd = registrations[module]?.exportedGlobal(field) ?: throw RunErr.ImportNotFound(module, field)
        if (!hnd.first.type().returnType().isPrimitive) throw RunErr.ImportNotFound(module, field)
        val hndType = Node.Type.Global(
            contentType = hnd.first.type().returnType().valueType!!,
            mutable = hnd.second != null
        )
        if (hndType != type) throw RunErr.ImportIncompatible(module, field, type, hndType)
        return hnd
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> resolveImportMemory(module: String, field: String, type: Node.Type.Memory, memClass: Class<T>) =
        registrations[module]?.exportedMemory(field, memClass) ?: throw RunErr.ImportNotFound(module, field)

    override fun resolveImportTable(module: String, field: String, type: Node.Type.Table) =
        registrations[module]?.exportedTable(field) ?: throw RunErr.ImportNotFound(module, field)
}