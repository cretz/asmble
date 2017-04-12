package asmble.compile.jvm

import asmble.ast.Node
import asmble.util.Either
import asmble.util.Logger
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

data class ClsContext(
    val packageName: String,
    val className: String,
    val mod: Node.Module,
    val cls: ClassNode = ClassNode().also { it.name = (packageName.replace('.', '/') + "/$className").trimStart('/') },
    val mem: Mem = ByteBufferMem,
    val reworker: InsnReworker = InsnReworker,
    val logger: Logger = Logger.Print(Logger.Level.OFF),
    val funcBuilder: FuncBuilder = FuncBuilder,
    val syntheticAssertionBuilder: SyntheticAssertionBuilder = SyntheticAssertionBuilder,
    val checkTruncOverflow: Boolean = true,
    val nonAdjacentMemAccessesRequiringLocalVar: Int = 3,
    val eagerFailLargeMemOffset: Boolean = true,
    val preventMemIndexOverflow: Boolean = false,
    val accurateNanBits: Boolean = true,
    val checkSignedDivIntegerOverflow: Boolean = true
) : Logger by logger {
    val importFuncs: List<Node.Import> by lazy { mod.imports.filter { it.kind is Node.Import.Kind.Func } }
    val importGlobals: List<Node.Import> by lazy { mod.imports.filter { it.kind is Node.Import.Kind.Global } }
    val thisRef = TypeRef(Type.getObjectType((packageName.replace('.', '/') + "/$className").trimStart('/')))

    fun funcAtIndex(index: Int) = importFuncs.getOrNull(index).let {
        when (it) {
            null -> Either.Right(mod.funcs.getOrNull(index - importFuncs.size) ?: throw CompileErr.UnknownFunc(index))
            else -> Either.Left(it)
        }
    }

    fun funcTypeAtIndex(index: Int) = funcAtIndex(index).let {
        when (it) {
            is Either.Left -> mod.types[(it.v.kind as Node.Import.Kind.Func).typeIndex]
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
    fun funcName(index: Int) = "\$func$index"

    private fun syntheticAssertion(
        nameSuffix: String,
        fn: SyntheticAssertionBuilder.(ClsContext, String) -> MethodNode
    ): MethodInsnNode {
        val name = "\$\$$nameSuffix"
        val method =
            cls.methods.find { (it as MethodNode).name == name }?.let { it as MethodNode } ?:
                fn(syntheticAssertionBuilder, this, name).also { cls.methods.add(it) }
        return MethodInsnNode(Opcodes.INVOKESTATIC, thisRef.asmName, method.name, method.desc, false)
    }

    val truncAssertF2SI get() = syntheticAssertion("assertF2SI", SyntheticAssertionBuilder::buildF2SIAssertion)
    val truncAssertF2UI get() = syntheticAssertion("assertF2UI", SyntheticAssertionBuilder::buildF2UIAssertion)
    val truncAssertF2SL get() = syntheticAssertion("assertF2SL", SyntheticAssertionBuilder::buildF2SLAssertion)
    val truncAssertF2UL get() = syntheticAssertion("assertF2UL", SyntheticAssertionBuilder::buildF2ULAssertion)
    val truncAssertD2SI get() = syntheticAssertion("assertD2SI", SyntheticAssertionBuilder::buildD2SIAssertion)
    val truncAssertD2UI get() = syntheticAssertion("assertD2UI", SyntheticAssertionBuilder::buildD2UIAssertion)
    val truncAssertD2SL get() = syntheticAssertion("assertD2SL", SyntheticAssertionBuilder::buildD2SLAssertion)
    val truncAssertD2UL get() = syntheticAssertion("assertD2UL", SyntheticAssertionBuilder::buildD2ULAssertion)
    val divAssertI get() = syntheticAssertion("assertIDiv", SyntheticAssertionBuilder::buildIDivAssertion)
    val divAssertL get() = syntheticAssertion("assertLDiv", SyntheticAssertionBuilder::buildLDivAssertion)
}