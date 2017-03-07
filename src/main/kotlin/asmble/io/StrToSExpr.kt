package asmble.io

import asmble.ast.SExpr
import asmble.util.Either

open class StrToSExpr {
    data class ParseError(val line: Int, val char: Int, val msg: String)

    fun parse(str: CharSequence): Either<SExpr.Multi, ParseError> {
        val state = ParseState(str)
        var ret = emptyList<SExpr>()
        while (!state.isEof) {
            ret += state.nextSExpr() ?: break
            if (state.err != null) {
                val line = str.substring(0, state.offset).substringCount("\n") + 1
                val char = state.offset - str.lastIndexOf('\n', state.offset)
                return Either.Right(ParseError(line, char, state.err!!))
            }
        }
        if (ret.size == 1 && ret[0] is SExpr.Multi) return Either.Left(ret[0] as SExpr.Multi)
        else return Either.Left(SExpr.Multi(ret))
    }

    private class ParseState(val str: CharSequence, var offset: Int = 0, var err: String? = null) {
        fun nextSExpr(): SExpr? {
            skipWhitespace()
            if (isEof) return null
            // What type of expr do we have here?
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
                    return SExpr.Symbol(retStr, true)
                }
                else -> {
                    // Go until next quote or whitespace or parens
                    val origOffset = offset
                    while (!isEof && !"();\"".contains(str[offset]) && !str[offset].isWhitespace()) offset++
                    return if (origOffset == offset) null else SExpr.Symbol(str.substring(origOffset, offset))
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