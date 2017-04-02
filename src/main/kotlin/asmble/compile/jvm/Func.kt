package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

data class Func(
    val name: String,
    val params: List<TypeRef> = emptyList(),
    val ret: TypeRef = Void::class.ref,
    val access: Int = Opcodes.ACC_PUBLIC,
    val insns: List<AbstractInsnNode> = emptyList(),
    val stack: List<TypeRef> = emptyList(),
    val blockStack: List<Block> = emptyList(),
    // Contains index of JumpInsnNode that has a null label
    val ifStack: List<Int> = emptyList()
) {

    val desc: String get() = ret.asMethodRetDesc(*params.toTypedArray())
    val isCurrentBlockDead get() = blockStack.lastOrNull()?.let { block ->
        // It's dead if it's marked unconditional or it's an unconditional
        // if/else and we are in that if/else area
        block.unconditionalBranch ||
            (block.unconditionalBranchInIf && !block.hasElse) ||
            (block.unconditionalBranchInElse && block.hasElse)
    } ?: false

    fun addInsns(insns: List<AbstractInsnNode>) =
        if (isCurrentBlockDead) this else copy(insns = this.insns + insns)

    fun addInsns(vararg insns: AbstractInsnNode) =
        if (isCurrentBlockDead) this else copy(insns = this.insns + insns)

    fun push(vararg types: TypeRef) = copy(stack = stack + types)

    fun popExpectingMulti(types: List<TypeRef>) = types.reversed().fold(this, Func::popExpecting)

    fun popExpectingMulti(vararg types: TypeRef) = types.reversed().fold(this, Func::popExpecting)

    fun popExpecting(type: TypeRef): Func {
        assertTopOfStack(type)
        return pop().first
    }

    fun isStackEmptyForBlock(currBlock: Block? = blockStack.lastOrNull()): Boolean {
        // Per https://github.com/WebAssembly/design/issues/1020, it's not whether the
        // stack is empty, but whether it's the same as the current block
        return stack.isEmpty() || (currBlock != null && stack.size <= currBlock.origStack.size)
    }

    fun pop(currBlock: Block? = blockStack.lastOrNull()): Pair<Func, TypeRef> {
        if (isStackEmptyForBlock(currBlock)) {
            // Just fake it if dead
            if (isCurrentBlockDead) return this to Int::class.ref
            throw CompileErr.StackMismatch(emptyArray(), null)
        }
        return copy(stack = stack.dropLast(1)) to stack.last()
    }

    fun assertTopOfStack(type: TypeRef, currBlock: Block? = blockStack.lastOrNull()): Unit {
        // If it's dead, we just go with it
        if (!isCurrentBlockDead) {
            if (isStackEmptyForBlock(currBlock)) throw CompileErr.StackMismatch(arrayOf(type), null)
            if (stack.lastOrNull() != type) throw CompileErr.StackMismatch(arrayOf(type), stack.lastOrNull())
        }
    }

    fun toMethodNode(): MethodNode {
        if (stack.isNotEmpty()) throw CompileErr.UnusedStackOnReturn(stack)
        require(insns.lastOrNull()?.isTerminating ?: false, { "Last insn for $name$desc is not terminating" })
        val ret = MethodNode(access, name, desc, null, null)
        insns.forEach(ret.instructions::add)
        return ret
    }

    fun withoutAffectingStack(fn: (Func) -> Func) = fn(this).copy(stack = stack)

    fun stackSwap(currBlock: Block? = blockStack.lastOrNull()) =
        if (isCurrentBlockDead) this else pop(currBlock).let { (fn, refLast) ->
            fn.pop(currBlock).let { (fn, refFirst) ->
                (if (refFirst.stackSize == 2) {
                    if (refLast.stackSize == 2)
                        // If they are both 2, dup2_x2 + pop2
                        fn.addInsns(InsnNode(Opcodes.DUP2_X2), InsnNode(Opcodes.POP2))
                    else
                        // If only the first one is, dup_x2 + pop
                        fn.addInsns(InsnNode(Opcodes.DUP_X2), InsnNode(Opcodes.POP))
                } else {
                    if (refLast.stackSize == 2)
                       // If the first is not 2 but the last is, dup_2x1, pop2
                        fn.addInsns(InsnNode(Opcodes.DUP2_X1), InsnNode(Opcodes.POP2))
                    else
                        // If neither are 2, just swap
                        fn.addInsns(InsnNode(Opcodes.SWAP))
                }).push(refLast).push(refFirst)
            }
        }

    fun pushBlock(insn: Node.Instr) = copy(blockStack = blockStack + Block(insn, insns.size, stack))

    fun popBlock() = copy(blockStack = blockStack.dropLast(1)) to blockStack.last()

    fun blockAtDepth(depth: Int) = blockStack.getOrNull(blockStack.size - depth - 1).let { block ->
        when (block) {
            null -> throw CompileErr.NoBlockAtDepth(depth)
            is Block.WithLabel -> this to block
            // We have to lazily create it here
            else -> blockStack.toMutableList().let {
                val newBlock = block.withLabel(LabelNode())
                it[blockStack.size - depth - 1] = newBlock
                copy(blockStack = it) to newBlock
            }
        }
    }

    fun pushIf() = copy(ifStack = ifStack + insns.size)

    fun peekIf() = insns[ifStack.last()] as JumpInsnNode

    fun popIf() = copy(ifStack = ifStack.dropLast(1)) to peekIf()

    open class Block(
        val insn: Node.Instr,
        val startIndex: Int,
        val origStack: List<TypeRef>
    ) {
        open val label: LabelNode? get() = null
        open val requiredEndStack: List<TypeRef>? get() = null
        open val hasElse: Boolean get() = false
        open val unconditionalBranch: Boolean get() = false
        open val unconditionalBranchInIf: Boolean get() = false
        open val unconditionalBranchInElse: Boolean get() = false
        // First val is the insn, second is the type
        open val blockExitVals: List<Pair<Node.Instr, TypeRef?>> = emptyList()
        fun withLabel(label: LabelNode) = WithLabel(insn, startIndex, origStack, label)
        val insnType: Node.Type.Value? get() = (insn as? Node.Instr.Args.Type)?.type

        class WithLabel(
            insn: Node.Instr,
            startIndex: Int,
            origStack: List<TypeRef>,
            override val label: LabelNode
        ) : Block(insn, startIndex, origStack) {
            override var blockExitVals: List<Pair<Node.Instr, TypeRef?>> = emptyList()
            override var requiredEndStack: List<TypeRef>? = null
            override var hasElse = false
            override var unconditionalBranch = false
            override var unconditionalBranchInIf = false
            override var unconditionalBranchInElse = false
        }
    }
}