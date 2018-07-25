package asmble.io

import asmble.ast.SExpr
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

open class StrToSExpr {
    sealed class ParseResult {
        data class Pos(val line: Int, val char: Int)
        data class Success(
            val vals: List<SExpr>,
            // Key is ident hash code, val is str char offset
            val exprIdentOffsetMap: Map<Int, Int>
        ) : ParseResult() {
            fun exprPos(fullStr: CharSequence, expr: SExpr) =
                fullStr.posFromOffset(exprIdentOffsetMap.getValue(System.identityHashCode(expr)))
        }
        data class Error(val pos: Pos, val msg: String) : ParseResult()
    }

    fun parseSingleMulti(str: CharSequence) = parse(str).let {
        when (it) {
            is ParseResult.Success -> (it.vals.singleOrNull() as? SExpr.Multi) ?: error("Not a single multi-expr")
            is ParseResult.Error -> error("Failed parsing at ${it.pos.line}:${it.pos.char} - ${it.msg}")
        }
    }

    fun parse(str: CharSequence): ParseResult {
        val state = ParseState(str)
        val ret = mutableListOf<SExpr>()
        while (!state.isEof) {
            ret += state.nextSExpr() ?: break
            if (state.err != null) return ParseResult.Error(str.posFromOffset(state.offset), state.err!!)
        }
        return ParseResult.Success(ret, state.exprOffsetMap)
    }

    private class ParseState(
        val str: CharSequence,
        val exprOffsetMap: MutableMap<Int, Int> = mutableMapOf(),
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
                    val inner = mutableListOf<SExpr>()
                    while (err == null && !isEof && str[offset] != ')') {
                        inner.add(nextSExpr() ?: break)
                    }
                    if (err == null) {
                        if (str[offset] == ')') offset++ else err = "EOF when expected ')'"
                    }
                    val ret = SExpr.Multi(inner)
                    exprOffsetMap.put(System.identityHashCode(ret), origOffset)
                    return ret
                }
                '"' -> {
                    offset++
                    // We go over each char here checking escapes
                    var retStr = ""

                    // The WASM spec says we can treat chars normally unless they are hex escapes at which point they
                    // are raw bytes. Since we want to store everything as a string for later use, we need to keep track
                    // which set of raw bytes were invalid UTF-8 for UTF-8 validation later. Alternatively, we could
                    // just store in bytes and decode on use but this is easier. We keep a list of byte "runs" and at
                    // the end of each "run", we check whether they would make a valid UTF-8 string.
                    var hasNonUtf8ByteSeqs = false
                    val currByteSeq = mutableListOf<Byte>()
                    fun checkByteSeq() {
                        if (!hasNonUtf8ByteSeqs && currByteSeq.isNotEmpty()) {
                            try {
                                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(currByteSeq.toByteArray()))
                            } catch (_: CharacterCodingException) {
                                hasNonUtf8ByteSeqs = true
                            }
                            currByteSeq.clear()
                        }
                    }

                    while (err == null && !isEof && str[offset] != '"') {
                        var wasEscapedChar = false
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
                                                val int = str.substring(offset, offset + 2).toInt(16)
                                                retStr += int.toChar()
                                                currByteSeq.add(int.toByte())
                                                wasEscapedChar = true
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
                        if (!wasEscapedChar) checkByteSeq()
                    }
                    checkByteSeq()
                    if (err == null && str[offset] != '"') err = "EOF when expected '\"'"
                    else if (err == null) offset++
                    val ret = SExpr.Symbol(retStr, true, hasNonUtf8ByteSeqs)
                    exprOffsetMap.put(System.identityHashCode(ret), origOffset)
                    return ret
                }
                else -> {
                    // Go until next quote or whitespace or parens
                    while (!isEof && !"();\"".contains(str[offset]) && !str[offset].isWhitespace()) offset++
                    if (origOffset == offset) return null
                    val ret = SExpr.Symbol(str.substring(origOffset, offset))
                    exprOffsetMap.put(System.identityHashCode(ret), origOffset)
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