package asmble

import asmble.ast.SExpr
import asmble.ast.Script
import asmble.io.SExprToAst
import asmble.io.StrToSExpr

open class BaseTestUnit(val name: String, val wast: String, val expectedOutput: String?) {
    override fun toString() = "Test unit: $name"

    open val packageName = "asmble.temp." + name.replace('/', '.')
    open val shouldFail get() = false
    open val skipRunReason: String? get() = null
    open val defaultMaxMemPages get() = 1
    open val parseResult: StrToSExpr.ParseResult.Success by lazy {
        StrToSExpr.parse(wast).let {
            when (it) {
                is StrToSExpr.ParseResult.Error -> throw Exception("$name[${it.pos}] Parse fail: ${it.msg}")
                is StrToSExpr.ParseResult.Success -> it
            }
        }
    }
    open val ast: List<SExpr> get() = parseResult.vals
    open val script: Script by lazy { SExprToAst.toScript(SExpr.Multi(ast)) }
}