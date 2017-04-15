package asmble.compile.jvm

import asmble.ast.Node

interface Mem {
    // The class to accept as "memory"
    val memType: TypeRef

    // Make sure the given instance matches the expected for import
    fun assertValidImport(instance: Any, expected: Node.Type.Memory)

    // Caller can trust the max is on the stack as an i32 in bytes. The result
    // must be put on the stack
    fun create(func: Func): Func

    // Caller can trust the mem instance is on the stack and must handle it. If
    // it's already there after call anyways, this can leave the mem inst on the
    // stack and it will be reused or popped. Note, this is not called for
    // imported memory.
    fun init(func: Func, initial: Int): Func

    // Caller can trust the mem instance is on the stack, buildOffset puts an i32
    // offset on the stack. If it's already there after call anyways, this can
    // leave the mem inst on the stack and it will be reused or popped.
    fun data(func: Func, bytes: ByteArray, buildOffset: (Func) -> Func): Func

    // Caller can trust the mem instance is on the stack.
    fun currentMemory(ctx: FuncContext, func: Func): Func

    // Caller can trust the mem instance and then i32 delta page count is on the
    // stack. Per the spec, the result needs to be the previous page count or -1
    // on failure (i.e. do not throw).
    fun growMemory(ctx: FuncContext, func: Func): Func

    // Caller can trust the mem instance is on the stack
    fun loadOp(ctx: FuncContext, func: Func, insn: Node.Instr.Args.AlignOffset): Func

    // Caller can trust the mem instance is on the stack followed
    // by the value. If it's already there after call anyways, this can
    // leave the mem inst on the stack and it will be reused or popped.
    fun storeOp(ctx: FuncContext, func: Func, insn: Node.Instr.Args.AlignOffset): Func

    companion object {
        const val PAGE_SIZE = 65536
    }
}