package asmble.compile.jvm

import asmble.ast.Node
import asmble.util.Either
import asmble.util.Logger
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.util.*

data class ClsContext(
    val packageName: String,
    val className: String,
    val mod: Node.Module,
    val cls: ClassNode = ClassNode().also { it.name = (packageName.replace('.', '/') + "/$className").trimStart('/') },
    val mem: Mem = ByteBufferMem,
    val modName: String? = null,
    val reworker: InsnReworker = InsnReworker,
    val logger: Logger = Logger.Print(Logger.Level.OFF),
    val funcBuilder: FuncBuilder = FuncBuilder,
    val syntheticFuncBuilder: SyntheticFuncBuilder = SyntheticFuncBuilder,
    val checkTruncOverflow: Boolean = true,
    val nonAdjacentMemAccessesRequiringLocalVar: Int = 3,
    val eagerFailLargeMemOffset: Boolean = true,
    val preventMemIndexOverflow: Boolean = false,
    val accurateNanBits: Boolean = true,
    val checkSignedDivIntegerOverflow: Boolean = true,
    val jumpTableChunkSize: Int = 5000,
    val includeBinary: Boolean = false
) : Logger by logger {
    val importFuncs: List<Node.Import> by lazy { mod.imports.filter { it.kind is Node.Import.Kind.Func } }
    val importGlobals: List<Node.Import> by lazy { mod.imports.filter { it.kind is Node.Import.Kind.Global } }
    val thisRef = TypeRef(Type.getObjectType((packageName.replace('.', '/') + "/$className").trimStart('/')))
    val hasMemory: Boolean by lazy {
        mod.memories.isNotEmpty() || mod.imports.any { it.kind is Node.Import.Kind.Memory }
    }
    val hasTable: Boolean by lazy {
        mod.tables.isNotEmpty() || mod.imports.any { it.kind is Node.Import.Kind.Table }
    }
    val dedupedFuncNames: Map<Int, String>? by lazy {
        // Consider all exports as seen
        val seen = mod.exports.flatMap { export ->
            when {
                export.kind == Node.ExternalKind.FUNCTION -> listOf(export.field.javaIdent)
                // Just to make it easy, consider all globals as having setters
                export.kind == Node.ExternalKind.GLOBAL ->
                    export.field.javaIdent.capitalize().let { listOf("get$it", "set$it") }
                else -> listOf("get" + export.field.javaIdent.capitalize())
            }
        }.toMutableSet()
        mod.names?.funcNames?.toList()?.sortedBy { it.first }?.map { (index, origName) ->
            var name = origName.javaIdent
            var nameIndex = 0
            while (!seen.add(name)) name = origName.javaIdent + (nameIndex++)
            index to name
        }?.toMap()
    }

    fun assertHasMemory() { if (!hasMemory) throw CompileErr.UnknownMemory(0) }

    fun typeAtIndex(index: Int) = mod.types.getOrNull(index) ?: throw CompileErr.UnknownType(index)

    fun funcAtIndex(index: Int) = importFuncs.getOrNull(index).let {
        when (it) {
            null -> Either.Right(mod.funcs.getOrNull(index - importFuncs.size) ?: throw CompileErr.UnknownFunc(index))
            else -> Either.Left(it)
        }
    }

    fun funcTypeAtIndex(index: Int) = funcAtIndex(index).let {
        when (it) {
            is Either.Left -> typeAtIndex((it.v.kind as Node.Import.Kind.Func).typeIndex)
            is Either.Right -> it.v.type
        }
    }

    fun globalAtIndex(index: Int) = importGlobals.getOrNull(index).let {
        when (it) {
            null ->
                Either.Right(mod.globals.getOrNull(index - importGlobals.size) ?:
                    throw CompileErr.UnknownGlobal(index))
            else ->
                Either.Left(it)
        }
    }

    fun importGlobalGetterFieldName(index: Int) = "import\$get" + globalName(index)
    fun importGlobalSetterFieldName(index: Int) = "import\$set" + globalName(index)
    fun globalName(index: Int) = "\$global$index"
    fun funcName(index: Int) = dedupedFuncNames?.get(index) ?: "\$func$index"

    private fun syntheticFunc(
        nameSuffix: String,
        fn: SyntheticFuncBuilder.(ClsContext, String) -> MethodNode
    ): MethodInsnNode {
        val name = "\$\$$nameSuffix"
        val method =
            cls.methods.find { (it as MethodNode).name == name }?.let { it as MethodNode } ?:
                fn(syntheticFuncBuilder, this, name).also { cls.methods.add(it) }
        return MethodInsnNode(Opcodes.INVOKESTATIC, thisRef.asmName, method.name, method.desc, false)
    }

    val truncAssertF2SI get() = syntheticFunc("assertF2SI", SyntheticFuncBuilder::buildF2SIAssertion)
    val truncAssertF2UI get() = syntheticFunc("assertF2UI", SyntheticFuncBuilder::buildF2UIAssertion)
    val truncAssertF2SL get() = syntheticFunc("assertF2SL", SyntheticFuncBuilder::buildF2SLAssertion)
    val truncAssertF2UL get() = syntheticFunc("assertF2UL", SyntheticFuncBuilder::buildF2ULAssertion)
    val truncAssertD2SI get() = syntheticFunc("assertD2SI", SyntheticFuncBuilder::buildD2SIAssertion)
    val truncAssertD2UI get() = syntheticFunc("assertD2UI", SyntheticFuncBuilder::buildD2UIAssertion)
    val truncAssertD2SL get() = syntheticFunc("assertD2SL", SyntheticFuncBuilder::buildD2SLAssertion)
    val truncAssertD2UL get() = syntheticFunc("assertD2UL", SyntheticFuncBuilder::buildD2ULAssertion)
    val divAssertI get() = syntheticFunc("assertIDiv", SyntheticFuncBuilder::buildIDivAssertion)
    val divAssertL get() = syntheticFunc("assertLDiv", SyntheticFuncBuilder::buildLDivAssertion)

    val indirectBootstrap get() = syntheticFunc("indirectBootstrap", SyntheticFuncBuilder::buildIndirectBootstrap)

    // Builds a method that takes an int and returns a depth int
    fun largeTableJumpCall(table: Node.Instr.BrTable): MethodInsnNode {
        val namePrefix = "largeTable" + UUID.randomUUID().toString().replace("-", "")
        val methods = syntheticFuncBuilder.buildLargeTableJumps(this, namePrefix, table)
        cls.methods.addAll(methods)
        return methods.first().let { method ->
            MethodInsnNode(Opcodes.INVOKESTATIC, thisRef.asmName, method.name, method.desc, false)
        }
    }
}