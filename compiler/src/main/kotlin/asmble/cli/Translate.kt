package asmble.cli

import asmble.ast.SExpr
import asmble.ast.Script
import asmble.io.*
import asmble.util.toIntExact
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

open class Translate : Command<Translate.Args>() {

    override val name = "translate"
    override val desc = "Translate WebAssembly from one form to another"

    override fun args(bld: Command.ArgsBuilder) = Args(
        inFile = bld.arg(
            name = "inFile",
            desc = "The wast or wasm WebAssembly file name. Can be '--' to read from stdin."
        ),
        inFormat = bld.arg(
            name = "inFormat",
            opt = "in",
            desc = "Either 'wast' or 'wasm' to describe format.",
            default = "<use file extension>"
        ),
        outFile = bld.arg(
            name = "outFile",
            desc = "The wast or wasm WebAssembly file name. Can be '--' to write to stdout.",
            default = "--"
        ),
        outFormat = bld.arg(
            name = "outFormat",
            opt = "out",
            desc = "Either 'wast' or 'wasm' to describe format.",
            default = "<use file extension or wast for stdout>"
        ),
        compact = bld.flag(
            opt = "compact",
            desc = "If set for wast out format, will be compacted.",
            lowPriority = true
        )
    ).also { bld.done() }

    override fun run(args: Args) {
        // Get format
        val inFormat =
            if (args.inFormat != "<use file extension>") args.inFormat
            else args.inFile.substringAfterLast('.', "<unknown>")
        val script = inToAst(args.inFile, inFormat)
        val outFormat =
            if (args.outFormat != "<use file extension or wast for stdout>") args.outFormat
            else if (args.outFile == "--") "wast"
            else args.outFile.substringAfterLast('.', "<unknown>")
        astToOut(args.outFile, outFormat, args.compact, script)
    }

    fun inToAst(inFile: String, inFormat: String): Script {
        val inBytes =
            if (inFile == "--") System.`in`.use { it.readBytes() }
            else File(inFile).let { f -> FileInputStream(f).use { it.readBytes(f.length().toIntExact()) } }
        return when (inFormat) {
            "wast" -> StrToSExpr.parse(inBytes.toString(Charsets.UTF_8)).let { res ->
                when (res) {
                    is StrToSExpr.ParseResult.Error -> error("Error [${res.pos.line}:${res.pos.char}] - ${res.pos}")
                    is StrToSExpr.ParseResult.Success -> SExprToAst.toScript(SExpr.Multi(res.vals))
                }
            }
            "wasm" ->
                Script(listOf(Script.Cmd.Module(BinaryToAst(logger = logger).toModule(
                    ByteReader.InputStream(inBytes.inputStream())), null)))
            else -> error("Unknown in format '$inFormat'")
        }
    }

    fun astToOut(outFile: String, outFormat: String, compact: Boolean, script: Script) {
        val outStream =
            if (outFile == "--") System.out
            else FileOutputStream(outFile)
        outStream.use { outStream ->
            when (outFormat) {
                "wast" -> {
                    val sexprToStr = if (compact) SExprToStr.Compact else SExprToStr
                    val sexprs = AstToSExpr.fromScript(script)
                    outStream.write(sexprToStr.fromSExpr(*sexprs.toTypedArray()).toByteArray())
                }
                "wasm" -> {
                    val mod = (script.commands.firstOrNull() as? Script.Cmd.Module)?.module ?:
                    error("Output to WASM requires input be just a single module")
                    AstToBinary.fromModule(ByteWriter.OutputStream(outStream), mod)
                }
                else -> error("Unknown out format '$outFormat'")
            }
        }
    }

    data class Args(
        val inFile: String,
        val inFormat: String,
        val outFile: String,
        val outFormat: String,
        val compact: Boolean
    )

    companion object : Translate()
}