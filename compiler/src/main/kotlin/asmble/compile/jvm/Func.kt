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
    // Contains index of JumpInsnNode that has a null label initially
    val ifStack: List<Int> = emptyList(),
    val lastStackIsMemLeftover: Boolean = false
) {

    val desc: String get() = ret.asMethodRetDesc(*params.toTypedArray())
    val currentBlock get() = blockAtDepth(0)
    val isCurrentBlockDead get() = blockStack.lastOrNull()?.unreachable ?: false

    fun markUnreachable() = currentBlock.let { block ->
        if (block.insn is Node.Instr.If) {
            if (block.hasElse) {
                block.unreachableInElse = true
                block.unreachable = block.unreachableInElse && block.unreachableInIf
            } else block.unreachableInIf = true
        } else {
            block.unreachable = true
        }
        copy(stack = currentBlock.origStack)
    }

    fun addInsns(insns: List<AbstractInsnNode>) =
        if (isCurrentBlockDead) this else copy(insns = this.insns + insns)

    fun addInsns(vararg insns: AbstractInsnNode) =
        if (isCurrentBlockDead) this else copy(insns = this.insns + insns)

    fun push(types: List<TypeRef>) = copy(stack = stack + types)

    fun push(vararg types: TypeRef) = push(types.asList())

    fun popExpectingMulti(types: List<TypeRef>, currBlock: Block = currentBlock) =
        types.reversed().fold(this) { fn, typ -> fn.popExpecting(typ, currBlock) }

    fun popExpectingMulti(vararg types: TypeRef) = popExpectingMulti(types.asList())

    fun popExpecting(type: TypeRef, currBlock: Block = currentBlock): Func {
        return pop(currBlock).let { (fn, poppedType) ->
            if (poppedType != TypeRef.Unknown && type != TypeRef.Unknown && poppedType != type)
                throw CompileErr.StackMismatch(arrayOf(type), poppedType)
            fn
        }
    }

    fun isStackEmptyForBlock(currBlock: Block = currentBlock): Boolean {
        // Per https://github.com/WebAssembly/design/issues/1020, it's not whether the
        // stack is empty, but whether it's the same as the current block
        return stack.isEmpty() || stack.size <= currBlock.origStack.size
    }

    fun pop(currBlock: Block = currentBlock): Pair<Func, TypeRef> {
        if (isStackEmptyForBlock(currBlock)) {
            // Just fake it if dead
            if (currBlock.unreachable) return this to TypeRef.Unknown
            if (currBlock.insn is Node.Instr.If && !currBlock.hasElse && currBlock.unreachableInIf)
                return this to TypeRef.Unknown
            if (currBlock.hasElse && currBlock.unreachableInElse) return this to TypeRef.Unknown
            throw CompileErr.StackMismatch(emptyArray(), null)
        }
        return copy(stack = stack.dropLast(1)) to stack.last()
    }

    fun peekExpecting(type: TypeRef, currBlock: Block = currentBlock): Unit {
        // Just pop expecting
        popExpecting(type, currBlock)
    }

    fun toMethodNode(): MethodNode {
        if (stack.isNotEmpty()) throw CompileErr.UnusedStackOnReturn(stack)
        require(insns.lastOrNull()?.isTerminating ?: false) { "Last insn for $name$desc is not terminating" }
        val ret = MethodNode(access, name, desc, null, null)
        insns.forEach(ret.instructions::add)
        return ret
    }

    fun withoutAffectingStack(fn: (Func) -> Func) = fn(this).copy(stack = stack)

    fun stackSwap(currBlock: Block = currentBlock) =
        pop(currBlock).let { (fn, refLast) ->
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

    fun pushBlock(insn: Node.Instr, labelType: Node.Type.Value?, endType: Node.Type.Value?) =
        pushBlock(insn, listOfNotNull(labelType?.typeRef), listOfNotNull(endType?.typeRef))

    fun pushBlock(insn: Node.Instr, labelTypes: List<TypeRef>, endTypes: List<TypeRef>) =
        copy(blockStack = blockStack + Block(insn, insns.size, stack, labelTypes, endTypes))

    fun popBlock() = copy(blockStack = blockStack.dropLast(1)) to blockStack.last()

    fun blockAtDepth(depth: Int): Block =
        blockStack.getOrNull(blockStack.size - depth - 1) ?: throw CompileErr.NoBlockAtDepth(depth)

    fun pushIf() = copy(ifStack = ifStack + insns.size)

    fun peekIf() = insns[ifStack.last()] as JumpInsnNode

    fun popIf() = copy(ifStack = ifStack.dropLast(1)) to peekIf()

    class Block(
        val insn: Node.Instr,
        val startIndex: Int,
        val origStack: List<TypeRef>,
        val labelTypes: List<TypeRef>,
        val endTypes: List<TypeRef>
    ) {
        var unreachable = false
        var unreachableInIf = false
        var unreachableInElse = false
        var hasElse = false
        var thenStackOnIf = emptyList<TypeRef>()

        var _label: LabelNode? = null
        val label get() = _label
        val requiredLabel: LabelNode get() {
            if (_label == null) _label = LabelNode()
            return _label!!
        }
    }
}