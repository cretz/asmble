package asmble.compile.jvm

import asmble.compile.jvm.msplit.SplitMethod
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodTooLargeException
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * May mutate given class nodes on [fromClassNode] if [splitMethod] is present (the default). Uses the two-param
 * [SplitMethod.split] call to try and split overly large methods.
 */
open class AsmToBinary(val splitMethod: SplitMethod? = SplitMethod(Opcodes.ASM6)) {
    fun fromClassNode(
        cn: ClassNode,
        newClassWriter: () -> ClassWriter = { ClassWriter(ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS) }
    ): ByteArray {
        while (true) {
            val cw = newClassWriter()
            // Note, compute maxs adds a bunch of NOPs for unreachable code.
            // See $func12 of block.wast. I don't believe the extra time over the
            // instructions to remove the NOPs is worth it.
            cn.accept(cw)
            try {
                return cw.toByteArray()
            } catch (e: MethodTooLargeException) {
                if (splitMethod == null) throw e
                // Split the offending method by removing it and replacing it with the split ones
                require(cn.name == e.className)
                val tooLargeIndex = cn.methods.indexOfFirst { it.name == e.methodName && it.desc == e.descriptor }
                require(tooLargeIndex >= 0)
                val split = splitMethod.split(cn.name, cn.methods[tooLargeIndex])
                split ?: throw IllegalStateException("Failed to split", e)
                // Change the split off method's name if there's already one
                val origName = split.splitOffMethod.name
                var foundCount = 0
                while (cn.methods.any { it.name == split.splitOffMethod.name }) {
                    split.splitOffMethod.name = origName + (++foundCount)
                }
                // Replace at the index
                cn.methods.removeAt(tooLargeIndex)
                cn.methods.add(tooLargeIndex, split.splitOffMethod)
                cn.methods.add(tooLargeIndex, split.trimmedMethod)
            }
        }
    }

    companion object : AsmToBinary() {
        val noSplit = AsmToBinary(null)
    }
}