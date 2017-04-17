package asmble.run.jvm.script

import javax.script.ScriptEngineFactory

abstract class BaseEngineFactory : ScriptEngineFactory {
    // TODO: have gradle replace this on build
    override fun getEngineVersion() = "0.1.0"
    // We make this empty because we don't want people looking up the
    // engine this way since there are multiple
    override fun getNames() = emptyList<String>()
    override fun getLanguageName() = "WebAssembly"
    override fun getLanguageVersion() = "1"
    override fun getParameter(key: String?) = null
    override fun getMethodCallSyntax(obj: String?, m: String?, vararg args: String?) = "<no method call syntax>"
    override fun getOutputStatement(toDisplay: String?) = "<no output statement>"
    override fun getProgram(vararg statements: String?) = TODO()
}
