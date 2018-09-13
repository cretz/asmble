package asmble.cli

import asmble.util.Logger
import kotlin.system.exitProcess

val commands = listOf(Compile, Help, Invoke, Link, Run, Translate)

fun main(args: Array<String>) {
    if (args.isEmpty()) return println(
        """
        |Usage:
        |  COMMAND options...
        |
        |Commands:
        |  ${commands.map { it.name + " - " + it.desc }.joinToString("\n  ")}
        |
        |For detailed command info, use:
        |  help COMMAND
        """.trimMargin()
    )

    // Grab first arg and then delegate
    var logger = Logger.Print(Logger.Level.WARN)
    var command: Command<*>? = null
    try {
        command = commands.find { it.name == args.first() } ?: error("No command for ${args.first()}")
        val argBuild = Command.ArgsBuilder.ActualArgBuilder(args.drop(1))
        val globals = Main.globalArgs(argBuild)
        logger = Logger.Print(globals.logLevel)
        command.logger = logger
        command.runWithArgs(argBuild)
    } catch (e: Exception) {
        logger.error { "Error ${command?.let { "in command '${it.name}'" } ?: ""}: ${e.message}" }
        if (logger.level > Logger.Level.INFO ) logger.error { "Use '-log info' for more details." }
        else e.printStackTrace()
        exitProcess(1)
    }
}

object Main {

    fun globalArgs(bld: Command.ArgsBuilder) = GlobalArgs(
        logLevel = bld.arg(
            name = "logLevel",
            opt = "log",
            desc = "One of: ${Logger.Level.values().map { it.name.toLowerCase() }.joinToString()}.",
            default = "warn",
            lowPriority = true
        ).let { Logger.Level.valueOf(it.toUpperCase()) }
    )

    data class GlobalArgs(
        val logLevel: Logger.Level
    )
}