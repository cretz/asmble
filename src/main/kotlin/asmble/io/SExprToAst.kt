package asmble.io

import asmble.ast.Node
import asmble.ast.Node.InstrOp
import asmble.ast.SExpr
import asmble.ast.Script
import asmble.util.takeUntilNullLazy
import com.google.common.primitives.UnsignedInteger
import java.math.BigInteger

typealias NameMap = Map<String, Int>

open class SExprToAst {
    data class ExprContext(val nameMap: NameMap, val blockDepth: Int = 0)

    fun toAction(exp: SExpr.Multi): Script.Cmd.Action {
        var index = 1
        val name = exp.maybeName(index)
        if (name != null) index++
        val str = exp.vals[index].symbolStr()!!
        index++
        return when(exp.vals.first().symbolStr()) {
            "invoke" ->
                Script.Cmd.Action.Invoke(name, str, exp.vals.drop(index).map {
                    toExprMaybe(it as SExpr.Multi, ExprContext(emptyMap()))
                })
            "get" ->
                Script.Cmd.Action.Get(name, str)
            else -> throw Exception("Invalid action exp $exp")
        }
    }

    fun toAssertion(exp: SExpr.Multi): Script.Cmd.Assertion {
        val mult = exp.vals[1] as SExpr.Multi
        return when(exp.vals.first().symbolStr()) {
            "assert_return" ->
                Script.Cmd.Assertion.Return(toAction(mult),
                    exp.vals.drop(2).map { toExprMaybe(it as SExpr.Multi, ExprContext(emptyMap())) })
            "assert_return_nan" ->
                Script.Cmd.Assertion.ReturnNan(toAction(mult))
            "assert_trap" ->
                if (mult.vals.first().symbolStr() == "module")
                    Script.Cmd.Assertion.TrapModule(toModule(mult).second, exp.vals[2].symbolStr()!!)
                else Script.Cmd.Assertion.Trap(toAction(mult), exp.vals[2].symbolStr()!!)
            "assert_malformed" ->
                Script.Cmd.Assertion.Malformed(toModule(mult).second, exp.vals[2].symbolStr()!!)
            "assert_invalid" ->
                Script.Cmd.Assertion.Invalid(
                    Script.LazyModule.SExpr(mult) { toModule(it).second },
                    exp.vals[2].symbolStr()!!
                )
            "assert_soft_invalid" ->
                Script.Cmd.Assertion.SoftInvalid(toModule(mult).second, exp.vals[2].symbolStr()!!)
            "assert_unlinkable" ->
                Script.Cmd.Assertion.Unlinkable(toModule(mult).second, exp.vals[2].symbolStr()!!)
            "assert_exhaustion" ->
                Script.Cmd.Assertion.Exhaustion(toAction(mult), exp.vals[2].symbolStr()!!)
            else -> throw Exception("Invalid assertion exp $exp")
        }
    }

    fun toBlockSigMaybe(exp: SExpr.Multi, offset: Int): List<Node.Type.Value> {
        val types = exp.vals.drop(offset).takeUntilNullLazy { if (it is SExpr.Symbol) toTypeMaybe(it) else null }
        // We can only handle one type for now
        require(types.size  <= 1)
        return types
    }

    fun toCmd(exp: SExpr.Multi): Script.Cmd {
        val expName = exp.vals.first().symbolStr()
        return when(expName) {
            "module" -> toModule(exp).let { Script.Cmd.Module(it.second, it.first) }
            "register" -> toRegister(exp)
            "invoke", "get" -> toAction(exp)
            "assert_return", "assert_return_nan", "assert_trap", "assert_malformed", "assert_invalid",
                "assert_soft_invalid", "assert_unlinkable", "assert_exhaustion" -> toAssertion(exp)
            "script", "input", "output" -> toMeta(exp)
            else -> error("Unrecognized cmd expr '$expName'")
        }
    }

    fun toData(exp: SExpr.Multi, nameMap: NameMap): Node.Data {
        exp.requireFirstSymbol("data")
        var currIndex = 1
        val index = toVarMaybe(exp.vals[currIndex], nameMap)
        if (index != null) currIndex++
        val offsetMulti = exp.vals[currIndex] as SExpr.Multi
        val instrs = if (offsetMulti.vals.firstOrNull()?.symbolStr() == "offset") {
            toInstrs(offsetMulti, 1, ExprContext(nameMap)).first
        } else toExprMaybe(offsetMulti, ExprContext(nameMap))
        currIndex++
        val strs = exp.vals.drop(currIndex).fold("") { str, sym -> str + (sym as SExpr.Symbol).contents }
        return Node.Data(index ?: 0, instrs, strs.toByteArray(Charsets.UTF_8))
    }

    fun toElem(exp: SExpr.Multi, nameMap: NameMap): Node.Elem {
        exp.requireFirstSymbol("elem")
        var currIndex = 1
        val index = toVarMaybe(exp.vals[currIndex], nameMap)
        if (index != null) currIndex++
        val offsetMulti = exp.vals[currIndex] as SExpr.Multi
        val instrs = if (offsetMulti.vals.firstOrNull()?.symbolStr() == "offset") {
            toInstrs(offsetMulti, 1, ExprContext(nameMap)).first
        } else toExprMaybe(offsetMulti, ExprContext(nameMap))
        currIndex++
        val vars = exp.vals.drop(currIndex + 1).map { toVar(it as SExpr.Symbol, nameMap) }
        return Node.Elem(index ?: 0, instrs, vars)
    }

    fun toElemType(exp: SExpr.Multi, offset: Int): Node.ElemType {
        exp.vals[offset].requireSymbol("anyfunc")
        return Node.ElemType.ANYFUNC
    }

    fun toElemTypeMaybe(exp: SExpr.Multi, offset: Int): Node.ElemType? {
        if (exp.vals[offset].symbolStr() == "anyfunc") return Node.ElemType.ANYFUNC
        return null
    }

    fun toExport(exp: SExpr.Multi, nameMap: NameMap): Node.Export {
        exp.requireFirstSymbol("export")
        val field = exp.vals[1].symbolStr()!!
        val kind = exp.vals[2] as SExpr.Multi
        val kindIndex = toVar(kind.vals[1].symbol()!!, nameMap)
        val extKind = when(kind.vals[0].symbolStr()) {
            "func" -> Node.ExternalKind.FUNCTION
            "global" -> Node.ExternalKind.GLOBAL
            "table" -> Node.ExternalKind.TABLE
            "memory" -> Node.ExternalKind.MEMORY
            else -> throw Exception("Unrecognized kind: ${kind.vals[0]}")
        }
        return Node.Export(field, extKind, kindIndex)
    }

    fun toExprMaybe(exp: SExpr.Multi, ctx: ExprContext): List<Node.Instr> {
        // <op> or <op> <expr>+
        val maybeOpAndOffset = toOpMaybe(exp, 0, ctx)
        if (maybeOpAndOffset != null) {
            // Everything left in the multi should be a a multi expression
            return exp.vals.drop(maybeOpAndOffset.second).flatMap {
                toExprMaybe(it as SExpr.Multi, ctx)
            } + maybeOpAndOffset.first
        }
        // Other blocks take up the rest (ignore names)
        val blockName = exp.vals.first().symbolStr()
        var opOffset = 1
        var innerCtx = ctx.copy(blockDepth = ctx.blockDepth + 1)
        exp.maybeName(opOffset)?.also {
            opOffset++
            innerCtx = innerCtx.copy(nameMap = innerCtx.nameMap + (it to innerCtx.blockDepth))
        }

        val sigs = toBlockSigMaybe(exp, opOffset)
        opOffset += sigs.size
        when(blockName) {
            "block" ->
                return listOf(Node.Instr.Block(sigs.firstOrNull())) +
                        toInstrs(exp, opOffset, innerCtx).first + Node.Instr.End
            "loop" ->
                return listOf(Node.Instr.Loop(sigs.firstOrNull())) +
                        toInstrs(exp, opOffset, innerCtx).first + Node.Instr.End
            "if" -> {
                if (opOffset >= exp.vals.size) return emptyList()
                var ret = emptyList<Node.Instr>()
                // Try expressions
                var exprMulti = exp.vals[opOffset] as SExpr.Multi
                val exprs = toExprMaybe(exprMulti, ctx)
                // Conditional?
                if (exprs.isNotEmpty()) {
                    // First expression means it's the conditional, so push on stack
                    ret += exprs
                    opOffset++
                    exprMulti = exp.vals[opOffset] as SExpr.Multi
                }
                ret += Node.Instr.If(sigs.firstOrNull())
                // Is it a "then"?
                if (exprMulti.vals.firstOrNull()?.symbolStr() == "then") ret += toInstrs(exprMulti, 1, innerCtx).first
                else ret += toExprMaybe(exprMulti, innerCtx)
                // Now check for "else"
                opOffset++
                if (opOffset < exp.vals.size) {
                    ret += Node.Instr.Else
                    exprMulti = exp.vals[opOffset] as SExpr.Multi
                    if (exprMulti.vals.firstOrNull()?.symbolStr() == "else") ret += toInstrs(exprMulti, 1, innerCtx).first
                    else ret += toExprMaybe(exprMulti, innerCtx)
                }
                return ret + Node.Instr.End
            }
            else -> return emptyList()
        }
    }

    fun toFunc(exp: SExpr.Multi, origNameMap: NameMap): Triple<String?, Node.Func, ImportOrExport?> {
        exp.requireFirstSymbol("func")
        var currentIndex = 1
        val name = exp.maybeName(currentIndex)
        if (name != null) currentIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currentIndex)
        if (maybeImpExp != null) currentIndex++
        var (nameMap, exprsUsed, sig) = toFuncSig(exp, currentIndex, origNameMap)
        currentIndex += exprsUsed
        val locals = exp.repeated("local", currentIndex, { toLocals(it) }).mapIndexed { index, (nameMaybe, vals) ->
            nameMaybe?.also { require(vals.size == 1); nameMap += it to (index + sig.params.size) }
            vals
        }
        currentIndex += locals.size
        val (instrs, _) = toInstrs(exp, currentIndex, ExprContext(nameMap))
        // Imports can't have locals or instructions
        if (maybeImpExp?.importModule != null) require(locals.isEmpty() && instrs.isEmpty())
        return Triple(name, Node.Func(sig, locals.flatten(), instrs), maybeImpExp)
    }

    fun toFuncSig(exp: SExpr.Multi, offset: Int, origNameMap: NameMap): Triple<NameMap, Int, Node.Type.Func> {
        // TODO: support type version
        if ((exp.vals.getOrNull(offset) as? SExpr.Multi)?.vals?.firstOrNull()?.symbolStr() == "type") {
            throw Exception("Func type ref not yet supported")
        }
        var nameMap = origNameMap
        val params = exp.repeated("param", offset, { toParams(it) }).mapIndexed { index, (nameMaybe, vals) ->
            nameMaybe?.also { require(vals.size == 1); nameMap += it to index }
            vals
        }
        val results = exp.repeated("result", offset + params.size, this::toResult)
        require(results.size <= 1)
        return Triple(nameMap, params.size + results.size, Node.Type.Func(params.flatten(), results.firstOrNull()))
    }

    fun toGlobal(exp: SExpr.Multi, nameMap: NameMap): Triple<String?, Node.Global, ImportOrExport?> {
        exp.requireFirstSymbol("global")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currIndex)
        if (maybeImpExp != null) currIndex++
        val sig = toGlobalSig(exp.vals[currIndex])
        currIndex++
        val (instrs, _) = toInstrs(exp, currIndex, ExprContext(nameMap))
        // Imports can't have instructions
        require((maybeImpExp?.importModule != null) == instrs.isEmpty())
        return Triple(name, Node.Global(sig, instrs), maybeImpExp)
    }

    fun toGlobalSig(exp: SExpr): Node.Type.Global = when(exp) {
        is SExpr.Symbol -> Node.Type.Global(toType(exp), false)
        is SExpr.Multi -> {
            exp.vals.first().requireSymbol("mut")
            Node.Type.Global(toType(exp.vals[1] as SExpr.Symbol), true)
        }
    }

    fun toImport(exp: SExpr.Multi): Triple<String, String, Node.Type> {
        exp.requireFirstSymbol("import")
        val module = exp.vals[1].symbolStr()!!
        val field = exp.vals[2].symbolStr()!!
        val kind = exp.vals[3] as SExpr.Multi
        val kindName = kind.vals.firstOrNull()?.symbolStr()
        val kindSubOffset = if (kind.maybeName(1) == null) 1 else 2
        return Triple(module, field, when(kindName) {
            "func" -> toFuncSig(kind, kindSubOffset, emptyMap()).third
            "global" -> toGlobalSig(kind.vals[kindSubOffset])
            "table" -> toTableSig(kind, kindSubOffset)
            "memory" -> toMemorySig(kind, kindSubOffset)
            else -> throw Exception("Unrecognized type: $kindName")
        })
    }

    fun toImportOrExportMaybe(exp: SExpr.Multi, offset: Int): ImportOrExport? {
        if (offset >= exp.vals.size) return null
        val multi = exp.vals[offset] as? SExpr.Multi ?: return null
        val multiHead = multi.vals[0] as? SExpr.Symbol ?: return null
        return when (multiHead.contents) {
            "export" -> ImportOrExport(multi.vals[1].symbolStr()!!, null)
            "import" -> ImportOrExport(multi.vals[2].symbolStr()!!, multi.vals[1].symbolStr()!!)
            else -> null
        }
    }

    fun toInstrs(
        exp: SExpr.Multi,
        offset: Int,
        ctx: ExprContext,
        mustCompleteExp: Boolean = true
    ): Pair<List<Node.Instr>, Int> {
        var runningOffset = 0
        var ret = emptyList<Node.Instr>()
        while (offset + runningOffset < exp.vals.size) {
            val maybeInstrAndOffset = toInstrMaybe(exp, offset + runningOffset, ctx)
            if (maybeInstrAndOffset.first.isEmpty()) break
            ret += maybeInstrAndOffset.first
            runningOffset += maybeInstrAndOffset.second
        }
        if (mustCompleteExp) require(offset + runningOffset == exp.vals.size) {
            "Unrecognized instruction: ${exp.vals[offset + runningOffset]}"
        }
        return Pair(ret, runningOffset)
    }

    fun toInstrMaybe(exp: SExpr.Multi, offset: Int, ctx: ExprContext): Pair<List<Node.Instr>, Int> {
        // <expr>
        if (exp.vals[offset] is SExpr.Multi) {
            val exprs = toExprMaybe(exp.vals[offset] as SExpr.Multi, ctx)
            return Pair(exprs, if (exprs.isEmpty()) 0 else 1)
        }
        // <op>
        val maybeOpAndOffset = toOpMaybe(exp, offset, ctx)
        if (maybeOpAndOffset != null) {
            return Pair(listOf(maybeOpAndOffset.first), maybeOpAndOffset.second)
        }
        // Other blocks (ignore names)
        val blockName = exp.vals[offset].symbolStr() ?: return Pair(emptyList(), 0)
        var opOffset = 1
        var innerCtx = ctx.copy(blockDepth = ctx.blockDepth + 1)
        val maybeName = exp.maybeName(offset + opOffset)
        if (maybeName != null) {
            opOffset++
            innerCtx = innerCtx.copy(nameMap = innerCtx.nameMap + (maybeName to innerCtx.blockDepth))
        }
        val sigs = toBlockSigMaybe(exp, offset + opOffset)
        opOffset += sigs.size
        var ret = emptyList<Node.Instr>()
        when(blockName) {
            "block" -> {
                ret += Node.Instr.Block(sigs.firstOrNull())
                toInstrs(exp, offset + opOffset, innerCtx, false).also {
                    ret += it.first
                    opOffset += it.second
                }
            }
            "loop" -> {
                ret += Node.Instr.Loop(sigs.firstOrNull())
                toInstrs(exp, offset + opOffset, innerCtx, false).also {
                    ret += it.first
                    opOffset += it.second
                }
            }
            "if" -> {
                ret += Node.Instr.If(sigs.firstOrNull())
                toInstrs(exp, offset + opOffset, innerCtx, false).also {
                    ret += it.first
                    opOffset += it.second
                }
                // Else?
                if (offset + opOffset < exp.vals.size) {
                    if (exp.vals[offset + opOffset].symbolStr() == "else") {
                        ret += Node.Instr.Else
                        opOffset++
                        exp.maybeName(offset + opOffset)?.also {
                            opOffset++
                            innerCtx = innerCtx.copy(nameMap = innerCtx.nameMap + (it to ctx.blockDepth))
                        }
                        toInstrs(exp, offset + opOffset, innerCtx, false).also {
                            ret += it.first
                            opOffset += it.second
                        }
                    }
                }
            }
            else -> return Pair(emptyList(), 0)
        }
        require(exp.vals[offset + opOffset].symbolStr() == "end")
        ret += Node.Instr.End
        opOffset++
        exp.maybeName(offset + opOffset)?.also {
            opOffset++
            require(it == maybeName, { "Expected end for $maybeName, got $it" })
        }
        return Pair(ret, opOffset)
    }

    fun toLocals(exp: SExpr.Multi): Pair<String?, List<Node.Type.Value>> {
        exp.requireFirstSymbol("local")
        val name = exp.maybeName(1)
        if (name != null) return Pair(name, listOf(toType(exp.vals[2].symbol()!!)))
        return Pair(null, exp.vals.drop(1).map { toType(it.symbol()!!) })
    }

    fun toMemory(exp: SExpr.Multi): Triple<String?, Node.Type.Memory, ImportOrExport?> {
        exp.requireFirstSymbol("memory")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currIndex)
        if (maybeImpExp != null) currIndex++
        // Try data approach
        if (exp.vals[currIndex] is SExpr.Multi) throw Exception("Data string not yet supported for memory")
        return Triple(name, toMemorySig(exp, currIndex), maybeImpExp)
    }

    fun toMemorySig(exp: SExpr.Multi, offset: Int): Node.Type.Memory {
        return Node.Type.Memory(toResizeableLimits(exp, offset))
    }

    fun toMeta(exp: SExpr.Multi): Script.Cmd.Meta {
        val name = exp.maybeName(1)
        val index = if (name == null) 1 else 2
        return when(exp.vals.first().symbolStr()) {
            "script" -> Script.Cmd.Meta.Script(name, toScript(exp.vals[index] as SExpr.Multi))
            "input" -> Script.Cmd.Meta.Input(name, exp.vals[index].symbolStr()!!)
            "output" -> Script.Cmd.Meta.Output(name, if (index >= exp.vals.size) null else exp.vals[index].symbolStr())
            else -> throw Exception("Invalid meta exp $exp")
        }
    }

    fun toModule(exp: SExpr.Multi): Pair<String?, Node.Module> {
        exp.requireFirstSymbol("module")
        val name = exp.maybeName(1)
        var mod = Node.Module()

        // Go over each module element and apply to the module
        // But we have to do all imports first before anything else, so we separate them
        val (importExps, nonImportExps) = exp.vals.mapNotNull { it as? SExpr.Multi }.partition {
            when(it.vals.firstOrNull()?.symbolStr()) {
                "import" -> true
                "func", "global", "table", "memory" -> {
                    val possibleImportIndex = if (it.maybeName(1) == null) 1 else 2
                    (it.vals.getOrNull(possibleImportIndex) as? SExpr.Multi)?.vals?.firstOrNull()?.symbolStr() == "import"
                }
                else -> false
            }
        }

        // Eagerly build the names (for forward decls)
        val nameMap = toModuleNameMap(importExps, nonImportExps)

        // Imports first. We keep track of the counts for exports later
        var funcCount = 0
        var globalCount = 0
        var tableCount = 0
        var memoryCount = 0
        importExps.forEach {
            val import = when(it.vals.firstOrNull()?.symbolStr()) {
                "import" -> toImport(it)
                "func" -> toFunc(it, nameMap).let { (_, fn, impExp) ->
                    Triple(impExp!!.importModule!!, impExp.field, fn.type)
                }
                "global" -> toGlobal(it, nameMap).let { (_, glb, impExp) ->
                    Triple(impExp!!.importModule!!, impExp.field, glb.type)
                }
                "table" -> toTable(it).let { (_, tbl, impExp) ->
                    Triple(impExp!!.importModule!!, impExp.field, tbl)
                }
                "memory" -> toMemory(it).let { (_, mem, impExp) ->
                    Triple(impExp!!.importModule!!, impExp.field, mem)
                }
                else -> throw Exception("Unknown import exp: $it")
            }
            import.also { (module, field, kind) ->
                val importKind = when(kind) {
                    is Node.Type.Func -> { mod = mod.copy(types = mod.types + kind); Node.Import.Kind.Func(funcCount++) }
                    is Node.Type.Global -> { globalCount++; Node.Import.Kind.Global(kind) }
                    is Node.Type.Table -> { tableCount++; Node.Import.Kind.Table(kind) }
                    is Node.Type.Memory -> { memoryCount++; Node.Import.Kind.Memory(kind) }
                    else -> throw Exception("Unrecognized import kind: $kind")
                }
                mod = mod.copy(imports = mod.imports + Node.Import(module, field, importKind))
            }
        }

        // Now everything else
        fun addMaybeExport(impExp: ImportOrExport?, extKind: Node.ExternalKind, index: Int) {
            impExp?.also { mod = mod.copy(exports = mod.exports + Node.Export(it.field, extKind, index)) }
        }
        nonImportExps.forEach {
            when(it.vals.firstOrNull()?.symbolStr()) {
                "type" -> mod = mod.copy(types = mod.types + toTypeDef(it, nameMap).second)
                "func" -> toFunc(it, nameMap).also { (_, fn, impExp) ->
                    addMaybeExport(impExp, Node.ExternalKind.FUNCTION, funcCount++)
                    mod = mod.copy(funcs = mod.funcs + fn)
                }
                "export" -> mod = mod.copy(exports = mod.exports + toExport(it, nameMap))
                "global" -> toGlobal(it, nameMap).also { (_, glb, impExp) ->
                    addMaybeExport(impExp, Node.ExternalKind.GLOBAL, globalCount++)
                    mod = mod.copy(globals = mod.globals + glb)
                }
                "table" -> toTable(it).also { (_, tbl, impExp) ->
                    addMaybeExport(impExp, Node.ExternalKind.TABLE, tableCount++)
                    mod = mod.copy(tables = mod.tables + tbl)
                }
                "memory" -> toMemory(it).also { (_, mem, impExp) ->
                    addMaybeExport(impExp, Node.ExternalKind.MEMORY, memoryCount++)
                    mod = mod.copy(memories = mod.memories + mem)
                }
                "elem" -> mod = mod.copy(elems = mod.elems + toElem(it, nameMap))
                "data" -> mod = mod.copy(data = mod.data + toData(it, nameMap))
                "start" -> mod = mod.copy(startFuncIndex = toStart(it, nameMap))
                else -> throw Exception("Unknown non-import exp $exp")
            }
        }

        return name to mod
    }

    fun toModuleNameMap(importExps: List<SExpr.Multi>, nonImportExps: List<SExpr.Multi>): NameMap {
        var typeCount = 0
        var funcCount = 0
        var globalCount = 0
        var tableCount = 0
        var memoryCount = 0

        var namesToIndices = emptyMap<String, Int>()

        fun maybeAddName(name: String?, index: Int) { name?.let { namesToIndices += it to index } }

        // First, go over everything in the module and get names as indices
        // All imports first
        importExps.forEach {
            val kindExp = when (it.vals.firstOrNull()?.symbolStr()) {
                "import" -> it.vals[3] as SExpr.Multi
                else -> it
            }
            val kindName = kindExp.maybeName(1)
            when (kindExp.vals.firstOrNull()?.symbolStr()) {
                "func" -> maybeAddName(kindName, funcCount++)
                "global" -> maybeAddName(kindName, globalCount++)
                "table" -> maybeAddName(kindName, tableCount++)
                "memory" -> maybeAddName(kindName, memoryCount++)
                else -> throw Exception("Unknown import exp: $it")
            }
        }
        // Now the rest
        nonImportExps.forEach {
            val kindName = it.maybeName(1)
            when (it.vals.firstOrNull()?.symbolStr()) {
                "type" -> maybeAddName(kindName, typeCount++)
                "func" -> maybeAddName(kindName, funcCount++)
                "global" -> maybeAddName(kindName, globalCount++)
                "table" -> maybeAddName(kindName, tableCount++)
                "memory" -> maybeAddName(kindName, memoryCount++)
                else -> {}
            }
        }
        return namesToIndices
    }

    fun toOpMaybe(exp: SExpr.Multi, offset: Int, ctx: ExprContext): Pair<Node.Instr, Int>? {
        if (offset >= exp.vals.size) return null
        val head = exp.vals[offset].symbol()!!
        fun varIsStringRef(off: Int = offset + 1) = exp.vals[off].symbolStr()?.firstOrNull() == '$'
        fun oneVar(off: Int = offset + 1) = toVar(exp.vals[off].symbol()!!, ctx.nameMap)
        // Some are not handled here:
        when (head.contents) {
            "block", "loop", "if", "else", "end" -> return null
            else -> { }
        }
        val op = InstrOp.strToOpMap[head.contents]
        return when(op) {
            null -> null
            is InstrOp.ControlFlowOp.NoArg -> Pair(op.create, 1)
            is InstrOp.ControlFlowOp.TypeArg -> return null // Type not handled here
            is InstrOp.ControlFlowOp.DepthArg -> {
                // Named depth is special, because we actually subtract from our current depth
                if (varIsStringRef()) Pair(op.create(ctx.blockDepth - oneVar()), 2)
                else Pair(op.create(oneVar()), 2)
            }
            is InstrOp.ControlFlowOp.TableArg -> {
                val vars = exp.vals.drop(offset + 1).takeUntilNullLazy {
                    toVarMaybe(it, ctx.nameMap)?.let { tableVar ->
                        // Named depth is subtracted from current depth
                        if (it.symbolStr()?.firstOrNull() == '$') ctx.blockDepth - tableVar else tableVar
                    }
                }
                Pair(op.create(vars.dropLast(1), vars.last()), offset + 1 + vars.size)
            }
            is InstrOp.CallOp.IndexArg -> Pair(op.create(oneVar()), 2)
            is InstrOp.CallOp.IndexReservedArg -> Pair(op.create(oneVar(), false), 2)
            is InstrOp.ParamOp.NoArg -> Pair(op.create, 1)
            is InstrOp.VarOp.IndexArg -> Pair(op.create(oneVar()), 2)
            is InstrOp.MemOp.AlignOffsetArg -> {
                var count = 1
                var instrOffset = 0L
                var instrAlign = 0
                if (exp.vals.size > offset + count) exp.vals[offset + count].symbolStr().also {
                    if (it != null && it.startsWith("offset=")) {
                        instrOffset = it.substring(7).toUnsignedIntConst().toLong()
                        count++
                    }
                }
                if (exp.vals.size > offset + count) exp.vals[offset + count].symbolStr().also {
                    if (it != null && it.startsWith("align=")) {
                        instrAlign = it.substring(6).toInt()
                        require(instrAlign > 0 && instrAlign and (instrAlign - 1) == 0) {
                            "Alignment expected to be positive power of 2, but got $instrAlign"
                        }
                        count++
                    }
                }
                Pair(op.create(instrAlign, instrOffset), count)
            }
            is InstrOp.MemOp.ReservedArg -> Pair(op.create(false), 1)
            is InstrOp.ConstOp.IntArg -> Pair(op.create(exp.vals[offset + 1].symbol()!!.contents.toIntConst()), 2)
            is InstrOp.ConstOp.LongArg -> Pair(op.create(exp.vals[offset + 1].symbol()!!.contents.toLongConst()), 2)
            is InstrOp.ConstOp.FloatArg -> Pair(op.create(exp.vals[offset + 1].symbol()!!.contents.toFloatConst()), 2)
            is InstrOp.ConstOp.DoubleArg -> Pair(op.create(exp.vals[offset + 1].symbol()!!.contents.toDoubleConst()), 2)
            is InstrOp.CompareOp.NoArg -> Pair(op.create, 1)
            is InstrOp.NumOp.NoArg -> Pair(op.create, 1)
            is InstrOp.ConvertOp.NoArg -> Pair(op.create, 1)
            is InstrOp.ReinterpretOp.NoArg -> Pair(op.create, 1)
        }
    }

    fun toParams(exp: SExpr.Multi): Pair<String?, List<Node.Type.Value>> {
        exp.requireFirstSymbol("param")
        val name = exp.maybeName(1)
        if (name != null) return Pair(name, listOf(toType(exp.vals[2].symbol()!!)))
        return Pair(null, exp.vals.drop(1).map { toType(it.symbol()!!) })
    }

    fun toRegister(exp: SExpr.Multi): Script.Cmd.Register {
        exp.requireFirstSymbol("register")
        return Script.Cmd.Register(exp.vals[1].symbolStr()!!, exp.maybeName(2))
    }

    fun toResizeableLimits(exp: SExpr.Multi, offset: Int): Node.ResizableLimits {
        var max: Int? = null
        if (offset + 1 < exp.vals.size && exp.vals[offset + 1] is SExpr.Symbol) {
            max = exp.vals[offset + 1].symbolStr()?.toIntOrNull()
        }
        return Node.ResizableLimits(exp.vals[offset].symbolStr()!!.toInt(), max)
    }

    fun toResult(exp: SExpr.Multi): Node.Type.Value {
        exp.requireFirstSymbol("result")
        return toType(exp.vals[1].symbol()!!)
    }

    fun toScript(exp: SExpr.Multi): Script {
        return Script(exp.vals.map { toCmd(it as SExpr.Multi) })
    }

    fun toStart(exp: SExpr.Multi, nameMap: NameMap): Int {
        exp.requireFirstSymbol("start")
        return toVar(exp.vals[1].symbol()!!, nameMap)
    }

    fun toTable(exp: SExpr.Multi): Triple<String?, Node.Type.Table, ImportOrExport?> {
        exp.requireFirstSymbol("table")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currIndex)
        if (maybeImpExp != null) currIndex++
        // Try elem type approach
        val elemType = toElemTypeMaybe(exp, currIndex)
        if (elemType != null) {
            require(maybeImpExp?.importModule == null)
            throw Exception("Elem type not yet supported for table")
        }
        return Triple(name, toTableSig(exp, currIndex), maybeImpExp)
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

    fun toTypeDef(exp: SExpr.Multi, nameMap: NameMap): Pair<String?, Node.Type.Func> {
        exp.requireFirstSymbol("type")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        val funcSigExp = exp.vals[currIndex] as SExpr.Multi
        funcSigExp.requireFirstSymbol("func")
        return Pair(name, toFuncSig(funcSigExp, 1, nameMap).third)
    }

    fun toVar(exp: SExpr.Symbol, nameMap: NameMap): Int {
        return toVarMaybe(exp, nameMap) ?: throw Exception("No var for on exp $exp")
    }

    fun toVarMaybe(exp: SExpr, nameMap: NameMap): Int? {
        return exp.symbolStr()?.let { it ->
            if (it.startsWith("$")) nameMap[it] ?: throw Exception("Unable to find index for name $it")
            else if (it.startsWith("0x")) it.substring(2).toIntOrNull(16)
            else it.toIntOrNull()
        }
    }

    private fun String.toBigIntegerConst() =
        if (this.contains("0x")) BigInteger(this.replace("0x", ""), 16)
        else BigInteger(this)
    private fun String.toIntConst() = toBigIntegerConst().toInt()
    private fun String.toLongConst() = toBigIntegerConst().toLong()
    private fun String.toUnsignedIntConst() =
        if (this.contains("0x")) UnsignedInteger.valueOf(this.replace("0x", ""), 16)
        else UnsignedInteger.valueOf(this)

    private fun String.toFloatConst() =
        if (this == "infinity" || this == "+infinity") Float.POSITIVE_INFINITY
        else if (this == "-infinity") Float.NEGATIVE_INFINITY
        else if (this == "nan" || this == "+nan") java.lang.Float.intBitsToFloat(0x7fc00000)
        else if (this == "-nan") java.lang.Float.intBitsToFloat(0xffc00000.toInt())
        else if (this.startsWith("nan:") || this.startsWith("+nan:")) java.lang.Float.intBitsToFloat(
            0x7f800000 + this.substring(this.indexOf(':') + 1).toIntConst()
        ) else if (this.startsWith("-nan:")) java.lang.Float.intBitsToFloat(
            0xff800000.toInt() + this.substring(this.indexOf(':') + 1).toIntConst()
        ) else if (this.startsWith("0x") && !this.contains('P', true)) this.toLongConst().toFloat()
        else this.toFloat()
    private fun String.toDoubleConst() =
        if (this == "infinity" || this == "+infinity") Double.POSITIVE_INFINITY
        else if (this == "-infinity") Double.NEGATIVE_INFINITY
        else if (this == "nan" || this == "+nan") java.lang.Double.longBitsToDouble(0x7ff8000000000000)
        else if (this == "-nan") java.lang.Double.longBitsToDouble(-2251799813685248) // i.e. 0xfff8000000000000
        else if (this.startsWith("nan:") || this.startsWith("+nan:")) java.lang.Double.longBitsToDouble(
            0x7ff0000000000000 + this.substring(this.indexOf(':') + 1).toLongConst()
        ) else if (this.startsWith("-nan:")) java.lang.Double.longBitsToDouble(
            -4503599627370496 + this.substring(this.indexOf(':') + 1).toLongConst() // i.e. 0xfff0000000000000
        ) else if (this.startsWith("0x") && !this.contains('P', true)) this.toLongConst().toDouble()
        else this.toDouble()

    private fun SExpr.requireSymbol(contents: String, quotedCheck: Boolean? = null) {
        if (this is SExpr.Symbol && this.contents == contents &&
                (quotedCheck == null || this.quoted == quotedCheck)) {
            return
        }
        throw Exception("Expected symbol of $contents, got $this")
    }


    private fun SExpr.symbol() = this as? SExpr.Symbol
    private fun SExpr.symbolStr() = this.symbol()?.contents

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
            val expMulti = this.vals[offset] as? SExpr.Multi ?: break
            val expName = expMulti.vals[0] as? SExpr.Symbol ?: break
            if (expName.quoted || expName.contents != name) break
            ret += fn(expMulti)
            offset++
        }
        return ret
    }

    private fun SExpr.Multi.requireFirstSymbol(contents: String, quotedCheck: Boolean? = null) {
        if (this.vals.isEmpty()) throw Exception("Expected symbol of $contents, got empty")
        return this.vals.first().requireSymbol(contents, quotedCheck)
    }

    companion object : SExprToAst()
}
