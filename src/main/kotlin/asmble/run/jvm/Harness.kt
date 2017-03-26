package asmble.run.jvm

import java.io.PrintWriter

open class Harness(val out: PrintWriter) {

    fun print(arg0: Int) { out.println("$arg0 : i32") }

    companion object : Harness(PrintWriter(System.out, true))
}