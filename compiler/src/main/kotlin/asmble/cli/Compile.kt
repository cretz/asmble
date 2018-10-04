package asmble.cli

import asmble.ast.Script
import asmble.compile.jvm.AsmToBinary
import asmble.compile.jvm.AstToAsm
import asmble.compile.jvm.ClsContext
import java.io.FileOutputStream

@Suppress("NAME_SHADOWING")
open class Compile : Command<Compile.Args>() {

    override val name = "compile"
    override val desc = "Compile WebAssembly to class file"

    override fun args(bld: Command.ArgsBuilder) = Args(
        inFile = bld.arg(
            name = "inFile",
            desc = "The wast or wasm WebAssembly file name. Can be '--' to read from stdin."
        ),
        inFormat = bld.arg(
            name = "inFormat",
            opt = "format",
            desc = "Either 'wast' or 'wasm' to describe format.",
            default = "<use file extension>"
        ),
        outClass = bld.arg(
            name = "outClass",
            desc = "The fully qualified class name."
        ),
        outFile = bld.arg(
            name = "outFile",
            opt = "out",
            desc = "The file name to output to. Can be '--' to write to stdout.",
            default = "<outClass.class>"
        ),
        name = bld.arg(
            name = "name",
            opt = "name",
            desc = "The name to use for this module. Will override the name on the module if present.",
            default = "<name on module or none>"
        ).takeIf { it != "<name on module or none>" },
        includeBinary = bld.flag(
            opt = "bindata",
            desc = "Embed the WASM binary as an annotation on the class.",
            lowPriority = true
        )
    ).also { bld.done() }

    override fun run(args: Args) {
        // Get format
        val inFormat =
            if (args.inFormat != "<use file extension>") args.inFormat
            else args.inFile.substringAfterLast('.', "<unknown>")
        val script = Translate().also { it.logger = logger }.inToAst(args.inFile, inFormat)
        val mod = (script.commands.firstOrNull() as? Script.Cmd.Module) ?:
            error("Only a single sexpr for (module) allowed")
        val outStream = when (args.outFile) {
            "<outClass.class>" -> FileOutputStream(args.outClass.substringAfterLast('.') + ".class")
            "--" -> System.out
            else -> FileOutputStream(args.outFile)
        }
        outStream.use { outStream ->
            val ctx = ClsContext(
                packageName = if (!args.outClass.contains('.')) "" else args.outClass.substringBeforeLast('.'),
                className = args.outClass.substringAfterLast('.'),
                mod = mod.module,
                modName = args.name ?: mod.name,
                logger = logger,
                includeBinary = args.includeBinary
            )
            AstToAsm.fromModule(ctx)
            outStream.write(AsmToBinary(logger = logger).fromClassNode(ctx.cls))
        }
    }

    data class Args(
        val inFile: String,
        val inFormat: String,
        val outClass: String,
        val outFile: String,
        val name: String?,
        val includeBinary: Boolean
    )

    companion object : Compile()
}