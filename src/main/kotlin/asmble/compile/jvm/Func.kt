package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.tree.AbstractInsnNode

data class Func(val insns: List<AbstractInsnNode>, val stack: List<Class<*>>) {

    fun addInsns(vararg insns: AbstractInsnNode) = copy(insns = this.insns + insns)

    fun apply(fn: (Func) -> Func) = fn(this)

    fun push(vararg types: Class<*>) = copy(stack = stack + types)

    fun popExpectingNum() = popExpecting(Int::class.java, Long::class.java, Float::class.java, Double::class.java)

    fun popExpecting(vararg types: Class<*>) = popExpectingAny(types::contains)

    fun popExpectingAny(pred: (Class<*>) -> Boolean): Func {
        require(stack.isNotEmpty(), { "Stack is empty" })
        require(pred(stack.last()), { "Stack var type ${stack.last()} unexpected" })
        return copy(stack = stack.dropLast(1))
    }
}