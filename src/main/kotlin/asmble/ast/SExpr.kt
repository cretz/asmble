package asmble.ast

import asmble.util.Either

sealed class SExpr {
    data class Multi(val vals: List<SExpr> = emptyList()) : SExpr()
    data class Symbol(val contents: String = "", val quoted: Boolean = false) : SExpr()

    companion object {
        data class ParseError(val charOffset: Int, val msg: String)
        fun parse(str: CharSequence): Either<Multi, ParseError> {
            val state = ParseState(str)
            var ret = emptyList<SExpr>()
            while (!state.isEof) {
                ret += state.nextSExpr()
                if (state.err != null) return Either.Right(ParseError(state.offset, state.err!!))
            }
            if (ret.size == 1 && ret[0] is Multi) return Either.Left(ret[0] as Multi)
            else return Either.Left(Multi(ret))
        }

        private class ParseState(val str: CharSequence, var offset: Int = 0, var err: String? = null) {
            fun nextSExpr(): SExpr {
                skipWhitespace()
                if (isEof) return Multi()
                // What type of expr do we have here?
                when (str[offset]) {
                    '(' -> {
                        offset++
                        var ret = Multi()
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
                        // Anything can be escaped (for now)
                        var retStr = ""
                        while (!isEof && str[offset] != '"') {
                            if (str[offset] == '\\') {
                                ++offset
                                if (isEof) {
                                    err = "EOF when expected char to unescape"
                                    break
                                }
                            }
                            retStr += str[offset]
                            offset++
                        }
                        if (err == null && str[offset] != '"') err = "EOF when expected '\"'"
                        return Symbol(retStr, true)
                    }
                    else -> {
                        // Go until next quote or whitespace or parens
                        val origOffset = offset
                        while (!isEof && str[offset] != '(' && str[offset] != ')' &&
                                str[offset] != '"' && !str[offset].isWhitespace()) offset++
                        return Symbol(str.substring(origOffset, offset))
                    }
                }
            }

            fun skipWhitespace() { while (!isEof && str[offset].isWhitespace()) offset++ }

            val isEof: Boolean inline get() = offset >= str.length
        }
    }
}