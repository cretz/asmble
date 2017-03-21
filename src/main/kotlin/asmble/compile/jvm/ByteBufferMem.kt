package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.reflect

open class ByteBufferMem(val direct: Boolean = true, val defaultMax: Int = 5 * Mem.PAGE_SIZE) : Mem<ByteBuffer> {
    override val memClass = ByteBuffer::class.java

    override fun create(func: Func, maximum: Int?) = func.addInsns(
        (maximum ?: defaultMax).const,
        (if (direct) ByteBuffer::allocateDirect else ByteBuffer::allocate).invokeStatic()
    ).push(memClass)

    override fun init(func: Func, initial: Int) = func.popExpecting(memClass).addInsns(
        // Set the limit to initial
        initial.const,
        forceFnType<ByteBuffer.(Int) -> Buffer>(ByteBuffer::limit).invokeVirtual(),
        TypeInsnNode(Opcodes.CHECKCAST, ByteBuffer::class.asmName),
        // Set it to use little endian
        ByteOrder::LITTLE_ENDIAN.getStatic(),
        forceFnType<ByteBuffer.(ByteOrder) -> ByteBuffer>(ByteBuffer::order).invokeVirtual()
    ).push(ByteBuffer::class.java)

    override fun data(func: Func, bytes: ByteArray, buildOffset: (Func) -> Func) = func.
        popExpecting(memClass).
        addInsns(
                bytes.size.const,
                IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE),
                *bytes.withIndex().flatMap { (index, byte) ->
                    listOf(InsnNode(Opcodes.DUP), index.const, byte.toInt().const)
                }.toTypedArray()
        ).
        apply(buildOffset).popExpecting(Int::class.java).
        // BOO! https://discuss.kotlinlang.org/t/overload-resolution-ambiguity-function-reference-requiring-local-var/2425
        addInsns(forceFnType<ByteBuffer.(ByteArray, Int, Int) -> ByteBuffer>(ByteBuffer::put).invokeVirtual()).
        push(memClass)

    override fun currentMemory(func: Func) = func.popExpecting(memClass).addInsns(
        forceFnType<ByteBuffer.() -> Int>(ByteBuffer::limit).invokeVirtual(),
        Mem.PAGE_SIZE.const,
        InsnNode(Opcodes.IDIV)
    ).push(Int::class.java)

    override fun growMemory(func: Func) = func.popExpecting(memClass).popExpecting(Int::class.java).addInsns(
        Mem.PAGE_SIZE.const,
        // TODO: overflow check, e.g. Math.multiplyExact
        InsnNode(Opcodes.IMUL),
        forceFnType<ByteBuffer.(Int) -> Buffer>(ByteBuffer::limit).invokeVirtual()
    ).push(ByteBuffer::class.java)

    override fun loadOp(func: Func, insn: Node.Instr.Args.AlignOffset) = func.popExpecting(memClass).let { func ->
        require(insn.offset <= Int.MAX_VALUE, { "Offsets > ${Int.MAX_VALUE} unsupported" }).let { this }
        fun Func.load(fn: ByteBuffer.(Int) -> Any, retClass: KClass<*>) =
            this.addInsns(insn.offset.toInt().const, fn.reflect()!!.invokeVirtual()).push(retClass.java)
        fun Func.loadI32(fn: ByteBuffer.(Int) -> Any) =
            this.load(fn, Int::class)
        fun Func.loadI64(fn: ByteBuffer.(Int) -> Any) =
            this.load(fn, Long::class)
        fun Func.toUnsigned(fn: KFunction<*>) =
            this.addInsns(fn.invokeVirtual())
        fun Func.toUnsigned64(fn: KFunction<*>) =
            this.popExpecting(Int::class.java).toUnsigned(fn).push(Long::class.java)
        fun Func.i32ToI64() =
            this.popExpecting(Int::class.java).addInsns(InsnNode(Opcodes.I2L)).push(Long::class.java)
        when (insn) {
            is Node.Instr.I32Load ->
                func.loadI32(ByteBuffer::getInt)
            is Node.Instr.I64Load ->
                func.loadI64(ByteBuffer::getLong)
            is Node.Instr.F32Load ->
                func.load(ByteBuffer::getFloat, Float::class)
            is Node.Instr.F64Load ->
                func.load(ByteBuffer::getDouble, Double::class)
            is Node.Instr.I32Load8S ->
                func.loadI32(ByteBuffer::get)
            is Node.Instr.I32Load8U ->
                func.loadI32(ByteBuffer::get).toUnsigned(java.lang.Byte::toUnsignedInt)
            is Node.Instr.I32Load16S ->
                func.loadI32(ByteBuffer::getShort)
            is Node.Instr.I32Load16U ->
                func.loadI32(ByteBuffer::getShort).toUnsigned(java.lang.Short::toUnsignedInt)
            is Node.Instr.I64Load8S ->
                func.loadI32(ByteBuffer::get).i32ToI64()
            is Node.Instr.I64Load8U ->
                func.loadI32(ByteBuffer::get).toUnsigned64(java.lang.Byte::toUnsignedLong)
            is Node.Instr.I64Load16S ->
                func.loadI32(ByteBuffer::getShort).i32ToI64()
            is Node.Instr.I64Load16U ->
                func.loadI32(ByteBuffer::getShort).toUnsigned64(java.lang.Short::toUnsignedLong)
            is Node.Instr.I64Load32S ->
                func.loadI32(ByteBuffer::getInt).i32ToI64()
            is Node.Instr.I64Load32U ->
                func.loadI32(ByteBuffer::getInt).toUnsigned64(java.lang.Integer::toUnsignedLong)
            else -> throw IllegalArgumentException("Unknown load op $insn")
        }
    }

    override fun storeOp(func: Func, insn: Node.Instr.Args.AlignOffset) = func.popExpecting(memClass).let { func ->
        require(insn.offset <= Int.MAX_VALUE, { "Offsets > ${Int.MAX_VALUE} unsupported" }).let { this }
        fun <T> Func.store32(fn: ByteBuffer.(Int, T) -> ByteBuffer, inClass: KClass<*>) =
            // We add the index and then swap with the value already on the stack
            this.popExpecting(inClass.java).addInsns(
                insn.offset.toInt().const,
                InsnNode(Opcodes.SWAP),
                fn.reflect()!!.invokeVirtual()
            ).push(ByteBuffer::class.java)
        fun <T> Func.store64(fn: ByteBuffer.(Int, T) -> ByteBuffer, inClass: KClass<*>) =
            // We add the offset, dup_x2 to swap, and pop to get rid of the dup offset
            this.popExpecting(inClass.java).addInsns(
                insn.offset.toInt().const,
                InsnNode(Opcodes.DUP_X2),
                InsnNode(Opcodes.POP),
                fn.reflect()!!.invokeVirtual()
            ).push(ByteBuffer::class.java)
        fun Func.changeI64ToI32() =
            this.popExpecting(Long::class.java).push(Int::class.java)
        when (insn) {
            is Node.Instr.I32Store ->
                func.store32(ByteBuffer::putInt, Int::class)
            is Node.Instr.I64Store ->
                func.store64(ByteBuffer::putLong, Long::class)
            is Node.Instr.F32Store ->
                func.store32(ByteBuffer::putFloat, Float::class)
            is Node.Instr.F64Store ->
                func.store64(ByteBuffer::putDouble, Double::class)
            is Node.Instr.I32Store8 ->
                func.addInsns(InsnNode(Opcodes.I2B)).store32(ByteBuffer::put, Int::class)
            is Node.Instr.I32Store16 ->
                func.addInsns(InsnNode(Opcodes.I2S)).store32(ByteBuffer::putShort, Int::class)
            is Node.Instr.I64Store8 ->
                func.addInsns(InsnNode(Opcodes.L2I), InsnNode(Opcodes.I2B)).
                    changeI64ToI32().store32(ByteBuffer::put, Int::class)
            is Node.Instr.I64Store16 ->
                func.addInsns(InsnNode(Opcodes.L2I), InsnNode(Opcodes.I2S)).
                    changeI64ToI32().store32(ByteBuffer::putShort, Int::class)
            is Node.Instr.I64Store32 ->
                func.addInsns(InsnNode(Opcodes.L2I)).
                    changeI64ToI32().store32(ByteBuffer::putInt, Int::class)
            else -> throw IllegalArgumentException("Unknown store op $insn")
        }
    }

    companion object : ByteBufferMem()
}