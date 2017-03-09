package asmble.io

import asmble.ast.SExpr

open class SExprToStr(val depthBeforeNewline: Int, val indent: String) {
    @Suppress("UNCHECKED_CAST") // TODO: why?
    fun <T : Appendable> append(exp: SExpr, sb: T = StringBuilder() as T, indentLevel: Int = 0) = when(exp) {
        is SExpr.Symbol -> appendSymbol(exp, sb)
        is SExpr.Multi -> appendMulti(exp, sb, indentLevel)
    }

    @Suppress("UNCHECKED_CAST") // TODO: why?
    fun <T : Appendable> appendSymbol(exp: SExpr.Symbol, sb: T = StringBuilder() as T): T {
        val quoted = exp.quoted || exp.contents.requiresQuote
        if (!quoted) sb.append(exp.contents) else {
            sb.append('"')
            exp.contents.forEach {
                sb.append(
                        if (it in "'\"\\") "\\$it"
                        else if (it == '\n') "\\n"
                        else if (it == '\t') "\\t"
                        else if (!it.requiresQuote) it.toString()
                        else "\\" + (it.toInt() and 0xFF).toString(16)
                )
            }
            sb.append('"')
        }
        return sb
    }

    @Suppress("UNCHECKED_CAST") // TODO: why?
    fun <T : Appendable> appendMulti(exp: SExpr.Multi, sb: T = StringBuilder() as T, indentLevel: Int = 0): T {
        sb.append('(')
        exp.vals.forEachIndexed { index, sub ->
            if (sub.maxDepth() <= depthBeforeNewline) {
                if (index > 0) sb.append(' ')
                append(sub, sb, indentLevel)
            } else {
                sb.append("\n").append(indent.repeat(indentLevel + 1))
                append(sub, sb, indentLevel + 1)
                sb.append("\n").append(indent.repeat(indentLevel))
            }
        }
        sb.append(')')
        return sb
    }

    val String.requiresQuote: Boolean get() =
        this.find { it.requiresQuote } != null
    val Char.requiresQuote: Boolean get() =
        this > '~' || (!this.isLetterOrDigit() && this !in "_.+-*/^~=<>!?@#$%&|:'`")

    fun SExpr.maxDepth(): Int = when(this) {
        is SExpr.Symbol -> 0
        is SExpr.Multi -> 1 + (this.vals.map { it.maxDepth() }.max() ?: 0)
    }

    companion object : SExprToStr(depthBeforeNewline = 3, indent = "  ")
}
