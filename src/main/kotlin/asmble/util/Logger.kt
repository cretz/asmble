package asmble.util

interface Logger {
    val level: Level
    fun log(atLevel: Level, str: String)

    fun log(atLevel: Level, fn: () -> String) { if (atLevel >= level) log(atLevel, fn()) }
    fun error(fn: () -> String) { log(Level.ERROR, fn) }
    fun warn(fn: () -> String) { log(Level.WARN, fn) }
    fun info(fn: () -> String) { log(Level.INFO, fn) }
    fun debug(fn: () -> String) { log(Level.DEBUG, fn) }
    fun trace(fn: () -> String) { log(Level.TRACE, fn) }

    enum class Level { TRACE, DEBUG, INFO, WARN, ERROR, OFF }

    data class Print(override val level: Level) : Logger {
        override fun log(atLevel: Level, str: String) { println("[$atLevel] $str") }
    }
}