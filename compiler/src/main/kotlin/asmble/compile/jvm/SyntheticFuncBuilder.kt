package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

open class SyntheticFuncBuilder {

    fun buildIndirectBootstrap(ctx: ClsContext, name: String): MethodNode {
        // With WASM, you have all parameters then the index of the method to call in the table.
        // So we need to build a dynamic call that can take parameters + the table index.
        // Just take the helper's instructions and add them here. For now we don't cache because
        // ASM does some annoying state manip even when just looping over instructions. For now,
        // none of the insns in the helper reference the "owner" so we don't have to change those.
        val runtimeHelpers = ClassNode().also {
            ClassReader(RuntimeHelpers::class.java.name).
                accept(it, ClassReader.SKIP_DEBUG and ClassReader.SKIP_FRAMES)
        }
        val helperMeth = runtimeHelpers.methods.first { (it as MethodNode).name == "bootstrapIndirect" } as MethodNode
        return MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC, name,
            helperMeth.desc, null, null
        ).addInsns(*helperMeth.instructions.toArray())
    }

    // Guaranteed that the first method result can be called to get the proper index.
    // Caller needs to make sure namePrefix is unique.
    fun buildLargeTableJumps(ctx: ClsContext, namePrefix: String, table: Node.Instr.BrTable): List<MethodNode> {
        // Sadly really large table jumps need to be broken up into multiple methods
        // because Java has method size limits. What we are going to do here is make
        // a method that takes an int, then a table switch node that returns the depth.
        // The default will chain to another method if there are more to handle.

        // Build a bunch of chunk views...first of each is start, second is sub list
        val chunks = (0 until Math.ceil(table.targetTable.size / ctx.jumpTableChunkSize.toDouble()).toInt()).
            fold(emptyList<Pair<Int, List<Int>>>()) { chunks, chunkNum ->
                val start = chunkNum * ctx.jumpTableChunkSize
                chunks.plusElement(start to table.targetTable.subList(start,
                    Math.min(table.targetTable.size, (chunkNum + 1) * ctx.jumpTableChunkSize)))
            }
        // Go over the chunks, backwards, building the jump methods, then flip em back
        return chunks.asReversed().fold(emptyList<MethodNode>()) { methods, (start, chunk) ->
            val defaultLabel = LabelNode()
            val method = largeTableJumpMethod(ctx, namePrefix, start, chunk, defaultLabel)
            // If we are the last chunk, default is what table default is
            methods + if (methods.isEmpty()) method.addInsns(
                defaultLabel, table.default.const, InsnNode(Opcodes.IRETURN)
            ) else method.addInsns(
                // Otherwise, the default label just calls the prev
                defaultLabel,
                VarInsnNode(Opcodes.ILOAD, 0),
                methods.last().let { other ->
                    MethodInsnNode(Opcodes.INVOKESTATIC, ctx.thisRef.asmName, other.name, other.desc, false)
                },
                InsnNode(Opcodes.IRETURN)
            )
        }.reversed()
    }

    private fun largeTableJumpMethod(
        ctx: ClsContext,
        namePrefix: String,
        startIndex: Int,
        targets: List<Int>,
        defaultLabel: LabelNode
    ): MethodNode {
        val labelsByTargets = mutableMapOf<Int, LabelNode>()
        return MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            "${namePrefix}_${startIndex}_until_${startIndex + targets.size}", "(I)I", null, null
        ).addInsns(
            VarInsnNode(Opcodes.ILOAD, 0),
            TableSwitchInsnNode(startIndex, (startIndex + targets.size) - 1, defaultLabel,
                *targets.map { labelsByTargets.getOrPut(it) { LabelNode() } }.toTypedArray()
            )
        ).also { method ->
            labelsByTargets.forEach { (target, label) ->
                method.addInsns(label, target.const, InsnNode(Opcodes.IRETURN))
            }
        }
    }

    fun buildIDivAssertion(ctx: ClsContext, name: String) =
        LabelNode().let { safeLabel ->
            LabelNode().let { overflowLabel ->
                MethodNode(
                    Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
                    name, "(II)V", null, null
                ).addInsns(
                    VarInsnNode(Opcodes.ILOAD, 0),
                    Int.MIN_VALUE.const,
                    JumpInsnNode(Opcodes.IF_ICMPNE, safeLabel),
                    VarInsnNode(Opcodes.ILOAD, 1),
                    (-1).const,
                    JumpInsnNode(Opcodes.IF_ICMPEQ, overflowLabel),
                    safeLabel,
                    InsnNode(Opcodes.RETURN),
                    overflowLabel
                ).throwArith("Integer overflow")
            }
        }

    fun buildLDivAssertion(ctx: ClsContext, name: String) =
        LabelNode().let { safeLabel ->
            LabelNode().let { overflowLabel ->
                MethodNode(
                    Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
                    name, "(JJ)V", null, null
                ).addInsns(
                    VarInsnNode(Opcodes.LLOAD, 0),
                    Long.MIN_VALUE.const,
                    InsnNode(Opcodes.LCMP),
                    JumpInsnNode(Opcodes.IFNE, safeLabel),
                    VarInsnNode(Opcodes.LLOAD, 2),
                    (-1L).const,
                    InsnNode(Opcodes.LCMP),
                    JumpInsnNode(Opcodes.IFEQ, overflowLabel),
                    safeLabel,
                    InsnNode(Opcodes.RETURN),
                    overflowLabel
                ).throwArith("Integer overflow")
            }
        }

    // TODO: add tests for +- 4 near overflow for each combo compared with spec

    fun buildF2SIAssertion(ctx: ClsContext, name: String) =
        MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            name, "(F)V", null, null
        ).floatNanCheck().floatRangeCheck(2147483648f, -2147483648f).addInsns(InsnNode(Opcodes.RETURN))

    fun buildF2UIAssertion(ctx: ClsContext, name: String) =
        MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            name, "(F)V", null, null
        ).floatNanCheck().floatUnsignedRangeCheck(4294967296f).addInsns(InsnNode(Opcodes.RETURN))

    fun buildF2SLAssertion(ctx: ClsContext, name: String) =
        MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            name, "(F)V", null, null
        ).floatNanCheck().floatRangeCheck(9223372036854775807f, -9223372036854775807f).
            addInsns(InsnNode(Opcodes.RETURN))

    fun buildF2ULAssertion(ctx: ClsContext, name: String) =
        MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            name, "(F)V", null, null
        ).floatNanCheck().floatUnsignedRangeCheck(18446744073709551616f).addInsns(InsnNode(Opcodes.RETURN))

    fun buildD2SIAssertion(ctx: ClsContext, name: String) =
        MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            name, "(D)V", null, null
        ).doubleNanCheck().doubleRangeCheck(2147483648.0, -2147483648.0).addInsns(InsnNode(Opcodes.RETURN))

    fun buildD2UIAssertion(ctx: ClsContext, name: String) =
        MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            name, "(D)V", null, null
        ).doubleNanCheck().doubleUnsignedRangeCheck(4294967296.0).addInsns(InsnNode(Opcodes.RETURN))

    fun buildD2SLAssertion(ctx: ClsContext, name: String) =
        MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            name, "(D)V", null, null
        ).doubleNanCheck().doubleRangeCheck(9223372036854775807.0, -9223372036854775807.0).
            addInsns(InsnNode(Opcodes.RETURN))

    fun buildD2ULAssertion(ctx: ClsContext, name: String) =
        MethodNode(
            Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            name, "(D)V", null, null
        ).doubleNanCheck().doubleUnsignedRangeCheck(18446744073709551616.0).addInsns(InsnNode(Opcodes.RETURN))

    fun MethodNode.floatNanCheck() = LabelNode().let { okLabel ->
        this.addInsns(
            VarInsnNode(Opcodes.FLOAD, 0),
            MethodInsnNode(Opcodes.INVOKESTATIC, Float::class.javaObjectType.ref.asmName, "isNaN", "(F)Z", false),
            JumpInsnNode(Opcodes.IFEQ, okLabel)
        ).throwArith("Invalid conversion to integer").addInsns(okLabel)
    }

    fun MethodNode.doubleNanCheck() = LabelNode().let { okLabel ->
        this.addInsns(
            VarInsnNode(Opcodes.DLOAD, 0),
            MethodInsnNode(Opcodes.INVOKESTATIC, Double::class.javaObjectType.ref.asmName, "isNaN", "(D)Z", false),
            JumpInsnNode(Opcodes.IFEQ, okLabel)
        ).throwArith("Invalid conversion to integer").addInsns(okLabel)
    }

    fun MethodNode.floatRangeCheck(inclMax: Float, exclMin: Float) = LabelNode().let { failLabel ->
        LabelNode().let { okLabel ->
            this.addInsns(
                VarInsnNode(Opcodes.FLOAD, 0),
                inclMax.const,
                InsnNode(Opcodes.FCMPL),
                JumpInsnNode(Opcodes.IFGE, failLabel),
                VarInsnNode(Opcodes.FLOAD, 0),
                exclMin.const,
                InsnNode(Opcodes.FCMPG),
                JumpInsnNode(Opcodes.IFGE, okLabel),
                failLabel
            ).throwArith("Integer overflow").addInsns(okLabel)
        }
    }

    fun MethodNode.doubleRangeCheck(inclMax: Double, exclMin: Double) = LabelNode().let { failLabel ->
        LabelNode().let { okLabel ->
            this.addInsns(
                VarInsnNode(Opcodes.DLOAD, 0),
                inclMax.const,
                InsnNode(Opcodes.DCMPL),
                JumpInsnNode(Opcodes.IFGE, failLabel),
                VarInsnNode(Opcodes.DLOAD, 0),
                exclMin.const,
                InsnNode(Opcodes.DCMPG),
                JumpInsnNode(Opcodes.IFGE, okLabel),
                failLabel
            ).throwArith("Integer overflow").addInsns(okLabel)
        }
    }

    fun MethodNode.floatUnsignedRangeCheck(inclMax: Float) = LabelNode().let { failLabel ->
        LabelNode().let { okLabel ->
            this.addInsns(
                VarInsnNode(Opcodes.FLOAD, 0),
                inclMax.const,
                InsnNode(Opcodes.FCMPL),
                JumpInsnNode(Opcodes.IFGE, failLabel),
                VarInsnNode(Opcodes.FLOAD, 0),
                InsnNode(Opcodes.F2I),
                JumpInsnNode(Opcodes.IFGE, okLabel),
                failLabel
            ).throwArith("Integer overflow").addInsns(okLabel)
        }
    }

    fun MethodNode.doubleUnsignedRangeCheck(inclMax: Double) = LabelNode().let { failLabel ->
        LabelNode().let { okLabel ->
            this.addInsns(
                VarInsnNode(Opcodes.DLOAD, 0),
                inclMax.const,
                InsnNode(Opcodes.DCMPL),
                JumpInsnNode(Opcodes.IFGE, failLabel),
                VarInsnNode(Opcodes.DLOAD, 0),
                InsnNode(Opcodes.D2I),
                JumpInsnNode(Opcodes.IFGE, okLabel),
                failLabel
            ).throwArith("Integer overflow").addInsns(okLabel)
        }
    }

    fun MethodNode.throwArith(msg: String) = this.addInsns(
        TypeInsnNode(Opcodes.NEW, ArithmeticException::class.ref.asmName),
        InsnNode(Opcodes.DUP),
        msg.const,
        MethodInsnNode(Opcodes.INVOKESPECIAL, ArithmeticException::class.ref.asmName,
            "<init>", "(Ljava/lang/String;)V", false),
        InsnNode(Opcodes.ATHROW)
    )

    companion object : SyntheticFuncBuilder()
}