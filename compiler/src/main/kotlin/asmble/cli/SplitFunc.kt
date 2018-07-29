package asmble.cli

import asmble.ast.Node
import asmble.ast.Script
import asmble.ast.opt.SplitLargeFunc

open class SplitFunc : Command<SplitFunc.Args>() {
    override val name = "split-func"
    override val desc = "Split a WebAssembly function into two"

    override fun args(bld: Command.ArgsBuilder) = Args(
        inFile = bld.arg(
            name = "inFile",
            desc = "The wast or wasm WebAssembly file name. Can be '--' to read from stdin."
        ),
        funcName = bld.arg(
            name = "funcName",
            desc = "The name (or '#' + function space index) of the function to split"
        ),
        inFormat = bld.arg(
            name = "inFormat",
            opt = "in",
            desc = "Either 'wast' or 'wasm' to describe format.",
            default = "<use file extension>",
            lowPriority = true
        ),
        outFile = bld.arg(
            name = "outFile",
            opt = "outFile",
            desc = "The wast or wasm WebAssembly file name. Can be '--' to write to stdout.",
            default = "<inFileSansExt.split.wasm or stdout>",
            lowPriority = true
        ),
        outFormat = bld.arg(
            name = "outFormat",
            opt = "out",
            desc = "Either 'wast' or 'wasm' to describe format.",
            default = "<use file extension or wast for stdout>",
            lowPriority = true
        ),
        compact = bld.flag(
            opt = "compact",
            desc = "If set for wast out format, will be compacted.",
            lowPriority = true
        ),
        minInsnSetLength = bld.arg(
            name = "minInsnSetLength",
            opt = "minLen",
            desc = "The minimum number of instructions allowed for the split off function.",
            default = "5",
            lowPriority = true
        ).toInt(),
        maxInsnSetLength = bld.arg(
            name = "maxInsnSetLength",
            opt = "maxLen",
            desc = "The maximum number of instructions allowed for the split off function.",
            default = "40",
            lowPriority = true
        ).toInt(),
        maxNewFuncParamCount = bld.arg(
            name = "maxNewFuncParamCount",
            opt = "maxParams",
            desc = "The maximum number of params allowed for the split off function.",
            default = "30",
            lowPriority = true
        ).toInt(),
        attempts = bld.arg(
            name = "attempts",
            opt = "attempts",
            desc = "The number of attempts to perform.",
            default = "1",
            lowPriority = true
        ).toInt()
    ).also { bld.done() }

    override fun run(args: Args) {
        // Load the mod
        val translate = Translate().also { it.logger = logger }
        val inFormat =
            if (args.inFormat != "<use file extension>") args.inFormat
            else args.inFile.substringAfterLast('.', "<unknown>")
        val script = translate.inToAst(args.inFile, inFormat)
        var mod = (script.commands.firstOrNull() as? Script.Cmd.Module)?.module ?: error("Only a single module allowed")

        // Do attempts
        val splitter = SplitLargeFunc(
            minSetLength = args.minInsnSetLength,
            maxSetLength = args.maxInsnSetLength,
            maxParamCount = args.maxNewFuncParamCount
        )
        for (attempt in 0 until args.attempts) {
            // Find the function
            var index = mod.names?.funcNames?.toList()?.find { it.second == args.funcName }?.first
            if (index == null && args.funcName.startsWith('#')) index = args.funcName.drop(1).toInt()
            val origFunc = index?.let {
                mod.funcs.getOrNull(it - mod.imports.count { it.kind is Node.Import.Kind.Func })
            } ?: error("Unable to find func")

            // Split it
            val results = splitter.apply(mod, index)
            if (results == null) {
                logger.warn { "No instructions after attempt $attempt" }
                break
            }
            val (splitMod, insnsSaved) = results
            val newFunc = splitMod.funcs[index - mod.imports.count { it.kind is Node.Import.Kind.Func }]
            val splitFunc = splitMod.funcs.last()
            logger.warn {
                "Split complete, from func with ${origFunc.instructions.size} insns to a func " +
                    "with ${newFunc.instructions.size} insns + delegated func " +
                    "with ${splitFunc.instructions.size} insns and ${splitFunc.type.params.size} params, " +
                    "saved $insnsSaved insns"
            }
            mod = splitMod
        }

        // Write it
        val outFile = when {
            args.outFile != "<inFileSansExt.split.wasm or stdout>" -> args.outFile
            args.inFile == "--" -> "--"
            else -> args.inFile.replaceAfterLast('.', "split." + args.inFile.substringAfterLast('.'))
        }
        val outFormat = when {
            args.outFormat != "<use file extension or wast for stdout>" -> args.outFormat
            outFile == "--" -> "wast"
            else -> outFile.substringAfterLast('.', "<unknown>")
        }
        translate.astToOut(outFile, outFormat, args.compact,
            Script(listOf(Script.Cmd.Module(mod, mod.names?.moduleName))))
    }

    data class Args(
        val inFile: String,
        val inFormat: String,
        val funcName: String,
        val outFile: String,
        val outFormat: String,
        val compact: Boolean,
        val minInsnSetLength: Int,
        val maxInsnSetLength: Int,
        val maxNewFuncParamCount: Int,
        val attempts: Int
    )

    companion object : SplitFunc()
}