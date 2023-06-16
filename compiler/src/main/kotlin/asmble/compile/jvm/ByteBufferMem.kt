package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

open class ByteBufferMem(val direct: Boolean = true) : Mem {
    override val memType = ByteBuffer::class.ref

    override fun limitAndCapacity(instance: Any) =
        if (instance !is ByteBuffer) error("Unrecognized memory instance: $instance")
        else instance.limit() to instance.capacity()

    override fun create(func: Func) = func.popExpecting(Int::class.ref).addInsns(
        (if (direct) ByteBuffer::allocateDirect else ByteBuffer::allocate).invokeStatic()
    ).push(memType)

    override fun init(func: Func, initial: Int) = func.popExpecting(memType).addInsns(
        // Set the limit to initial
        (initial * Mem.PAGE_SIZE).const,
        forceFnType<ByteBuffer.(Int) -> Buffer>(Buffer::limit).invokeVirtual(),
        TypeInsnNode(Opcodes.CHECKCAST, ByteBuffer::class.ref.asmName),
        // Set it to use little endian
        ByteOrder::LITTLE_ENDIAN.getStatic(),
        forceFnType<ByteBuffer.(ByteOrder) -> ByteBuffer>(ByteBuffer::order).invokeVirtual()
    ).push(ByteBuffer::class.ref)

    override fun data(func: Func, bytes: ByteArray, buildOffset: (Func) -> Func) =
        // Sadly there is no absolute bulk put, so we need to fake one. Ref:
        // http://stackoverflow.com/questions/15409727/missing-some-absolute-methods-on-bytebuffer.
        // To maintain some thread safety and what not, we're going to duplicate the buffer
        // then set the position, then put the whole array. Technically there is an opportunity
        // for performance improvement by knowing this is the first data call and it's offset
        // is 0, we can not even call duplicate/position or any of that, but it's negligible for now.
        // There is also an opportunity for performance improvement if only one byte was given
        // where we could call put directly, but it too is negligible for now.
        // Note, with this approach, the mem not be left on the stack for future data() calls which is fine.
        func.popExpecting(memType).
            addInsns(ByteBuffer::duplicate.invokeVirtual()).
            let(buildOffset).popExpecting(Int::class.ref).
            addInsns(
                forceFnType<ByteBuffer.(Int) -> Buffer>(Buffer::position).invokeVirtual(),
                TypeInsnNode(Opcodes.CHECKCAST, memType.asmName)
            ).addInsns(
                // We're going to do this as an LDC string in ISO-8859 and read it back at runtime. However,
                // due to JVM limits, we can't have a string > 65536 chars. We chunk into 16300 because when
                // converting to UTF8 const it can be up to 4 bytes per char, so this makes sure it doesn't
                // overflow.
                bytes.chunked(16300).flatMap { bytes ->
                    sequenceOf(
                        LdcInsnNode(bytes.toString(Charsets.ISO_8859_1)),
                        LdcInsnNode("ISO-8859-1"),
                        // Ug, can't do func refs on native types here...
                        MethodInsnNode(Opcodes.INVOKEVIRTUAL, String::class.ref.asmName,
                            "getBytes", "(Ljava/lang/String;)[B", false),
                        0.const,
                        bytes.size.const,
                        forceFnType<ByteBuffer.(ByteArray, Int, Int) -> ByteBuffer>(ByteBuffer::put).invokeVirtual()
                    )
                }.toList()
            ).addInsns(
                InsnNode(Opcodes.POP)
            )

    override fun currentMemory(ctx: FuncContext, func: Func) = func.popExpecting(memType).addInsns(
        forceFnType<ByteBuffer.() -> Int>(ByteBuffer::limit).invokeVirtual(),
        Mem.PAGE_SIZE.const,
        InsnNode(Opcodes.IDIV)
    ).push(Int::class.ref)

    override fun growMemory(ctx: FuncContext, func: Func) = getOrCreateGrowMemoryMethod(ctx, func).let { method ->
        func.popExpecting(Int::class.ref).popExpecting(memType).addInsns(
            // This is complicated enough to need a synthetic method
            MethodInsnNode(Opcodes.INVOKESTATIC, ctx.cls.thisRef.asmName, method.name, method.desc, false)
        ).push(Int::class.ref)
    }

    fun getOrCreateGrowMemoryMethod(ctx: FuncContext, func: Func): MethodNode =
        ctx.cls.cls.methods.find { (it as? MethodNode)?.name == "\$\$growMemory" }?.let { it as MethodNode } ?: run {
            val okLim = LabelNode()
            val node = MethodNode(
                Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
                "\$\$growMemory", "(Ljava/nio/ByteBuffer;I)I", null, null
            ).addInsns(
                VarInsnNode(Opcodes.ALOAD, 0), // [mem]
                forceFnType<ByteBuffer.() -> Int>(ByteBuffer::limit).invokeVirtual(), // [lim]
                InsnNode(Opcodes.DUP), // [lim, lim]
                VarInsnNode(Opcodes.ALOAD, 0), // [lim, lim, mem]
                InsnNode(Opcodes.SWAP), // [lim, mem, lim]
                // We have to use longs because the page multiplication can overflow an int
                InsnNode(Opcodes.I2L), // [lim, mem, limL]
                VarInsnNode(Opcodes.ILOAD, 1), // [lim, mem, limL, pagedelt]
                InsnNode(Opcodes.I2L), // [lim, mem, limL, pagedeltL]
                Mem.PAGE_SIZE.toLong().const, // [lim, mem, limL, pagedeltL, pagesizeL]
                InsnNode(Opcodes.LMUL), // [lim, mem, limL, memdeltL]
                InsnNode(Opcodes.LADD), // [lim, mem, newlimL]
                InsnNode(Opcodes.DUP2), // [lim, mem, newlimL, newlimL]
                VarInsnNode(Opcodes.ALOAD, 0), // [lim, mem, newlimL, newlimL, mem]
                ByteBuffer::capacity.invokeVirtual(), // [lim, mem, newlimL, newlimL, cap]
                InsnNode(Opcodes.I2L), // [lim, mem, newlimL, newlimL, capL]
                InsnNode(Opcodes.LCMP), // [lim, mem, newlimL, cmpres]
                JumpInsnNode(Opcodes.IFLE, okLim), // [lim, mem, newlimL]
                InsnNode(Opcodes.POP2), InsnNode(Opcodes.POP2),
                (-1).const,
                InsnNode(Opcodes.IRETURN),
                okLim, // [lim, mem, newlimL]
                InsnNode(Opcodes.L2I), // [lim, mem, newlim]
                forceFnType<ByteBuffer.(Int) -> Buffer>(Buffer::limit).invokeVirtual(), // [lim, mem]
                InsnNode(Opcodes.POP), // [lim]
                Mem.PAGE_SIZE.const, // [lim, pagesize]
                InsnNode(Opcodes.IDIV), // [limpages]
                InsnNode(Opcodes.IRETURN)
            )
            ctx.cls.cls.methods.add(node)
            node
        }

    override fun loadOp(ctx: FuncContext, func: Func, insn: Node.Instr.Args.AlignOffset): Func {
        // Ug, some tests expect this to be a runtime failure so we feature flagged it
        if (ctx.cls.eagerFailLargeMemOffset)
            require(insn.offset <= Int.MAX_VALUE, { "Offsets > ${Int.MAX_VALUE} unsupported" }).let { this }
        fun Func.load(fn: ByteBuffer.(Int) -> Any, retClass: KClass<*>) =
            this.popExpecting(Int::class.ref).let { func ->
                // No offset means we'll access it directly
                (if (insn.offset == 0L) func else {
                    // Since some things want runtime failure, we'll give it them via -1
                    if (insn.offset > Int.MAX_VALUE) func.addInsns(InsnNode(Opcodes.POP), (-1).const) else {
                        func.addInsns(insn.offset.toInt().const).let { func ->
                            // Simple add if no bounds check
                            if (!ctx.cls.preventMemIndexOverflow) func.addInsns(InsnNode(Opcodes.IADD)) else {
                                // Otherwise, do an addExact
                                func.addInsns(forceFnType<(Int, Int) -> Int>(Math::addExact).invokeStatic())
                            }
                        }
                    }
                }).popExpecting(memType).addInsns((fn as KFunction<*>).invokeVirtual())
            }.push(retClass.ref)
        fun Func.loadI32(fn: ByteBuffer.(Int) -> Any) =
            this.load(fn, Int::class)
        fun Func.loadI64(fn: ByteBuffer.(Int) -> Any) =
            this.load(fn, Long::class)
        /* Ug: https://youtrack.jetbrains.com/issue/KT-17064
        fun Func.toUnsigned(fn: KFunction<*>) =
            this.addInsns(fn.invokeVirtual())
        fun Func.toUnsigned64(fn: KFunction<*>) =
            this.popExpecting(Int::class.ref).toUnsigned(fn).push(Long::class.ref)
        */
        fun Func.toUnsigned(owner: KClass<*>, methodName: String, inClass: KClass<*>, outClass: KClass<*>) =
            this.addInsns(MethodInsnNode(Opcodes.INVOKESTATIC, owner.ref.asmName, methodName,
                Type.getMethodDescriptor(outClass.ref.asm, inClass.ref.asm), false))
        fun Func.toUnsigned32(owner: KClass<*>, methodName: String, inClass: KClass<*>) =
            this.popExpecting(Int::class.ref).toUnsigned(owner, methodName, inClass, Int::class).push(Int::class.ref)
        fun Func.toUnsigned64(owner: KClass<*>, methodName: String, inClass: KClass<*>) =
            this.popExpecting(Int::class.ref).toUnsigned(owner, methodName, inClass, Long::class).push(Long::class.ref)
        fun Func.i32ToI64() =
            this.popExpecting(Int::class.ref).addInsns(InsnNode(Opcodes.I2L)).push(Long::class.ref)
        // Had to move this in here instead of as first expr because of https://youtrack.jetbrains.com/issue/KT-8689
        return when (insn) {
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
                func.loadI32(ByteBuffer::get).toUnsigned32(java.lang.Byte::class, "toUnsignedInt", Byte::class)
            is Node.Instr.I32Load16S ->
                func.loadI32(ByteBuffer::getShort)
            is Node.Instr.I32Load16U ->
                func.loadI32(ByteBuffer::getShort).toUnsigned32(java.lang.Short::class, "toUnsignedInt", Short::class)
            is Node.Instr.I64Load8S ->
                func.loadI32(ByteBuffer::get).i32ToI64()
            is Node.Instr.I64Load8U ->
                func.loadI32(ByteBuffer::get).toUnsigned64(java.lang.Byte::class, "toUnsignedLong", Byte::class)
            is Node.Instr.I64Load16S ->
                func.loadI32(ByteBuffer::getShort).i32ToI64()
            is Node.Instr.I64Load16U ->
                func.loadI32(ByteBuffer::getShort).toUnsigned64(java.lang.Short::class, "toUnsignedLong", Short::class)
            is Node.Instr.I64Load32S ->
                func.loadI32(ByteBuffer::getInt).i32ToI64()
            is Node.Instr.I64Load32U ->
                func.loadI32(ByteBuffer::getInt).toUnsigned64(java.lang.Integer::class, "toUnsignedLong", Int::class)
            else -> throw IllegalArgumentException("Unknown load op $insn")
        }
    }

    override fun storeOp(ctx: FuncContext, func: Func, insn: Node.Instr.Args.AlignOffset) =
        func.let { func ->
            // Ug, some tests expect this to be a runtime failure so we feature flagged it
            if (ctx.cls.eagerFailLargeMemOffset)
                require(insn.offset <= Int.MAX_VALUE, { "Offsets > ${Int.MAX_VALUE} unsupported" }).let { this }
            fun Func.store(fn: MethodInsnNode, inClass: KClass<*>) =
                // Stack comes in as mem + index + value which is good...
                // However, if the offset is not 0, we have to add to index which means
                // a swap, add, swap back.
                if (insn.offset == 0L) this else {
                    // Swap, do things, swap back
                    this.stackSwap().let { func ->
                        // Since some things want runtime failure, we'll give it them via -1
                        if (insn.offset > Int.MAX_VALUE) func.addInsns(InsnNode(Opcodes.POP), (-1).const) else {
                            // Otherwise, just add to the offset
                            func.addInsns(insn.offset.toInt().const).let { func ->
                                // Simple add if no bounds check
                                if (!ctx.cls.preventMemIndexOverflow) func.addInsns(InsnNode(Opcodes.IADD)) else {
                                    // Otherwise, do an addExact which does bounds check
                                    func.addInsns(forceFnType<(Int, Int) -> Int>(Math::addExact).invokeStatic())
                                }
                            }
                        }
                    }.stackSwap()
                }.let { func ->
                    // Now we can do the call since we know it's mem + index-with-offset + value
                    func.popExpecting(inClass.ref).
                        popExpecting(Int::class.ref).
                        popExpecting(memType).
                        addInsns(fn).
                        push(ByteBuffer::class.ref)
                }
            // Ug, I hate these as strings but can't introspect Kotlin overloads
            fun bufStoreFunc(name: String, valType: KClass<*>) =
                MethodInsnNode(Opcodes.INVOKEVIRTUAL, ByteBuffer::class.ref.asmName, name,
                    ByteBuffer::class.ref.asMethodRetDesc(Int::class.ref, valType.ref), false)
            fun Func.changeI64ToI32() =
                this.popExpecting(Long::class.ref).push(Int::class.ref)
            when (insn) {
                is Node.Instr.I32Store ->
                    func.store(bufStoreFunc("putInt", Int::class), Int::class)
                is Node.Instr.I64Store ->
                    func.store(bufStoreFunc("putLong", Long::class), Long::class)
                is Node.Instr.F32Store ->
                    func.store(bufStoreFunc("putFloat", Float::class), Float::class)
                is Node.Instr.F64Store ->
                    func.store(bufStoreFunc("putDouble", Double::class), Double::class)
                is Node.Instr.I32Store8 ->
                    func.addInsns(InsnNode(Opcodes.I2B)).store(bufStoreFunc("put", Byte::class), Int::class)
                is Node.Instr.I32Store16 ->
                    func.addInsns(InsnNode(Opcodes.I2S)).store(bufStoreFunc("putShort", Short::class), Int::class)
                is Node.Instr.I64Store8 ->
                    func.addInsns(InsnNode(Opcodes.L2I), InsnNode(Opcodes.I2B)).
                        changeI64ToI32().store(bufStoreFunc("put", Byte::class), Int::class)
                is Node.Instr.I64Store16 ->
                    func.addInsns(InsnNode(Opcodes.L2I), InsnNode(Opcodes.I2S)).
                        changeI64ToI32().store(bufStoreFunc("putShort", Short::class), Int::class)
                is Node.Instr.I64Store32 ->
                    func.addInsns(InsnNode(Opcodes.L2I)).
                        changeI64ToI32().store(bufStoreFunc("putInt", Int::class), Int::class)
                else -> throw IllegalArgumentException("Unknown store op $insn")
            }
        }

    override val storeLeavesMemOnStack get() = true

    companion object : ByteBufferMem()
}
