package asmble.io

import asmble.ast.SExpr
import asmble.util.IdentityMap
import asmble.util.plus

open class StrToSExpr {
    sealed class ParseResult {
        data class Pos(val line: Int, val char: Int)
        data class Success(val vals: List<SExpr>, val exprOffsetMap: IdentityMap<SExpr, Pos>) : ParseResult()
        data class Error(val pos: Pos, val msg: String) : ParseResult()
    }

    fun parse(str: CharSequence): ParseResult {
        val state = ParseState(str)
        var ret = emptyList<SExpr>()
        while (!state.isEof) {
            ret += state.nextSExpr() ?: break
            if (state.err != null) return ParseResult.Error(str.posFromOffset(state.offset), state.err!!)
        }
//        val retVals = if (ret.size == 1 && ret[0] is SExpr.Multi) (ret[0] as SExpr.Multi).vals else ret
        return ParseResult.Success(ret, state.exprOffsetMap)
    }

    private class ParseState(
        val str: CharSequence,
        var exprOffsetMap: IdentityMap<SExpr, ParseResult.Pos> = IdentityMap(),
        var offset: Int = 0,
        var err: String? = null
    ) {
        fun nextSExpr(): SExpr? {
            skipWhitespace()
            if (isEof) return null
            // What type of expr do we have here?
            val origOffset = offset
            when (str[offset]) {
                '(' -> {
                    offset++
                    var ret = SExpr.Multi()
                    while (err == null && !isEof && str[offset] != ')') {
                        val innerExp = nextSExpr() ?: break
                        ret = ret.copy(ret.vals + innerExp)
                    }
                    if (err == null) {
                        if (str[offset] == ')') offset++ else err = "EOF when expected ')'"
                    }
                    exprOffsetMap += ret to str.posFromOffset(origOffset)
                    return ret
                }
                '"' -> {
                    offset++
                    // Check escapes
                    var retStr = ""
                    while (err == null && !isEof && str[offset] != '"') {
                        if (str[offset] == '\\') {
                            offset++
                            if (isEof) err = "EOF when expected char to unescape" else {
                                when (str[offset]) {
                                    'n' -> retStr += '\n'
                                    't' -> retStr += '\t'
                                    '\\' -> retStr += '\\'
                                    '\'' -> retStr += '\''
                                    '"' -> retStr += '"'
                                    else -> {
                                        // Try to parse hex if there is enough, otherwise just gripe
                                        if (offset + 1 >= str.length) err = "Not enough to hex escape" else {
                                            try {
                                                retStr += str.substring(offset, offset + 2).toInt(16).toChar()
                                                offset++
                                            } catch (e: NumberFormatException) {
                                                err = "Unknown escape: ${str.substring(offset, offset + 2)}: $e"
                                            }
                                        }
                                    }
                                }
                                offset++
                            }
                        } else {
                            retStr += str[offset]
                            offset++
                        }
                    }
                    if (err == null && str[offset] != '"') err = "EOF when expected '\"'"
                    else if (err == null) offset++
                    val ret = SExpr.Symbol(retStr, true)
                    exprOffsetMap += ret to str.posFromOffset(origOffset)
                    return ret
                }
                else -> {
                    // Go until next quote or whitespace or parens
                    while (!isEof && !"();\"".contains(str[offset]) && !str[offset].isWhitespace()) offset++
                    if (origOffset == offset) return null
                    val ret = SExpr.Symbol(str.substring(origOffset, offset))
                    exprOffsetMap += ret to str.posFromOffset(origOffset)
                    return ret
                }
            }
        }

        fun skipWhitespace() {
            do {
                val origOffset = offset
                // Get rid of actual whitespace
                while (!isEof && str[offset].isWhitespace()) offset++
                // Get rid of nested comments
                if (str.length >= offset + 2 && str.substring(offset, offset + 2) == "(;") {
                    // Find the next ;) and check back to see if nested
                    offset += 2
                    var endsRequired = 1
                    while (endsRequired > 0) {
                        val end = str.indexOf(";)", offset)
                        if (end == -1) throw Exception("Can't find ending ';)' for comment")
                        // Add to ends required if there were nested ones
                        endsRequired--
                        endsRequired += str.substring(offset, end).substringCount("(;")
                        offset = end + 2
                    }
                }
                // Get rid of line comments
                if (str.length >= offset + 2 && str.substring(offset, offset + 2) == ";;") {
                    val end = str.indexOf('\n', offset)
                    if (end == -1) offset = str.length else offset = end + 1
                }
            } while(!isEof && origOffset != offset)
        }

        val isEof: Boolean inline get() = offset >= str.length
    }

    fun CharSequence.posFromOffset(offset: Int) = ParseResult.Pos(
        line = this.substring(0, offset).substringCount("\n") + 1,
        char = offset - this.lastIndexOf('\n', offset)
    )

    fun CharSequence.substringCount(str: String): Int {
        var lastIndex = 0
        var count = 0
        while (lastIndex != -1) {
            lastIndex = this.indexOf(str, lastIndex)
            if (lastIndex != -1) {
                count++
                lastIndex += str.length
            }
        }
        return count
    }

    companion object : StrToSExpr()
}