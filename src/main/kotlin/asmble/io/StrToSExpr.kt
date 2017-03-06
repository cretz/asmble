package asmble.io

import asmble.ast.SExpr
import asmble.util.Either

open class StrToSExpr {
    data class ParseError(val charOffset: Int, val msg: String)

    fun parse(str: CharSequence): Either<SExpr.Multi, ParseError> {
        val state = ParseState(str)
        var ret = emptyList<SExpr>()
        while (!state.isEof) {
            ret += state.nextSExpr()
            if (state.err != null) return Either.Right(ParseError(state.offset, state.err!!))
        }
        if (ret.size == 1 && ret[0] is SExpr.Multi) return Either.Left(ret[0] as SExpr.Multi)
        else return Either.Left(SExpr.Multi(ret))
    }

    private class ParseState(val str: CharSequence, var offset: Int = 0, var err: String? = null) {
        fun nextSExpr(): SExpr {
            skipWhitespace()
            if (isEof) return SExpr.Multi()
            // What type of expr do we have here?
            when (str[offset]) {
                '(' -> {
                    offset++
                    var ret = SExpr.Multi()
                    while (err == null && !isEof && str[offset] != ')') {
                        ret = ret.copy(ret.vals + nextSExpr())
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
                            if (isEof) err = "EOF when expected char to unescape" else when (str[offset]) {
                                'n' -> retStr += '\n'
                                't' -> retStr += '\t'
                                '\\' -> retStr += '\\'
                                '\'' -> retStr += '\''
                                '"' -> retStr += '"'
                                else -> {
                                    // Try to parse hex if there is enough, otherwise just gripe
                                    if (offset + 4 >= str.length) err = "Unknown escape" else {
                                        try {
                                            retStr += str.substring(offset, offset + 4).toByte(16).toChar()
                                            offset += 4
                                        } catch (e: NumberFormatException) {
                                            err = "Unknown escape"
                                        }
                                    }
                                }
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
                    while (!isEof && str[offset] != '(' && str[offset] != ')' &&
                            str[offset] != '"' && !str[offset].isWhitespace()) offset++
                    return SExpr.Symbol(str.substring(origOffset, offset))
                }
            }
        }

        fun skipWhitespace() { while (!isEof && str[offset].isWhitespace()) offset++ }

        val isEof: Boolean inline get() = offset >= str.length
    }

    companion object : StrToSExpr()
}