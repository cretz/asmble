package asmble.run.jvm

import asmble.ast.Node
import asmble.ast.Script
import asmble.compile.jvm.*
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
import asmble.run.jvm.annotation.WasmName
import asmble.util.Logger
import asmble.util.toRawIntBits
import asmble.util.toRawLongBits
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
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
            "spectest" to NativeModule(TestHarness::class.java, TestHarness(out))
        ))

    fun runCommand(cmd: Script.Cmd) = when (cmd) {
        is Script.Cmd.Module ->
            // We ask for the module instance because some things are built on <init> expectation
            compileModule(cmd.module, "Module${modules.size}", cmd.name).also { it.instance(this) }.let {
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
            compileModule(unlink.module, className, null).instance(this)
            throw ScriptAssertionError(unlink, "Expected module link error with '${unlink.failure}', was valid")
        } catch (e: Throwable) { assertFailure(unlink, e, unlink.failure) }
    }

    fun assertTrapModule(trap: Script.Cmd.Assertion.TrapModule) {
        try {
            val className = "trapmod" + UUID.randomUUID().toString().replace("-", "")
            compileModule(trap.module, className, null).instance(this)
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
        if (!msgs.any { it.contains(expectedString) })
            throw ScriptAssertionError(a, "Expected failure '$expectedString' in $msgs", cause = innerEx)
    }

    fun doAction(cmd: Script.Cmd.Action) = when (cmd) {
        is Script.Cmd.Action.Invoke -> doInvoke(cmd)
        is Script.Cmd.Action.Get -> doGet(cmd)
    }

    fun doGet(cmd: Script.Cmd.Action.Get): Pair<Node.Type.Value, Any> {
        // Grab last module or named one
        val module = if (cmd.name == null) modules.last() else modules.first { it.name == cmd.name }
        // Just call the getter
        val getter = module.cls.getDeclaredMethod("get" + cmd.string.javaIdent.capitalize())
        return getter.returnType.valueType!! to getter.invoke(module.instance(this))
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
        return method.returnType.valueType to method.invoke(compMod.instance(this), *params.toTypedArray())
    }

    fun runExpr(insns: List<Node.Instr>) {
        MethodHandleUtil.invokeVoid(compileExpr(insns, null))
    }

    fun runExpr(insns: List<Node.Instr>, retType: Node.Type.Value): Any = compileExpr(insns, retType).let { handle ->
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
        return MethodHandles.lookup().bind(compiled.instance(this), "expr",
            MethodType.methodType(retType?.jclass ?: Void.TYPE))
    }

    fun withCompiledModule(mod: Node.Module, className: String, name: String?) =
        copy(modules = modules + compileModule(mod, className, name))

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

    fun bindImport(import: Node.Import, getter: Boolean, methodType: MethodType): MethodHandle {
        // Find a method that matches our expectations
        val module = registrations[import.module] ?: error("Unable to find module ${import.module}")
        // TODO: do I want to introduce a complicated set of code that will find
        // a method that can accept the given params including varargs, boxing, etc?
        // I doubt it since it's only the JVM layer, WASM doesn't have parametric polymorphism
        try {
            val javaName = if (getter) "get" + import.field.javaIdent.capitalize() else import.field.javaIdent
            return MethodHandles.lookup().bind(module.instance(this), javaName, methodType)
        } catch (e: NoSuchMethodException) {
            // Try any method w/ the proper annotation
            module.cls.methods.forEach { method ->
                if (method.getAnnotation(WasmName::class.java)?.value == import.field) {
                    val handle = MethodHandles.lookup().unreflect(method).bindTo(module.instance(this))
                    if (handle.type() == methodType) return handle
                }
            }
            throw e
        }
    }

    fun resolveImportFunc(import: Node.Import, funcType: Node.Type.Func) =
        bindImport(import, false,
            MethodType.methodType(funcType.ret?.jclass ?: Void.TYPE, funcType.params.map { it.jclass }))

    fun resolveImportGlobal(import: Node.Import, globalType: Node.Type.Global) =
        bindImport(import, true, MethodType.methodType(globalType.contentType.jclass))

    fun resolveImportMemory(import: Node.Import, memoryType: Node.Type.Memory, mem: Mem) =
        bindImport(import, true, MethodType.methodType(Class.forName(mem.memType.asm.className))).
            invokeWithArguments()!!

    fun resolveImportTable(import: Node.Import, tableType: Node.Type.Table) =
        bindImport(import, true, MethodType.methodType(Array<MethodHandle>::class.java)).
            invokeWithArguments()!! as Array<MethodHandle>

    interface Module {
        val cls: Class<*>
        // Guaranteed to be the same instance when there is no error
        fun instance(ctx: ScriptContext): Any
    }

    class NativeModule(override val cls: Class<*>, val inst: Any) : Module {
        override fun instance(ctx: ScriptContext) = inst
    }

    class CompiledModule(
        val mod: Node.Module,
        override val cls: Class<*>,
        val name: String?,
        val mem: Mem
    ) : Module {
        private var inst: Any? = null
        override fun instance(ctx: ScriptContext) =
            synchronized(this) { inst ?: createInstance(ctx).also { inst = it } }

        private fun createInstance(ctx: ScriptContext): Any {
            // Find the constructor
            var constructorParams = emptyList<Any>()
            var constructor: Constructor<*>?

            // If there is a memory import, we have to get the one with the mem class as the first
            val memImport = mod.imports.find { it.kind is Node.Import.Kind.Memory }
            val memLimit = if (memImport != null) {
                constructor = cls.declaredConstructors.find { it.parameterTypes.firstOrNull()?.ref == mem.memType }
                val memImportKind = memImport.kind as Node.Import.Kind.Memory
                val memInst = ctx.resolveImportMemory(memImport, memImportKind.type, mem)
                constructorParams += memInst
                val (memLimit, memCap) = mem.limitAndCapacity(memInst)
                if (memLimit < memImportKind.type.limits.initial * Mem.PAGE_SIZE)
                    throw RunErr.ImportMemoryLimitTooSmall(memImportKind.type.limits.initial * Mem.PAGE_SIZE, memLimit)
                memImportKind.type.limits.maximum?.let {
                    if (memCap > it * Mem.PAGE_SIZE)
                        throw RunErr.ImportMemoryCapacityTooLarge(it * Mem.PAGE_SIZE, memCap)
                }
                memLimit
            } else {
                // Find the constructor with no max mem amount (i.e. not int and not memory)
                constructor = cls.declaredConstructors.find {
                    val memClass = Class.forName(mem.memType.asm.className)
                    when (it.parameterTypes.firstOrNull()) {
                        Int::class.java, memClass -> false
                        else -> true
                    }
                }
                // If it is not there, find the one w/ the max mem amount
                val maybeMem = mod.memories.firstOrNull()
                if (constructor == null) {
                    val maxMem = Math.max(maybeMem?.limits?.initial ?: 0, ctx.defaultMaxMemPages)
                    constructor = cls.declaredConstructors.find { it.parameterTypes.firstOrNull() == Int::class.java }
                    constructorParams += maxMem * Mem.PAGE_SIZE
                }
                maybeMem?.limits?.initial?.let { it * Mem.PAGE_SIZE }
            }
            if (constructor == null) error("Unable to find suitable module constructor")

            // Function imports
            constructorParams += mod.imports.mapNotNull {
                if (it.kind is Node.Import.Kind.Func) ctx.resolveImportFunc(it, mod.types[it.kind.typeIndex])
                else null
            }

            // Global imports
            val globalImports = mod.imports.mapNotNull {
                if (it.kind is Node.Import.Kind.Global) ctx.resolveImportGlobal(it, it.kind.type)
                else null
            }
            constructorParams += globalImports

            // Table imports
            val tableImport = mod.imports.find { it.kind is Node.Import.Kind.Table }
            val tableSize = if (tableImport != null) {
                val tableImportKind = tableImport.kind as Node.Import.Kind.Table
                val table = ctx.resolveImportTable(tableImport, tableImportKind.type)
                if (table.size < tableImportKind.type.limits.initial)
                    throw RunErr.ImportTableTooSmall(tableImportKind.type.limits.initial, table.size)
                tableImportKind.type.limits.maximum?.let {
                    if (table.size > it) throw RunErr.ImportTableTooLarge(it, table.size)
                }
                constructorParams = constructorParams.plusElement(table)
                table.size
            } else mod.tables.firstOrNull()?.limits?.initial

            // We need to validate that elems can fit in table and data can fit in mem
            fun constIntExpr(insns: List<Node.Instr>): Int? = insns.singleOrNull()?.let {
                when (it) {
                    is Node.Instr.I32Const -> it.value
                    is Node.Instr.GetGlobal ->
                        if (it.index < globalImports.size) {
                            // Imports we already have
                            if (globalImports[it.index].type().returnType() == Int::class.java) {
                                globalImports[it.index].invokeWithArguments() as Int
                            } else null
                        } else constIntExpr(mod.globals[it.index - globalImports.size].init)
                    else -> null
                }
            }
            if (tableSize != null) mod.elems.forEach { elem ->
                constIntExpr(elem.offset)?.let { offset ->
                    if (offset >= tableSize) throw RunErr.InvalidElemIndex(offset, tableSize)
                }
            }
            if (memLimit != null) mod.data.forEach { data ->
                constIntExpr(data.offset)?.let { offset ->
                    if (offset < 0 || offset + data.data.size > memLimit)
                        throw RunErr.InvalidDataIndex(offset, data.data.size, memLimit)
                }
            }

            // Construct
            ctx.debug { "Instantiating $cls using $constructor with params $constructorParams" }
            return constructor.newInstance(*constructorParams.toTypedArray())
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

        fun addClass(bytes: ByteArray) {
            // Just get the name
            var className = ""
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM5) {
                override fun visit(a: Int, b: Int, name: String, c: String?, d: String?, e: Array<out String>?) {
                    className = name.replace('/', '.')
                }
            }, ClassReader.SKIP_CODE)
            defineClass(className, bytes, 0, bytes.size)
        }
    }
}