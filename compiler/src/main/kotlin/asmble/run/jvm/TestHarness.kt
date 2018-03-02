package asmble.run.jvm

import asmble.annotation.WasmExport
import asmble.annotation.WasmExternalKind
import asmble.compile.jvm.Mem
import java.io.PrintWriter
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

open class TestHarness(val out: PrintWriter) {

    // WASM is evil, not me:
    //  https://github.com/WebAssembly/spec/blob/6a01dab6d29b7c2b5dfd3bb3879bbd6ab76fd5dc/interpreter/host/import/spectest.ml#L12
    val global_i32 = 666
    val global_i64 = 666L
    val global_f32 = 666.6f
    val global_f64 = 666.6
    val table = arrayOfNulls<MethodHandle>(10)
    val memory = ByteBuffer.
        allocateDirect(2 * Mem.PAGE_SIZE).
        order(ByteOrder.LITTLE_ENDIAN).
        limit(Mem.PAGE_SIZE) as ByteBuffer

    // Note, we have all of these overloads because my import method
    // resolver is simple right now and only finds exact methods via
    // mh-lookup-bind. It does not support varargs, boxing, or any of
    // that currently.
    fun print() { }
    fun print_i32(arg0: Int) { out.println("$arg0 : i32") }
    fun print_i64(arg0: Long) { out.println("$arg0 : i64") }
    fun print_f32(arg0: Float) { out.printf("%#.0f : f32", arg0).println() }
    fun print_f64(arg0: Double) { out.printf("%#.0f : f64", arg0).println() }
    fun print_i32_f32(arg0: Int, arg1: Float) { print(arg0); print(arg1) }
    fun print_f64_f64(arg0: Double, arg1: Double) { print(arg0); print(arg1) }

    companion object : TestHarness(PrintWriter(System.out, true))
}