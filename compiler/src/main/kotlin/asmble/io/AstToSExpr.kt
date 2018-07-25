package asmble.io

import asmble.ast.Node
import asmble.ast.SExpr
import asmble.ast.Script

open class AstToSExpr(val parensInstrs: Boolean = true) {

    fun fromAction(v: Script.Cmd.Action) = when(v) {
        is Script.Cmd.Action.Invoke -> newMulti("invoke", v.name) + v.string.quoted + v.exprs.flatMap(this::fromInstrs)
        is Script.Cmd.Action.Get -> newMulti("get", v.name) + v.string.quoted
    }

    fun fromAssertion(v: Script.Cmd.Assertion) = when(v) {
        is Script.Cmd.Assertion.Return ->
            newMulti("assert_return") + fromAction(v.action) + v.exprs.flatMap(this::fromInstrs)
        is Script.Cmd.Assertion.ReturnNan ->
            if (v.canonical) newMulti("assert_return_canonical_nan") + fromAction(v.action)
            else newMulti("assert_return_arithmetic_nan") + fromAction(v.action)
        is Script.Cmd.Assertion.Trap ->
            newMulti("assert_trap") + fromAction(v.action) + v.failure
        is Script.Cmd.Assertion.Malformed -> when (v.module) {
            is Script.LazyModule.SExpr -> newMulti("assert_malformed") + v.module.sexpr + v.failure
        }
        is Script.Cmd.Assertion.Invalid -> when (v.module) {
            is Script.LazyModule.SExpr -> newMulti("assert_invalid") + v.module.sexpr + v.failure
        }
        is Script.Cmd.Assertion.SoftInvalid ->
            newMulti("assert_soft_invalid") + fromModule(v.module) + v.failure
        is Script.Cmd.Assertion.Unlinkable ->
            newMulti("assert_unlinkable") + fromModule(v.module) + v.failure
        is Script.Cmd.Assertion.TrapModule ->
            newMulti("assert_trap") + fromModule(v.module) + v.failure
        is Script.Cmd.Assertion.Exhaustion ->
            newMulti("assert_exhaustion") + fromAction(v.action) + v.failure
    }

    fun fromCmd(v: Script.Cmd): SExpr.Multi = when(v) {
        is Script.Cmd.Module -> fromModule(v.module)
        is Script.Cmd.Register -> fromRegister(v)
        is Script.Cmd.Action -> fromAction(v)
        is Script.Cmd.Assertion -> fromAssertion(v)
        is Script.Cmd.Meta -> fromMeta(v)
    }

    fun fromData(v: Node.Data) =
        (newMulti("data") + v.index) + (newMulti("offset") +
            fromInstrs(v.offset).unwrapInstrs()) + v.data.toString(Charsets.UTF_8).quoted

    fun fromElem(v: Node.Elem) =
        (newMulti("elem") + v.index) + (newMulti("offset") + fromInstrs(v.offset).unwrapInstrs()) +
            v.funcIndices.map(this::fromNum)

    fun fromElemType(v: Node.ElemType) = when(v) {
        Node.ElemType.ANYFUNC -> fromString("anyfunc")
    }

    fun fromExport(v: Node.Export) = newMulti("export") + v.field.quoted + when(v.kind) {
        Node.ExternalKind.FUNCTION -> newMulti("func") + v.index
        Node.ExternalKind.TABLE -> newMulti("table") + v.index
        Node.ExternalKind.MEMORY -> newMulti("memory") + v.index
        Node.ExternalKind.GLOBAL -> newMulti("global") + v.index
    }

    fun fromFunc(
        v: Node.Func,
        name: String? = null,
        impExp: ImportOrExport? = null,
        localNames: Map<Int, String> = emptyMap()
    ) =
        newMulti("func", name) + impExp?.let(this::fromImportOrExport) + fromFuncSig(v.type, localNames) +
            fromLocals(v.locals, v.type.params.size, localNames) + fromInstrs(v.instructions).unwrapInstrs()

    fun fromFuncSig(v: Node.Type.Func, localNames: Map<Int, String> = emptyMap()): List<SExpr> {
        var ret = emptyList<SExpr>()
        if (v.params.isNotEmpty()) {
            if (localNames.isEmpty()) ret += newMulti("param") + v.params.map(this::fromType)
            else ret += v.params.mapIndexed { index, param -> newMulti("param", localNames[index]) + fromType(param) }
        }
        v.ret?.also { ret += newMulti("result") + fromType(it) }
        return ret
    }

    fun fromGlobal(v: Node.Global, name: String? = null, impExp: ImportOrExport? = null) =
        newMulti("global", name) + impExp?.let(this::fromImportOrExport) +
            fromGlobalSig(v.type) + fromInstrs(v.init).unwrapInstrs()

    fun fromGlobalSig(v: Node.Type.Global) =
        if (v.mutable) newMulti("mut") + fromType(v.contentType) else fromType(v.contentType)

    fun fromImport(v: Node.Import, types: List<Node.Type.Func>, name: String? = null) =
        (newMulti("import") + v.module.quoted) + v.field.quoted + fromImportKind(v.kind, types, name)

    fun fromImportFunc(v: Node.Import.Kind.Func, types: List<Node.Type.Func>, name: String? = null) =
        fromImportFunc(types.getOrElse(v.typeIndex) { throw Exception("No type at ${v.typeIndex}") }, name)
    fun fromImportFunc(v: Node.Type.Func, name: String? = null) =
        newMulti("func", name) + fromFuncSig(v)

    fun fromImportGlobal(v: Node.Import.Kind.Global, name: String? = null) =
        newMulti("global", name) + fromGlobalSig(v.type)

    fun fromImportKind(v: Node.Import.Kind, types: List<Node.Type.Func>, name: String? = null) = when(v) {
        is Node.Import.Kind.Func -> fromImportFunc(v, types, name)
        is Node.Import.Kind.Table -> fromImportTable(v, name)
        is Node.Import.Kind.Memory -> fromImportMemory(v, name)
        is Node.Import.Kind.Global -> fromImportGlobal(v, name)
    }

    fun fromImportMemory(v: Node.Import.Kind.Memory, name: String? = null) =
        newMulti("memory", name) + fromMemorySig(v.type)

    fun fromImportOrExport(v: ImportOrExport) = when (v) {
        is ImportOrExport.Import -> listOf((newMulti("import") + v.module) + v.name)
        is ImportOrExport.Export -> v.fields.map { newMulti("export") + it }
    }

    fun fromImportTable(v: Node.Import.Kind.Table, name: String? = null) =
        newMulti("table", name) + fromTableSig(v.type)

    fun fromInstr(v: Node.Instr) = v.op().let {
        val exp = newMulti(it.name)
        when (it) {
            is Node.InstrOp.ControlFlowOp.NoArg, is Node.InstrOp.ParamOp.NoArg,
                is Node.InstrOp.CompareOp.NoArg, is Node.InstrOp.NumOp.NoArg,
                is Node.InstrOp.ConvertOp.NoArg, is Node.InstrOp.ReinterpretOp.NoArg -> exp
            is Node.InstrOp.ControlFlowOp.TypeArg -> exp + it.argsOf(v).type?.let(this::fromType)
            is Node.InstrOp.ControlFlowOp.DepthArg -> exp + it.argsOf(v).relativeDepth
            is Node.InstrOp.ControlFlowOp.TableArg -> it.argsOf(v).let {
                exp + it.targetTable.map(this::fromNum) + it.default
            }
            is Node.InstrOp.CallOp.IndexArg -> exp + it.argsOf(v).index
            is Node.InstrOp.CallOp.IndexReservedArg -> exp + it.argsOf(v).index
            is Node.InstrOp.VarOp.IndexArg -> exp + it.argsOf(v).index
            is Node.InstrOp.MemOp.AlignOffsetArg -> it.argsOf(v).let {
                exp + it.offset.takeIf { it > 0 }?.let { "offset=$it"} +
                    it.align.takeIf { it > 0 }?.let { "align=$it"}
            }
            is Node.InstrOp.MemOp.ReservedArg -> exp
            is Node.InstrOp.ConstOp<*> -> exp + it.argsOf(v).value
        }
    }

    fun fromInstrs(v: List<Node.Instr>): List<SExpr.Multi> {
        var index = 0
        fun untilNext(vararg insns: Node.Instr): Pair<List<SExpr>, Node.Instr?> {
            var ret = emptyList<SExpr>()
            while (index < v.size) {
                val insn = v[index]
                index++
                if (insns.contains(insn)) return ret to insn
                var expr = fromInstr(insn)
                if (insn is Node.Instr.Block || insn is Node.Instr.Loop) {
                    expr += untilNext(Node.Instr.End).first
                    if (!parensInstrs) expr += "end"
                } else if (insn is Node.Instr.If) untilNext(Node.Instr.Else, Node.Instr.End).let { (subList, insn) ->
                    var elseList = subList
                    if (insn is Node.Instr.Else) {
                        if (parensInstrs) expr += newMulti("then") + subList else expr = expr + subList + "end"
                        elseList = untilNext(Node.Instr.End).first
                    }
                    if (parensInstrs) expr += newMulti("else") + elseList else expr = expr + elseList + "end"
                }
                if (parensInstrs) ret += expr else ret += expr.vals
            }
            require(insns.isEmpty()) { "Expected one of ${insns.map { it.op().name }}, got none" }
            return ret to null
        }
        if (parensInstrs) return untilNext().first.map { it as SExpr.Multi }
        return listOf(SExpr.Multi(untilNext().first))
    }

    fun fromLocals(v: List<Node.Type.Value>, paramOffset: Int, localNames: Map<Int, String> = emptyMap()) =
        if (v.isEmpty()) emptyList()
        else if (localNames.isEmpty()) listOf(newMulti("local") + v.map(this::fromType))
        else v.mapIndexed { index, v -> newMulti("local", localNames[paramOffset + index]) + fromType(v) }

    fun fromMemory(v: Node.Type.Memory, name: String? = null, impExp: ImportOrExport? = null) =
        newMulti("memory", name) + impExp?.let(this::fromImportOrExport) + fromMemorySig(v)

    fun fromMemorySig(v: Node.Type.Memory) = fromResizableLimits(v.limits)

    fun fromMeta(v: Script.Cmd.Meta) = when(v) {
        is Script.Cmd.Meta.Script -> newMulti("script", v.name) + fromScript(v.script)
        is Script.Cmd.Meta.Input -> newMulti("input", v.name) + v.str
        is Script.Cmd.Meta.Output -> newMulti("output", v.name) + v.str
    }

    fun fromModule(v: Node.Module, name: String? = v.names?.moduleName): SExpr.Multi {
        var ret = newMulti("module", name)

        // If there is a call_indirect, then we need to output all types in exact order.
        // Otherwise we can skip ones that are referenced elsewhere.
        // Ref: https://github.com/WebAssembly/design/issues/1041
        val hasCallIndirect = v.funcs.any { it.instructions.any { it is Node.Instr.CallIndirect } }
        val types = if (hasCallIndirect) v.types else {
            val importIndices = v.imports.mapNotNull { (it.kind as? Node.Import.Kind.Func)?.typeIndex }.toSet()
            v.types.filterIndexed { i, _ -> importIndices.contains(i) } - v.funcs.map { it.type }
        }

        // Keep track of the current function index for names
        var funcIndex = -1

        ret += types.map { fromTypeDef(it) }
        ret += v.imports.map {
            if (it.kind is Node.Import.Kind.Func) funcIndex++
            fromImport(it, v.types, v.names?.funcNames?.get(funcIndex))
        }
        ret += v.exports.map(this::fromExport)
        ret += v.tables.map { fromTable(it) }
        ret += v.memories.map { fromMemory(it) }
        ret += v.globals.map { fromGlobal(it) }
        ret += v.elems.map(this::fromElem)
        ret += v.data.map(this::fromData)
        ret += v.startFuncIndex?.let(this::fromStart)
        ret += v.funcs.map {
            funcIndex++
            fromFunc(
                v = it,
                name = v.names?.funcNames?.get(funcIndex),
                localNames = v.names?.localNames?.get(funcIndex) ?: emptyMap()
            )
        }
        return ret
    }

    fun fromNum(v: Number) = fromString(v.toString())

    fun fromRegister(v: Script.Cmd.Register) = (newMulti("register") + v.string) + v.name

    fun fromResizableLimits(v: Node.ResizableLimits) = listOfNotNull(fromNum(v.initial), v.maximum?.let(this::fromNum))

    fun fromScript(v: Script) = v.commands.map(this::fromCmd)

    fun fromStart(v: Int) = newMulti("start") + v

    fun fromString(v: String, quoted: Boolean = false) = SExpr.Symbol(v, quoted)

    fun fromTable(v: Node.Type.Table, name: String? = null, impExp: ImportOrExport? = null) =
        newMulti("table", name) + impExp?.let(this::fromImportOrExport) + fromTableSig(v)

    fun fromTableSig(v: Node.Type.Table) = fromResizableLimits(v.limits) + fromElemType(v.elemType)

    fun fromType(v: Node.Type.Value) = fromString(when (v) {
        is Node.Type.Value.I32 -> "i32"
        is Node.Type.Value.I64 -> "i64"
        is Node.Type.Value.F32 -> "f32"
        is Node.Type.Value.F64 -> "f64"
    })

    fun fromTypeDef(v: Node.Type.Func, name: String? = null) =
        newMulti("type", name) + (newMulti("func") + fromFuncSig(v))

    private operator fun SExpr.Multi.plus(exp: Number?) =
        if (exp == null) this else this.copy(vals = this.vals + fromNum(exp))
    private operator fun SExpr.Multi.plus(exp: String?) =
            if (exp == null) this else this.copy(vals = this.vals + fromString(exp))
    private operator fun SExpr.Multi.plus(exp: SExpr?) =
        if (exp == null) this else this.copy(vals = this.vals + exp)
    private operator fun SExpr.Multi.plus(exps: List<SExpr>?) =
        if (exps == null || exps.isEmpty()) this else this.copy(vals = this.vals + exps)
    private fun newMulti(initSymb: String? = null, initName: String? = null) =
        SExpr.Multi() + initSymb + initName?.let { "$$it" }
    private fun List<SExpr.Multi>.unwrapInstrs() =
        if (parensInstrs) this else this.single().vals
    private val String.quoted get() = fromString(this, true)

    companion object : AstToSExpr()
}