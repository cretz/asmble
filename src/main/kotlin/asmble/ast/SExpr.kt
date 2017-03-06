package asmble.ast

sealed class SExpr {
    data class Multi(val vals: List<SExpr> = emptyList()) : SExpr()
    data class Symbol(val contents: String = "", val quoted: Boolean = false) : SExpr()
}