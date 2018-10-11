package asmble.cli

import asmble.compile.jvm.javaIdent
import asmble.run.jvm.Module

open class Invoke : ScriptCommand<Invoke.Args>() {

    override val name = "invoke"
    override val desc = "Invoke WebAssembly function"

    override fun args(bld: Command.ArgsBuilder) = Args(
        scriptArgs = scriptArgs(bld),
        module = bld.arg(
            name = "module",
            opt = "mod",
            desc = "The module name to run. If it's a JVM class, it must have a no-arg constructor.",
            default = "<last-in-entry>"
        ),
        export = bld.arg(
            name = "export",
            desc = "The specific export function to invoke.",
            default = "<start-func>"
        ),
        args = bld.args(
            name = "arg",
            desc = "Parameter for the export if export is present.",
            default = emptyList()
        ),
        resultToStdout = bld.flag(
            opt = "res",
            desc = "If there is a result, print it.",
            lowPriority = true
        )
    ).also { bld.done() }

    override fun run(args: Args) {
        val ctx = prepareContext(args.scriptArgs)
        // Instantiate the module
        val module =
            if (args.module == "<last-in-entry>") ctx.modules.lastOrNull() ?: error("No modules available")
            else ctx.registrations[args.module] as? Module.Instance ?:
                error("Unable to find module registered as ${args.module}")
        module as Module.Compiled
        // If an export is provided, call it
        if (args.export != "<start-func>") args.export.javaIdent.let { javaName ->
            val method = module.cls.declaredMethods.find { it.name == javaName } ?:
                error("Unable to find export '${args.export}'")
            // Map args to params
            require(method.parameterTypes.size == args.args.size) {
                "Given arg count of ${args.args.size} is invalid for $method"
            }
            val params = method.parameterTypes.withIndex().zip(args.args) { (index, paramType), arg ->
                when (paramType) {
                    Int::class.java -> arg.toIntOrNull() ?: error("Arg ${index + 1} of '$arg' not int")
                    Long::class.java -> arg.toLongOrNull() ?: error("Arg ${index + 1} of '$arg' not long")
                    Float::class.java -> arg.toFloatOrNull() ?: error("Arg ${index + 1} of '$arg' not float")
                    Double::class.java -> arg.toDoubleOrNull() ?: error("Arg ${index + 1} of '$arg' not double")
                    else -> error("Unrecognized type for param ${index + 1}: $paramType")
                }
            }
            val result = method.invoke(module.inst, *params.toTypedArray())
            if (args.resultToStdout && method.returnType != Void.TYPE) println(result)
        }
    }

    data class Args(
        val scriptArgs: ScriptCommand.ScriptArgs,
        val module: String,
        val export: String,
        val args: List<String>,
        val resultToStdout: Boolean
    )

    companion object : Invoke()
}