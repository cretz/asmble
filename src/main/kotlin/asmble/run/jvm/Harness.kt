package asmble.run.jvm

import java.io.PrintWriter

open class Harness(val out: PrintWriter) {

    // We're not as evil as WASM:
    //  https://github.com/WebAssembly/spec/blob/7c62b17f547b80c9c717cc6ef3a8aba1e04e4bcb/test/harness/index.js#L84
    val global get() = 555

    fun print() { }

    fun print(arg0: Int) { out.println("$arg0 : i32") }

    companion object : Harness(PrintWriter(System.out, true))
}