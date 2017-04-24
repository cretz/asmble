package asmble.run.jvm.script

class TextEngineFactory : BaseEngineFactory() {
    override fun getEngineName() = "Asmble WASM Text Engine"
    override fun getExtensions() = listOf("wast", "wat")
    // Ref: https://github.com/WebAssembly/design/issues/981
    override fun getMimeTypes() = listOf("text/webassembly")
    override fun getScriptEngine() = TextEngine()
}