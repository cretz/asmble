package asmble.io

import asmble.ast.Node

open class BinaryToAst(val version: Long = 0xd) {

    fun toModule(b: ByteReader): Node.Module = TODO()

    companion object : BinaryToAst()
}