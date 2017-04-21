package asmble.run.jvm.emscripten

import asmble.compile.jvm.Mem
import asmble.run.jvm.annotation.WasmName
import asmble.util.Logger
import asmble.util.get
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Env(
    val logger: Logger,
    val out: OutputStream
) : Logger by logger {
    fun alignTo16(num: Int) = Math.ceil(num / 16.0).toInt() * 16

    val memory = ByteBuffer.allocateDirect(256 * Mem.PAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    var fds: Map<Int, Stream> = mapOf(
        1 to Stream.OutputStream(out)
    )

    init {
        // Emscripten sets where "stack top" can start in mem at position 1024.
        // TODO: Waiting for https://github.com/WebAssembly/binaryen/issues/979
        val staticBump = 4044
        val stackBase = alignTo16(staticBump + 1024 + 16)
        val stackTop = stackBase + TOTAL_STACK
        // We have to set some values like Emscripten
        memory.putInt(1024, stackTop)
    }

    fun abort() { TODO() }

    @WasmName("__syscall6")
    fun close(arg0: Int, arg1: Int): Int { TODO() }

    @WasmName("__syscall54")
    fun ioctl(which: Int, varargs: Int): Int {
        val fd = fd(memory.getInt(varargs))
        val op = memory.getInt(varargs + 4)
        return when (IoctlOp[op]) {
            IoctlOp.TCGETS, IoctlOp.TCSETS, IoctlOp.TIOCGWINSZ ->
                if (fd.tty == null) -Errno.ENOTTY.number else 0
            IoctlOp.TIOCGPGRP ->
                if (fd.tty == null) -Errno.ENOTTY.number else {
                    memory.putInt(memory.getInt(varargs + 8), 0)
                    0
                }
            IoctlOp.TIOCSPGRP ->
                if (fd.tty == null) -Errno.ENOTTY.number else -Errno.EINVAL.number
            IoctlOp.FIONREAD ->
                if (fd.tty == null) -Errno.ENOTTY.number else TODO("ioctl FIONREAD")
            null ->
                error("Unrecognized op: $op")
        }
    }

    @WasmName("__syscall140")
    fun llseek(arg0: Int, arg1: Int): Int { TODO() }

    @WasmName("__lock")
    fun lock(arg: Int) { TODO() }

    fun sbrk(increment: Int): Int { TODO() }

    @WasmName("__unlock")
    fun unlock(arg: Int) { TODO() }

    @WasmName("__syscall146")
    fun writev(which: Int, varargs: Int): Int {
        val fd = fd(memory.getInt(varargs))
        val iov = memory.getInt(varargs + 4)
        val iovcnt = memory.getInt(varargs + 8)
        return (0 until iovcnt).fold(0) { total, i ->
            val ptr = memory.getInt(iov + (i * 8))
            val len = memory.getInt(iov + (i * 8) + 4)
            if (len > 0) {
                fd.write(try {
                    ByteArray(len).also { memory.get(ptr, it) }
                } catch (e: Exception) {
                    // TODO: set errno?
                    return -1
                })
            }
            total + len
        }
    }

    private fun fd(v: Int) = fds[v] ?: Errno.EBADF.raise()

    enum class IoctlOp(val number: Int) {
        TCGETS(0x5401),
        TCSETS(0x5402),
        TIOCGPGRP(0x540F),
        TIOCSPGRP(0x5410),
        FIONREAD(0x541B),
        TIOCGWINSZ(0x5413);

        companion object {
            val byNumber = IoctlOp.values().associateBy { it.number }
            operator fun get(number: Int) = byNumber[number]
        }
    }

    companion object {
        const val TOTAL_STACK = 5242880
        const val TOTAL_MEMORY = 16777216
        const val GLOBAL_BASE = 1024
    }
}