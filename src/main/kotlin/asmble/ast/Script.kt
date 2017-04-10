package asmble.ast

data class Script(val commands: List<Cmd>) {
    sealed class Cmd {
        data class Module(val module: Node.Module, val name: String?): Cmd()
        data class Register(val string: String, val name: String?): Cmd()
        sealed class Action: Cmd() {
            data class Invoke(val name: String?, val string: String, val exprs: List<List<Node.Instr>>): Action()
            data class Get(val name: String?, val string: String): Action()
        }
        sealed class Assertion: Cmd() {
            data class Return(val action: Action, val exprs: List<List<Node.Instr>>): Assertion()
            data class ReturnNan(val action: Action, val canonical: Boolean): Assertion()
            data class Trap(val action: Action, val failure: String): Assertion()
            data class Malformed(val module: LazyModule, val failure: String): Assertion()
            data class Invalid(val module: LazyModule, val failure: String): Assertion()
            data class SoftInvalid(val module: Node.Module, val failure: String): Assertion()
            data class Unlinkable(val module: Node.Module, val failure: String): Assertion()
            data class TrapModule(val module: Node.Module, val failure: String): Assertion()
            data class Exhaustion(val action: Action, val failure: String): Assertion()
        }
        sealed class Meta: Cmd() {
            data class Script(val name: String?, val script: asmble.ast.Script): Meta()
            data class Input(val name: String?, val str: String): Meta()
            data class Output(val name: String?, val str: String?): Meta()
        }
    }

    sealed class LazyModule(l: Lazy<Node.Module>) : Lazy<Node.Module> by l {
        data class SExpr(
            val sexpr: asmble.ast.SExpr.Multi,
            val fn: (asmble.ast.SExpr.Multi) -> Node.Module
        ) : LazyModule(lazy { fn(sexpr) })
    }
}