package asmble.run.jvm.script

import asmble.ast.Script
import asmble.io.BinaryToAst
import asmble.io.ByteReader
import java.nio.charset.Charset
import javax.script.ScriptContext

class BinaryEngine : BaseEngine() {
    override fun toAst(fileName: String, script: String, ctx: ScriptContext): Script {
        val charset = (ctx.getAttribute("asmble.in-charset") as? Charset) ?: Charsets.UTF_8
        val mod = BinaryToAst.toModule(ByteReader.InputStream(script.byteInputStream(charset)))
        return Script(listOf(Script.Cmd.Module(mod, null)))
    }
}