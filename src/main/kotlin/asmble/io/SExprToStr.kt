package asmble.io

import asmble.ast.SExpr

open class SExprToStr(val depthBeforeNewline: Int, val countBeforeNewlineAll: Int, val indent: String) {

    fun fromSExpr(vararg exp: SExpr): String = appendAll(exp.asList(), StringBuilder()).trim().toString()

    @Suppress("UNCHECKED_CAST")
    fun <T : Appendable> append(exp: SExpr, sb: T = StringBuilder() as T, indentLevel: Int = 0) = when(exp) {
        is SExpr.Symbol -> appendSymbol(exp, sb)
        is SExpr.Multi -> appendMulti(exp, sb, indentLevel)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Appendable> appendSymbol(exp: SExpr.Symbol, sb: T = StringBuilder() as T): T {
        val quoted = exp.quoted || exp.contents.requiresQuote
        if (!quoted) sb.append(exp.contents) else {
            sb.append('"')
            exp.contents.forEach {
                sb.append(
                    if (it in "'\"\\") "\\$it"
                    else if (it == '\n') "\\n"
                    else if (it == '\t') "\\t"
                    else if (it == ' ') " "
                    else if (!it.requiresQuote) it.toString()
                    else "\\" + (it.toInt() and 0xFF).toString(16).let { if (it.length < 2) "0$it" else it }
                )
            }
            sb.append('"')
        }
        return sb
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Appendable> appendMulti(exp: SExpr.Multi, sb: T = StringBuilder() as T, indentLevel: Int = 0): T {
        sb.append('(')
        appendAll(exp.vals, sb, indentLevel)
        sb.append(')')
        return sb
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Appendable> appendAll(exps: List<SExpr>, sb: T = StringBuilder() as T, indentLevel: Int = 0): T {
        val newlineAll = exps.sumBy { it.count() } >= countBeforeNewlineAll
        var wasLastNewline = false
        exps.forEachIndexed { index, sub ->
            // No matter what, if the first is a symbol
            val isFirstAsSymbol = index == 0 && sub is SExpr.Symbol
            val shouldNewline = !isFirstAsSymbol && (newlineAll || sub.maxDepth() > depthBeforeNewline)
            if (!shouldNewline) {
                if (index > 0 && !wasLastNewline) sb.append(' ')
                append(sub, sb, indentLevel)
                wasLastNewline = false
            } else {
                if (!wasLastNewline) sb.append("\n").append(indent.repeat(indentLevel))
                append(sub, sb, indentLevel + 1)
                sb.append("\n")
                if (index < exps.size - 1) sb.append(indent.repeat(indentLevel))
                else if (indentLevel > 0) sb.append(indent.repeat(indentLevel - 1))
                wasLastNewline = true
            }
        }
        return sb
    }

    val String.requiresQuote: Boolean get() =
        this.find { it.requiresQuote } != null
    val Char.requiresQuote: Boolean get() =
        this > '~' || (!this.isLetterOrDigit() && this !in "_.+-*/^~=<>!?@#$%&|:'`")

    fun SExpr.count(): Int = when(this) {
        is SExpr.Symbol -> 1
        is SExpr.Multi -> this.vals.sumBy { it.count() }
    }

    fun SExpr.maxDepth(): Int = when(this) {
        is SExpr.Symbol -> 0
        is SExpr.Multi -> 1 + (this.vals.map { it.maxDepth() }.max() ?: 0)
    }

    companion object : SExprToStr(depthBeforeNewline = 3, countBeforeNewlineAll = 10, indent = "  ") {
        val Compact = SExprToStr(depthBeforeNewline = Int.MAX_VALUE, countBeforeNewlineAll = Int.MAX_VALUE, indent = "")
    }
}
