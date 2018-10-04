package asmble.compile.jvm

import asmble.compile.jvm.msplit.SplitMethod
import asmble.util.Logger
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodTooLargeException
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/**
 * May mutate given class nodes on [fromClassNode] if [splitMethod] is present (the default). Uses the two-param
 * [SplitMethod.split] call to try and split overly large methods.
 */
open class AsmToBinary(
    val splitMethod: SplitMethod? = SplitMethod(Opcodes.ASM6),
    val logger: Logger = Logger.Print(Logger.Level.OFF)
) {
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
                logger.info {
                    val m = cn.methods[tooLargeIndex]
                    "Method ${m.name} (insns: ${m.instructions.size()}, size: ${e.codeSize}) too large, splitting..."
                }
                logMethodInsns("Pre-split", cn.methods[tooLargeIndex])
                val split = splitMethod.split(cn.name, cn.methods[tooLargeIndex])
                split ?: throw IllegalStateException("Failed to split", e)
                // Change the split off method's name if there's already one
                val origName = split.splitOffMethod.name
                var foundCount = 0
                while (cn.methods.any { it.name == split.splitOffMethod.name }) {
                    split.splitOffMethod.name = origName + (++foundCount)
                }
                // Replace at the index
                logger.info {
                    val m = cn.methods[tooLargeIndex]
                    "Split ${m.name} (${m.instructions.size()}) into " +
                        "${split.trimmedMethod.name} (${split.trimmedMethod.instructions.size()}) and " +
                        "${split.splitOffMethod.name} (${split.splitOffMethod.instructions.size()})"
                }
                logMethodInsns("Trimmed", split.trimmedMethod)
                logMethodInsns("Split-off", split.splitOffMethod)
                cn.methods.removeAt(tooLargeIndex)
                cn.methods.add(tooLargeIndex, split.splitOffMethod)
                cn.methods.add(tooLargeIndex, split.trimmedMethod)
            }
        }
    }

    fun logMethodInsns(type: String, method: MethodNode) {
        logger.debug {
            "$type method ${method.name} first 100:\n" + method.cloneWithInsnRange(0 until 100).toAsmString()
        }
        logger.debug {
            val size = method.instructions.size()
            "$type method ${method.name} last 100:\n" +
                method.cloneWithInsnRange((size - 100) until size).toAsmString()
        }
    }

    companion object : AsmToBinary() {
        val noSplit = AsmToBinary(null)
    }
}