package asmble.cli

import asmble.compile.jvm.AsmToBinary
import asmble.compile.jvm.Linker
import java.io.FileOutputStream

open class Link : Command<Link.Args>() {

    override val name = "link"
    override val desc = "Link WebAssembly modules in a single class file. TODO: not done"

    override fun args(bld: Command.ArgsBuilder) = Args(
        outFile = bld.arg(
            name = "outFile",
            opt = "out",
            desc = "The file name to output to. Can be '--' to write to stdout.",
            default = "<outClass.class>"
        ),
        modules = bld.args(
            name = "modules",
            desc = "The fully qualified class name of the modules on the classpath to link. A module name can be" +
                " added after an equals sign to set/override the existing module name."
        ),
        outClass = bld.arg(
            name = "outClass",
            desc = "The fully qualified class name."
        ),
        defaultMaxMem = bld.arg(
            name = "defaultMaxMem",
            opt = "maxmem",
            desc = "The max number of pages to build memory with when not specified by the module/import.",
            default = "10"
        ).toInt()
    ).also { bld.done() }

    override fun run(args: Args) {
        val outStream = when (args.outFile) {
            "<outClass.class>" -> FileOutputStream(args.outClass.substringAfterLast('.') + ".class")
            "--" -> System.out
            else -> FileOutputStream(args.outFile)
        }
        outStream.use { outStream ->
            val ctx = Linker.Context(
                classes = args.modules.map { module ->
                    val pieces = module.split('=', limit = 2)
                    Linker.ModuleClass(
                        cls = Class.forName(pieces.first()),
                        overrideName = pieces.getOrNull(1)
                    )
                },
                className = args.outClass,
                defaultMaxMemPages = args.defaultMaxMem
            )
            Linker.link(ctx)
            outStream.write(AsmToBinary.fromClassNode(ctx.cls))
        }
    }

    data class Args(
        val modules: List<String>,
        val outClass: String,
        val outFile: String,
        val defaultMaxMem: Int
    )

    companion object : Link()
}