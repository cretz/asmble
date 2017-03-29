package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.util.*

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

    fun addInsns(insns: List<AbstractInsnNode>) = copy(insns = this.insns + insns)

    fun addInsns(vararg insns: AbstractInsnNode) = copy(insns = this.insns + insns)

    fun apply(fn: (Func) -> Func) = fn(this)

    fun push(vararg types: TypeRef) = copy(stack = stack + types)

    fun popExpectingMulti(types: List<TypeRef>) = types.reversed().fold(this, Func::popExpecting)

    fun popExpectingMulti(vararg types: TypeRef) = types.reversed().fold(this, Func::popExpecting)

    fun popExpecting(type: TypeRef) = popExpectingAny(type)

    fun popExpectingAny(vararg types: TypeRef): Func {
        peekExpectingAny(*types)
        return pop().first
    }

    fun isStackEmptyForBlock(currBlock: Block? = blockStack.lastOrNull()): Boolean {
        // Per https://github.com/WebAssembly/design/issues/1020, it's not whether the
        // stack is empty, but whether it's the same as the current block
        return stack.isEmpty() || (currBlock != null && stack.size <= currBlock.origStack.size)
    }

    fun pop(currBlock: Block? = blockStack.lastOrNull()): Pair<Func, TypeRef> {
        if (isStackEmptyForBlock(currBlock)) throw CompileErr.StackMismatch(emptyArray(), null)
        return copy(stack = stack.dropLast(1)) to stack.last()
    }

    fun peekExpecting(type: TypeRef) = peekExpectingAny(type)

    fun peekExpectingAny(vararg types: TypeRef): TypeRef {
        if (isStackEmptyForBlock()) throw CompileErr.StackMismatch(types, null)
        val hasExpected = stack.lastOrNull()?.let(types::contains) ?: false
        if (!hasExpected) throw CompileErr.StackMismatch(types, stack.lastOrNull())
        return stack.last()
    }

    fun toMethodNode(): MethodNode {
        if (stack.isNotEmpty()) throw CompileErr.UnusedStackOnReturn(stack)
        require(insns.lastOrNull()?.isTerminating ?: false, { "Last insn for $name$desc is not terminating" })
        val ret = MethodNode(access, name, desc, null, null)
        insns.forEach(ret.instructions::add)
        return ret
    }

    fun withoutAffectingStack(fn: (Func) -> Func) = fn(this).copy(stack = stack)

    fun stackSwap(currBlock: Block? = blockStack.lastOrNull()) = pop(currBlock).let { (fn, refLast) ->
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
        }
    }
}