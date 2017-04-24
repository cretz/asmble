package asmble.run.jvm.emscripten

import asmble.compile.jvm.Mem
import asmble.run.jvm.Module
import asmble.run.jvm.annotation.WasmName
import asmble.util.Logger
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Env(
    val logger: Logger,
    val staticBump: Int,
    val out: OutputStream
) : Logger by logger {
    fun alignTo16(num: Int) = Math.ceil(num / 16.0).toInt() * 16

    val memory = ByteBuffer.allocateDirect(256 * Mem.PAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    init {
        // Emscripten sets where "stack top" can start in mem at position 1024.
        // See https://github.com/WebAssembly/binaryen/issues/979
        val stackBase = alignTo16(staticBump + 1024 + 16)
        val stackTop = stackBase + TOTAL_STACK
        memory.putInt(1024, stackTop)
    }

    internal fun readCStringBytes(ptr: Int) = ByteArrayOutputStream().let { bos ->
        var ptr = ptr
        while (true) {
            val byte = memory.get(ptr++)
            if (byte == 0.toByte()) break
            bos.write(byte.toInt())
        }
        bos.toByteArray()
    }

    internal fun readCString(ptr: Int) = readCStringBytes(ptr).toString(Charsets.ISO_8859_1)

    fun abort() { TODO() }

    @WasmName("__lock")
    fun lock(arg: Int) { TODO() }

    fun sbrk(increment: Int): Int { TODO() }

    @WasmName("__unlock")
    fun unlock(arg: Int) { TODO() }

    companion object {
        const val TOTAL_STACK = 5242880
        const val TOTAL_MEMORY = 16777216

        val subModules = listOf(::Stdio, ::Syscall)

        fun module(
            logger: Logger,
            staticBump: Int,
            out: OutputStream
        ): Module = Env(logger, staticBump, out).let { env ->
            Module.Composite(subModules.fold(listOf(Module.Native(env))) { list, subMod ->
                list + Module.Native(subMod(env))
            })
        }
    }
}