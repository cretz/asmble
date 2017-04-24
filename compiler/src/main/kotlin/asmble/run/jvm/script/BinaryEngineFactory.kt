package asmble.run.jvm.script

class BinaryEngineFactory : BaseEngineFactory() {
    override fun getEngineName() = "Asmble WASM Binary Engine"
    override fun getExtensions() = listOf("wasm")
    // Ref: https://github.com/WebAssembly/design/issues/981
    override fun getMimeTypes() = listOf("application/webassembly")
    override fun getScriptEngine() = BinaryEngine()
}