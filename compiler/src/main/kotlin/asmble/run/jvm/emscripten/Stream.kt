package asmble.run.jvm.emscripten

sealed class Stream {
    open val tty: Tty? = null

    abstract fun write(bytes: ByteArray)

    class OutputStream(val os: java.io.OutputStream) : Stream() {
        override val tty by lazy { Tty.OutputStream(os) }

        override fun write(bytes: ByteArray) {
            os.write(bytes)
        }
    }
}