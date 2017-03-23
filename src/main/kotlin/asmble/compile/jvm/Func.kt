package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodNode

data class Func(
    val name: String,
    val params: List<TypeRef> = emptyList(),
    val ret: TypeRef = Void::class.ref,
    val access: Int = Opcodes.ACC_PUBLIC,
    val insns: List<AbstractInsnNode> = emptyList(),
    val stack: List<TypeRef> = emptyList(),
    val memIsLocalVar: Boolean = false
) {

    val desc: String get() = ret.asMethodRetDesc(*params.toTypedArray())

    fun addInsns(insns: List<AbstractInsnNode>) = copy(insns = this.insns + insns)

    fun addInsns(vararg insns: AbstractInsnNode) = copy(insns = this.insns + insns)

    fun apply(fn: (Func) -> Func) = fn(this)

    fun push(vararg types: TypeRef) = copy(stack = stack + types)

    fun popExpectingMulti(types: List<TypeRef>) = types.reversed().fold(this, Func::popExpecting)

    fun popExpectingMulti(vararg types: TypeRef) = types.reversed().fold(this, Func::popExpecting)

    fun popExpecting(type: TypeRef) = popExpectingAny(type)

    fun popExpectingNum() = popExpectingAny(Int::class.ref, Long::class.ref, Float::class.ref, Double::class.ref)

    fun popExpectingAny(vararg types: TypeRef) = popExpectingAny(types::contains)

    fun popExpectingAny(pred: (TypeRef) -> Boolean): Func {
        stack.lastOrNull()?.let { require(pred(it)) { "Stack var type ${stack.last()} unexpected" } }
        return pop().first
    }

    fun pop(): Pair<Func, TypeRef> {
        require(stack.isNotEmpty(), { "Stack is empty" })
        return copy(stack = stack.dropLast(1)) to stack.last()
    }

    fun toMethodNode(): MethodNode {
        require(stack.isEmpty(), { "Stack not empty for $name when compiling" })
        require(insns.lastOrNull()?.isTerminating ?: false, { "Last insn for $name is not terminating" })
        val ret = MethodNode(access, name, desc, null, null)
        insns.forEach(ret.instructions::add)
        return ret
    }

    fun stackSwap() = pop().let { (fn, refLast) ->
        fn.pop().let { (fn, refFirst) ->
            if (refFirst.stackSize == 2) {
                if (refLast.stackSize == 2)
                    // If they are both 2, dup2_x2 + pop2
                    fn.addInsns(InsnNode(Opcodes.DUP2_X2), InsnNode(Opcodes.POP))
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
            }
        }
    }
}