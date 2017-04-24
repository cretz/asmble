package asmble.run.jvm.emscripten

sealed class Tty {
    class OutputStream(val os: java.io.OutputStream) : Tty() {

    }
}