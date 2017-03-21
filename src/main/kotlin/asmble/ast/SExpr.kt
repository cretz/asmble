package asmble.ast

import asmble.io.SExprToStr

sealed class SExpr {
    data class Multi(val vals: List<SExpr> = emptyList()) : SExpr() {
        override fun toString() = SExprToStr.Compact.fromSExpr(this)
    }
    data class Symbol(val contents: String = "", val quoted: Boolean = false) : SExpr() {
        override fun toString() = SExprToStr.Compact.fromSExpr(this)
    }
}