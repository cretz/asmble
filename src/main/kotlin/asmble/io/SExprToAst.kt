package asmble.io

import asmble.ast.Node
import asmble.ast.Node.InstrOp
import asmble.ast.SExpr
import asmble.util.takeUntilNull

class SExprToAst {
    fun toBlockSigMaybe(exp: SExpr.Multi, offset: Int): List<Node.Type.Value> {
        val types = exp.vals.drop(offset).asSequence().map {
            if (it is SExpr.Symbol) toTypeMaybe(it) else null
        }.takeUntilNull().toList()
        // We can only handle one type for now
        require(types.size  <= 1)
        return types
    }

    fun toElemType(exp: SExpr.Multi, offset: Int): Node.ElemType {
        exp.vals[offset].requireSymbol("anyfunc")
        return Node.ElemType.ANYFUNC
    }

    fun toExport(exp: SExpr.Multi): Node.Export {
        exp.vals.first().requireSymbol("export")
        val field = (exp.vals[1] as SExpr.Symbol).contents
        val kind = exp.vals[2] as SExpr.Multi
        val kindIndex = toVar(kind.vals[1] as SExpr.Symbol)
        val extKind = when((kind.vals[0] as SExpr.Symbol).contents) {
            "func" -> Node.ExternalKind.FUNCTION
            "global" -> Node.ExternalKind.GLOBAL
            "table" -> Node.ExternalKind.TABLE
            "memory" -> Node.ExternalKind.MEMORY
            else -> throw Exception("Unrecognized kind: ${kind.vals[0]}")
        }
        return Node.Export(field, extKind, kindIndex)
    }

    fun toExprMaybe(exp: SExpr.Multi): List<Node.Instr> {
        // <op> or <op> <expr>+
        val maybeOpAndOffset = toOpMaybe(exp, 0)
        if (maybeOpAndOffset != null) {
            // Everything left in the multi should be a a multi expression
            return exp.vals.drop(maybeOpAndOffset.second).flatMap {
                toExprMaybe(it as SExpr.Multi)
            } + maybeOpAndOffset.first
        }
        // Other blocks take up the rest (ignore names)
        val blockName = (exp.vals.first() as SExpr.Symbol).contents
        var opOffset = 1
        if (exp.maybeName(opOffset) != null) opOffset++
        val sigs = toBlockSigMaybe(exp, opOffset)
        opOffset += sigs.size
        when(blockName) {
            "block" ->
                return listOf(Node.Instr.Block(sigs.firstOrNull())) +
                        toInstrs(exp, opOffset).first + Node.Instr.End
            "loop" ->
                return listOf(Node.Instr.Loop(sigs.firstOrNull())) +
                        toInstrs(exp, opOffset).first + Node.Instr.End
            "if" -> {
                if (opOffset >= exp.vals.size) return emptyList()
                var ret = emptyList<Node.Instr>()
                // Try expressions
                var exprMulti = exp.vals[opOffset] as SExpr.Multi
                var exprs = toExprMaybe(exprMulti)
                // Conditional?
                if (exprs.isNotEmpty()) {
                    // First expression means it's the conditional, so push on stack
                    ret += exprs
                    opOffset++
                    exprMulti = exp.vals[opOffset] as SExpr.Multi
                }
                ret += Node.Instr.If(sigs.firstOrNull())
                // Is it a "then"?
                if (exprMulti.vals[0] is SExpr.Symbol && (exprMulti.vals[0] as SExpr.Symbol).contents == "then") {
                    ret += toInstrs(exprMulti, 1).first
                } else ret += toExprMaybe(exprMulti)
                // Now check for "else"
                opOffset++
                if (opOffset < exp.vals.size) {
                    ret += Node.Instr.Else
                    exprMulti = exp.vals[opOffset] as SExpr.Multi
                    if (exprMulti.vals[0] is SExpr.Symbol && (exprMulti.vals[0] as SExpr.Symbol).contents == "else") {
                        ret += toInstrs(exprMulti, 1).first
                    } else ret += toExprMaybe(exprMulti)
                }
                return ret + Node.Instr.End
            }
            else -> return emptyList()
        }
    }

    fun toFunc(exp: SExpr.Multi): Pair<String?, Node.Func> {
        exp.vals.first().requireSymbol("func")
        // TODO: export/import
        var currentIndex = 1
        val name = exp.maybeName(currentIndex)
        if (name != null) currentIndex++
        val sig = toFuncSig(exp.vals[currentIndex] as SExpr.Multi)
        currentIndex++
        val locals = exp.repeated("local", currentIndex, { toLocals(it).second }).flatten()
        currentIndex += locals.size
        val (instrs, _) = toInstrs(exp, currentIndex)
        return Pair(name, Node.Func(sig, locals, instrs))
    }

    fun toFuncSig(exp: SExpr.Multi): Node.Type.Func {
        // TODO: type <var> form?
        val params = exp.repeated("param", 0, { toParams(it).second }).flatten()
        val results = exp.vals.drop(params.size).map { toResult(it as SExpr.Multi) }
        require(results.size <= 1)
        return Node.Type.Func(params, results.firstOrNull())
    }

    fun toGlobalSig(exp: SExpr): Node.Type.Global = when(exp) {
        is SExpr.Symbol -> Node.Type.Global(toType(exp), false)
        is SExpr.Multi -> {
            exp.vals.first().requireSymbol("mut")
            Node.Type.Global(toType(exp.vals[1] as SExpr.Symbol), true)
        }
    }

    fun toImport(exp: SExpr.Multi): Triple<String, String, Node.Type> {
        exp.vals.first().requireSymbol("import")
        val module = (exp.vals[1] as SExpr.Symbol).contents
        val field = (exp.vals[2] as SExpr.Symbol).contents
        val kind = exp.vals[3] as SExpr.Multi
        val kindName = (kind.vals[0] as SExpr.Symbol).contents
        val kindSubOffset = if (kind.maybeName(1) == null) 1 else 2
        return Triple(module, field, when(kindName) {
            "func" -> toFuncSig(kind.vals[kindSubOffset] as SExpr.Multi)
            "global" -> toGlobalSig(kind.vals[kindSubOffset])
            "table" -> toTableSig(kind, kindSubOffset)
            "memory" -> toMemorySig(kind, kindSubOffset)
            else -> throw Exception("Unrecognized type: $kindName")
        })
    }

    fun toInstrs(exp: SExpr.Multi, offset: Int): Pair<List<Node.Instr>, Int> {
        var runningOffset = 0
        var ret = emptyList<Node.Instr>()
        while (offset + runningOffset < exp.vals.size) {
            val maybeInstrAndOffset = toInstrMaybe(exp, offset + runningOffset)
            if (maybeInstrAndOffset.first.isEmpty()) break
            ret += maybeInstrAndOffset.first
            runningOffset += maybeInstrAndOffset.second
        }
        return Pair(ret, runningOffset)
    }

    fun toInstrMaybe(exp: SExpr.Multi, offset: Int): Pair<List<Node.Instr>, Int> {
        // <expr>
        if (exp.vals[offset] is SExpr.Multi) {
            val exprs = toExprMaybe(exp)
            return Pair(exprs, if (exprs.isEmpty()) 0 else 1)
        }
        // <op>
        val maybeOpAndOffset = toOpMaybe(exp, offset)
        if (maybeOpAndOffset != null) {
            return Pair(listOf(maybeOpAndOffset.first), maybeOpAndOffset.second)
        }
        // Other blocks (ignore names)
        if (exp.vals[offset] !is SExpr.Symbol) return Pair(emptyList(), 0)
        val blockName = (exp.vals[offset] as SExpr.Symbol).contents
        var opOffset = 1
        if (exp.maybeName(offset + opOffset) != null) opOffset++
        val sigs = toBlockSigMaybe(exp, offset + opOffset)
        opOffset += sigs.size
        var ret = emptyList<Node.Instr>()
        when(blockName) {
            "block" -> {
                ret += Node.Instr.Block(sigs.firstOrNull())
                toInstrs(exp, offset + opOffset).also {
                    ret += it.first
                    opOffset += it.second
                }
            }
            "loop" -> {
                ret += Node.Instr.Loop(sigs.firstOrNull())
                toInstrs(exp, offset + opOffset).also {
                    ret += it.first
                    opOffset += it.second
                }
            }
            "if" -> {
                ret += Node.Instr.Loop(sigs.firstOrNull())
                toInstrs(exp, offset + opOffset).also {
                    ret += it.first
                    opOffset += it.second
                }
                // Else?
                if (offset + opOffset < exp.vals.size) {
                    if ((exp.vals[offset + opOffset] as? SExpr.Symbol)?.contents == "else") {
                        ret += Node.Instr.Else
                        opOffset++
                        if (exp.maybeName(offset + opOffset) != null) opOffset++
                        toInstrs(exp, offset + opOffset).also {
                            ret += it.first
                            opOffset += it.second
                        }
                    }
                }
            }
            else -> return Pair(emptyList(), 0)
        }
        require((exp.vals[offset + opOffset] as? SExpr.Symbol)?.contents == "end")
        opOffset++
        if (exp.maybeName(offset + opOffset) != null) opOffset++
        return Pair(ret, opOffset)
    }

    fun toLocals(exp: SExpr.Multi): Pair<String?, List<Node.Type.Value>> {
        exp.vals.first().requireSymbol("local")
        val name = exp.maybeName(1)
        if (name != null) return Pair(name, listOf(toType(exp.vals[2] as SExpr.Symbol)))
        return Pair(null, exp.vals.drop(1).map { toType(it as SExpr.Symbol) })
    }

    fun toMemorySig(exp: SExpr.Multi, offset: Int): Node.Type.Memory {
        return Node.Type.Memory(toResizeableLimits(exp, offset))
    }

    fun toModule(exp: SExpr.Multi): Pair<String?, Node.Module> {
        exp.vals.first().requireSymbol("module")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        var types = exp.repeated("type", currIndex, { toTypeDef(it).second })
        currIndex += types.size
        val funcs = exp.repeated("func", currIndex, { toFunc(it).second })
        currIndex += funcs.size
        val imports = exp.repeated("import", currIndex, this::toImport).map {
            when(it.third) {
                is Node.Type.Func -> {
                    // TODO: why does smart cast not work here?
                    types += it.third as Node.Type.Func
                    Node.Import(it.first, it.second, Node.Import.Kind.Func(types.lastIndex))
                }
                is Node.Type.Table ->
                    Node.Import(it.first, it.second, Node.Import.Kind.Table(it.third as Node.Type.Table))
                is Node.Type.Memory ->
                    Node.Import(it.first, it.second, Node.Import.Kind.Memory(it.third as Node.Type.Memory))
                is Node.Type.Global ->
                    Node.Import(it.first, it.second, Node.Import.Kind.Global(it.third as Node.Type.Global))
                else ->
                    throw Exception("Unrecognized import kind: ${it.third}")
            }
        }
        currIndex += imports.size
        val exports = exp.repeated("export", currIndex, this::toExport)
        currIndex += exports.size
    }

    fun toOpMaybe(exp: SExpr.Multi, offset: Int): Pair<Node.Instr, Int>? {
        if (offset >= exp.vals.size) return null
        val head = exp.vals[offset] as SExpr.Symbol
        fun oneVar() = toVar(exp.vals[offset + 1] as SExpr.Symbol)
        val op = InstrOp.strToOpMap[head.contents]
        return when(op) {
            null -> null
            is InstrOp.ControlFlowOp.NoArg -> Pair(op.create, 1)
            is InstrOp.ControlFlowOp.TypeArg -> return null // Type not handled here
            is InstrOp.ControlFlowOp.DepthArg -> Pair(op.create(oneVar()), 2)
            is InstrOp.ControlFlowOp.TableArg -> {
                val vars = exp.vals.drop(offset + 1).asSequence().map(this::toVarMaybe).takeUntilNull().toList()
                Pair(op.create(vars.drop(1), vars.first()), offset + vars.size)
            }
            is InstrOp.CallOp.IndexArg -> Pair(op.create(oneVar()), 2)
            is InstrOp.CallOp.IndexReservedArg -> Pair(op.create(oneVar(), false), 2)
            is InstrOp.ParamOp.NoArg -> Pair(op.create, 1)
            is InstrOp.VarOp.IndexArg -> Pair(op.create(oneVar()), 2)
            is InstrOp.MemOp.FlagsOffsetArg -> {
                var count = 1
                var instrOffset = 0
                var instrAlign = 0
                if (exp.vals.size > offset + count) {
                    val maybeSym = exp.vals[offset + count] as? SExpr.Symbol
                    if (maybeSym != null && maybeSym.contents.startsWith("offset=")) {
                        instrOffset = maybeSym.contents.substring(7).toInt()
                        count++
                    }
                }
                if (exp.vals.size > offset + count) {
                    val maybeSym = exp.vals[offset + count] as? SExpr.Symbol
                    if (maybeSym != null && maybeSym.contents.startsWith("align=")) {
                        instrAlign = maybeSym.contents.substring(6).toInt()
                        count++
                    }
                }
                Pair(op.create(instrAlign, instrOffset), count)
            }
            is InstrOp.MemOp.ReservedArg -> Pair(op.create(false), 1)
            is InstrOp.ConstOp.IntArg -> Pair(op.create((exp.vals[offset + 1] as SExpr.Symbol).contents.toInt()), 2)
            is InstrOp.ConstOp.LongArg -> Pair(op.create((exp.vals[offset + 1] as SExpr.Symbol).contents.toLong()), 2)
            is InstrOp.ConstOp.FloatArg -> Pair(op.create((exp.vals[offset + 1] as SExpr.Symbol).contents.toFloat()), 2)
            is InstrOp.ConstOp.DoubleArg -> Pair(op.create((exp.vals[offset + 1] as SExpr.Symbol).contents.toDouble()), 2)
            is InstrOp.CompareOp.NoArg -> Pair(op.create, 1)
            is InstrOp.NumOp.NoArg -> Pair(op.create, 1)
            is InstrOp.ConvertOp.NoArg -> Pair(op.create, 1)
            is InstrOp.ReinterpretOp.NoArg -> Pair(op.create, 1)
        }
    }

    fun toParams(exp: SExpr.Multi): Pair<String?, List<Node.Type.Value>> {
        exp.vals.first().requireSymbol("param")
        val name = exp.maybeName(1)
        if (name != null) return Pair(name, listOf(toType(exp.vals[2] as SExpr.Symbol)))
        return Pair(null, exp.vals.drop(1).map { toType(it as SExpr.Symbol) })
    }

    fun toResizeableLimits(exp: SExpr.Multi, offset: Int): Node.ResizableLimits {
        var max: Int? = null
        if (offset + 1 < exp.vals.size && exp.vals[offset + 1] is SExpr.Symbol) {
            max = (exp.vals[offset + 1] as SExpr.Symbol).contents.toIntOrNull()
        }
        return Node.ResizableLimits((exp.vals[offset] as SExpr.Symbol).contents.toInt(), max)
    }

    fun toResult(exp: SExpr.Multi): Node.Type.Value {
        exp.vals.first().requireSymbol("result")
        return toType(exp.vals[1] as SExpr.Symbol)
    }

    fun toTableSig(exp: SExpr.Multi, offset: Int): Node.Type.Table {
        val limits = toResizeableLimits(exp, offset)
        return Node.Type.Table(toElemType(exp, offset + if (limits.maximum == null) 0 else 1), limits)
    }

    fun toType(exp: SExpr.Symbol): Node.Type.Value {
        return toTypeMaybe(exp) ?: throw Exception("Unknown value type: ${exp.contents}")
    }

    fun toTypeMaybe(exp: SExpr.Symbol): Node.Type.Value? = when(exp.contents) {
        "i32" -> Node.Type.Value.I32
        "i64" -> Node.Type.Value.I64
        "f32" -> Node.Type.Value.F32
        "f64" -> Node.Type.Value.F64
        else -> null
    }

    fun toTypeDef(exp: SExpr.Multi): Pair<String?, Node.Type.Func> {
        exp.vals.first().requireSymbol("typedef")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        val funcSigExp = exp.vals[currIndex] as SExpr.Multi
        funcSigExp.vals[0].requireSymbol("func")
        return Pair(name, toFuncSig(funcSigExp.vals[1] as SExpr.Multi))
    }

    fun toVar(exp: SExpr.Symbol): Int {
        // TODO: what about name?
        return exp.contents.toInt()
    }

    fun toVarMaybe(exp: SExpr): Int? {
        // TODO: what about name?
        if (exp !is SExpr.Symbol) return null
        return exp.contents.toIntOrNull()
    }

    private fun SExpr.requireSymbol(contents: String, quotedCheck: Boolean? = null) {
        if (this is SExpr.Symbol && this.contents == contents &&
                (quotedCheck == null || this.quoted == quotedCheck)) {
            return
        }
        throw Exception("Expected symbol of $contents, got $this")
    }

    private fun SExpr.Multi.maybeName(index: Int): String? {
        if (this.vals.size > index && this.vals[index] is SExpr.Symbol) {
            val sym = this.vals[index] as SExpr.Symbol
            if (!sym.quoted && sym.contents[0] == '$') return sym.contents
        }
        return null
    }

    private fun <T> SExpr.Multi.repeated(name: String, startOffset: Int, fn: (SExpr.Multi) -> T): List<T> {
        var offset = startOffset
        var ret = emptyList<T>()
        while (this.vals.size > offset) {
            if (this.vals[offset] !is SExpr.Multi) break
            val expMulti = this.vals[offset] as SExpr.Multi
            if (expMulti.vals[0] !is SExpr.Symbol) break
            val expName = expMulti.vals[0] as SExpr.Symbol
            if (expName.quoted || expName.contents != name) break
            ret += fn(expMulti)
            offset++
        }
        return ret
    }
}


