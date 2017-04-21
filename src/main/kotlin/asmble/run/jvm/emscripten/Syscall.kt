package asmble.run.jvm.emscripten

import asmble.run.jvm.annotation.WasmName
import asmble.util.get

class Syscall(val env: Env) {
    var fds: Map<Int, Stream> = mapOf(
        1 to Stream.OutputStream(env.out)
    )

    @WasmName("__syscall6")
    fun close(arg0: Int, arg1: Int): Int { TODO() }

    @WasmName("__syscall54")
    fun ioctl(which: Int, varargs: Int): Int {
        val fd = fd(env.memory.getInt(varargs))
        val op = env.memory.getInt(varargs + 4)
        return when (IoctlOp[op]) {
            IoctlOp.TCGETS, IoctlOp.TCSETS, IoctlOp.TIOCGWINSZ ->
                if (fd.tty == null) -Errno.ENOTTY.number else 0
            IoctlOp.TIOCGPGRP ->
                if (fd.tty == null) -Errno.ENOTTY.number else {
                    env.memory.putInt(env.memory.getInt(varargs + 8), 0)
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

    @WasmName("__syscall146")
    fun writev(which: Int, varargs: Int): Int {
        val fd = fd(env.memory.getInt(varargs))
        val iov = env.memory.getInt(varargs + 4)
        val iovcnt = env.memory.getInt(varargs + 8)
        return (0 until iovcnt).fold(0) { total, i ->
            val ptr = env.memory.getInt(iov + (i * 8))
            val len = env.memory.getInt(iov + (i * 8) + 4)
            if (len > 0) {
                fd.write(try {
                    ByteArray(len).also { env.memory.get(ptr, it) }
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
}
