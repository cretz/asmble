package asmble.io

import asmble.ast.Node
import asmble.ast.Node.InstrOp
import asmble.ast.SExpr
import asmble.ast.Script
import asmble.compile.jvm.Mem
import asmble.util.*
import java.io.ByteArrayInputStream
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
            "assert_return_canonical_nan" ->
                Script.Cmd.Assertion.ReturnNan(toAction(mult), canonical = true)
            "assert_return_arithmetic_nan" ->
                Script.Cmd.Assertion.ReturnNan(toAction(mult), canonical = false)
            "assert_trap" ->
                if (mult.vals.first().symbolStr() == "module")
                    Script.Cmd.Assertion.TrapModule(toModule(mult).second, exp.vals[2].symbolStr()!!)
                else Script.Cmd.Assertion.Trap(toAction(mult), exp.vals[2].symbolStr()!!)
            "assert_malformed" ->
                Script.Cmd.Assertion.Malformed(
                    Script.LazyModule.SExpr(mult) { toModule(mult).second },
                    exp.vals[2].symbolStr()!!
                )
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
            "module" ->
                toModule(exp).let { Script.Cmd.Module(it.second, it.first) }
            "register" ->
                toRegister(exp)
            "invoke", "get" ->
                toAction(exp)
            "assert_return", "assert_return_canonical_nan", "assert_return_arithmetic_nan", "assert_trap",
            "assert_malformed", "assert_invalid", "assert_soft_invalid", "assert_unlinkable", "assert_exhaustion" ->
                toAssertion(exp)
            "script", "input", "output" ->
                toMeta(exp)
            else ->
                error("Unrecognized cmd expr '$expName'")
        }
    }

    fun toData(exp: SExpr.Multi, nameMap: NameMap): Node.Data {
        exp.requireFirstSymbol("data")
        var currIndex = 1
        val index = toVarMaybe(exp.vals[currIndex], nameMap, "memory")
        if (index != null) currIndex++
        val offsetMulti = exp.vals[currIndex] as SExpr.Multi
        val instrs = if (offsetMulti.vals.firstOrNull()?.symbolStr() == "offset") {
            toInstrs(offsetMulti, 1, ExprContext(nameMap)).first
        } else toExprMaybe(offsetMulti, ExprContext(nameMap))
        currIndex++
        val bytes = exp.vals.drop(currIndex).fold(byteArrayOf()) { bytes, sym ->
            bytes + (sym as SExpr.Symbol).rawContentCharsToBytes()
        }
        return Node.Data(index ?: 0, instrs, bytes)
    }

    fun toElem(exp: SExpr.Multi, nameMap: NameMap): Node.Elem {
        exp.requireFirstSymbol("elem")
        var currIndex = 1
        val index = toVarMaybe(exp.vals[currIndex], nameMap, "table")
        if (index != null) currIndex++
        val offsetMulti = exp.vals[currIndex] as SExpr.Multi
        val instrs = if (offsetMulti.vals.firstOrNull()?.symbolStr() == "offset") {
            toInstrs(offsetMulti, 1, ExprContext(nameMap)).first
        } else toExprMaybe(offsetMulti, ExprContext(nameMap))
        currIndex++
        val vars = exp.vals.drop(currIndex).map { toVar(it as SExpr.Symbol, nameMap, "func") }
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
        val extKind = when(kind.vals[0].symbolStr()) {
            "func" -> Node.ExternalKind.FUNCTION
            "global" -> Node.ExternalKind.GLOBAL
            "table" -> Node.ExternalKind.TABLE
            "memory" -> Node.ExternalKind.MEMORY
            else -> throw Exception("Unrecognized kind: ${kind.vals[0]}")
        }
        val kindIndex = toVar(kind.vals[1].symbol()!!, nameMap, kind.vals[0].symbolStr()!!)
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
            innerCtx = innerCtx.copy(nameMap = innerCtx.nameMap + ("block:$it" to innerCtx.blockDepth))
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

    fun toFunc(
        exp: SExpr.Multi,
        origNameMap: NameMap,
        types: List<Node.Type.Func>
    ): Triple<String?, Node.Func, ImportOrExport?> {
        exp.requireFirstSymbol("func")
        var currentIndex = 1
        val name = exp.maybeName(currentIndex)
        if (name != null) currentIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currentIndex)
        if (maybeImpExp != null) currentIndex++
        var (nameMap, exprsUsed, sig) = toFuncSig(exp, currentIndex, origNameMap, types)
        currentIndex += exprsUsed
        val locals = exp.repeated("local", currentIndex, { toLocals(it) }).mapIndexed { index, (nameMaybe, vals) ->
            nameMaybe?.also { require(vals.size == 1); nameMap += "local:$it" to (index + sig.params.size) }
            vals
        }
        currentIndex += locals.size
        val (instrs, _) = toInstrs(exp, currentIndex, ExprContext(nameMap))
        // Imports can't have locals or instructions
        if (maybeImpExp?.importModule != null) require(locals.isEmpty() && instrs.isEmpty())
        return Triple(name, Node.Func(sig, locals.flatten(), instrs), maybeImpExp)
    }

    fun toFuncSig(
        exp: SExpr.Multi,
        offset: Int,
        origNameMap: NameMap,
        types: List<Node.Type.Func>
    ): Triple<NameMap, Int, Node.Type.Func> {
        val (typeRef, offset) =
            if ((exp.vals.getOrNull(offset) as? SExpr.Multi)?.vals?.firstOrNull()?.symbolStr() == "type") {
                val typeIdx = toVar((exp.vals[offset] as SExpr.Multi).vals[1].symbol()!!, origNameMap, "type")
                if (typeIdx >= types.size) throw IoErr.UnknownType(typeIdx)
                types[typeIdx] to offset + 1
            } else null to offset
        var nameMap = origNameMap
        val params = exp.repeated("param", offset, { toParams(it) }).mapIndexed { index, (nameMaybe, vals) ->
            nameMaybe?.also { require(vals.size == 1); nameMap += "local:$it" to index }
            vals
        }
        val results = exp.repeated("result", offset + params.size, this::toResult)
        if (results.size > 1) throw IoErr.InvalidResultArity()
        val usedExps = params.size + results.size + if (typeRef == null) 0 else 1
        // Check against type ref
        if (typeRef != null) {
            // No params or results means just use it
            if (params.isEmpty() && results.isEmpty()) return Triple(nameMap, usedExps, typeRef)
            // Otherwise, just make sure it matches
            require(typeRef.params == params.flatten() && typeRef.ret == results.firstOrNull()) {
                "Params for type ref do not match explicit ones"
            }
        }
        return Triple(nameMap, usedExps, Node.Type.Func(params.flatten(), results.firstOrNull()))
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
            "func" -> toFuncSig(kind, kindSubOffset, emptyMap(), emptyList()).third
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
            innerCtx = innerCtx.copy(nameMap = innerCtx.nameMap + ("block:$maybeName" to innerCtx.blockDepth))
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
                            innerCtx = innerCtx.copy(nameMap = innerCtx.nameMap + ("block:$it" to ctx.blockDepth))
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

    fun toMemory(exp: SExpr.Multi): Triple<String?, Either<Node.Type.Memory, Node.Data>, ImportOrExport?> {
        exp.requireFirstSymbol("memory")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currIndex)
        if (maybeImpExp != null) currIndex++
        // If it's a multi we assume "data", otherwise assume sig
        val memOrData = exp.vals[currIndex].let {
            when (it) {
                is SExpr.Multi -> {
                    it.requireFirstSymbol("data")
                    Either.Right(Node.Data(
                        0,
                        listOf(Node.Instr.I32Const(0)),
                        it.vals.drop(1).fold(byteArrayOf()) { b, exp -> b + exp.symbol()!!.rawContentCharsToBytes() }
                    ))
                }
                else -> Either.Left(toMemorySig(exp, currIndex))
            }
        }
        return Triple(name, memOrData, maybeImpExp)
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

        // If all of the other symbols after the name are quoted strings,
        // this needs to be parsed as a binary
        exp.vals.drop(if (name == null) 1 else 2).also { otherVals ->
            if (otherVals.isNotEmpty() && otherVals.find { it !is SExpr.Symbol || !it.quoted } == null)
                return name to toModuleFromBytes(otherVals.fold(byteArrayOf()) { bytes, strVal ->
                    bytes + (strVal as SExpr.Symbol).rawContentCharsToBytes()
                })
        }

        var mod = Node.Module()
        val exps = exp.vals.mapNotNull { it as? SExpr.Multi }

        // Eagerly build the names (for forward decls)
        var nameMap = toModuleForwardNameMap(exps)

        fun Node.Module.addTypeIfNotPresent(type: Node.Type.Func): Pair<Node.Module, Int> {
            val index = this.types.indexOf(type)
            if (index != -1) return this to index
            return this.copy(types = this.types + type) to this.types.size
        }

        // Keep counts for exports. We enforce/trust that there are no imports after non-imports
        var funcCount = 0
        var globalCount = 0
        var tableCount = 0
        var memoryCount = 0
        fun handleImport(module: String, field: String, kind: Node.Type) {
            // We make sure that an import doesn't happen after a non-import
            require(mod.funcs.isEmpty() && mod.globals.isEmpty() &&
                mod.tables.isEmpty() && mod.memories.isEmpty()) { "Import happened after non-import" }
            val importKind = when(kind) {
                is Node.Type.Func -> mod.addTypeIfNotPresent(kind).let { (m, idx) ->
                    funcCount++
                    mod = m
                    Node.Import.Kind.Func(idx)
                }
                is Node.Type.Global -> { globalCount++; Node.Import.Kind.Global(kind) }
                is Node.Type.Table -> { tableCount++; Node.Import.Kind.Table(kind) }
                is Node.Type.Memory -> { memoryCount++; Node.Import.Kind.Memory(kind) }
                else -> throw Exception("Unrecognized import kind: $kind")
            }
            mod = mod.copy(imports = mod.imports + Node.Import(module, field, importKind))
        }

        fun addMaybeExport(impExp: ImportOrExport?, extKind: Node.ExternalKind, index: Int) {
            impExp?.also { mod = mod.copy(exports = mod.exports + Node.Export(it.field, extKind, index)) }
        }

        // Now just handle all expressions in order
        exps.forEach { exp ->
            when(exp.vals.firstOrNull()?.symbolStr()) {
                "import" -> toImport(exp).let { (module, field, type) -> handleImport(module, field, type) }
                "type" -> toTypeDef(exp, nameMap).let { (name, type) ->
                    // We always add the type, even if it's a duplicate.
                    // Ref: https://github.com/WebAssembly/design/issues/1041
                    if (name != null) nameMap += "type:$name" to mod.types.size
                    mod = mod.copy(types = mod.types + type)
                }
                "export" -> mod = mod.copy(exports = mod.exports + toExport(exp, nameMap))
                "elem" -> mod = mod.copy(elems = mod.elems + toElem(exp, nameMap))
                "data" -> mod = mod.copy(data = mod.data + toData(exp, nameMap))
                "start" -> mod = mod.copy(startFuncIndex = toStart(exp, nameMap))
                "func" -> toFunc(exp, nameMap, mod.types).also { (_, fn, impExp) ->
                    if (impExp != null && impExp.importModule != null) {
                        handleImport(impExp.importModule, impExp.field, fn.type)
                    } else {
                        addMaybeExport(impExp, Node.ExternalKind.FUNCTION, funcCount++)
                        mod = mod.copy(funcs = mod.funcs + fn).addTypeIfNotPresent(fn.type).first
                    }
                }
                "global" -> toGlobal(exp, nameMap).let { (_, glb, impExp) ->
                    if (impExp != null && impExp.importModule != null) {
                        handleImport(impExp.importModule, impExp.field, glb.type)
                    } else {
                        addMaybeExport(impExp, Node.ExternalKind.GLOBAL, globalCount++)
                        mod = mod.copy(globals = mod.globals + glb)
                    }
                }
                "table" -> toTable(exp, nameMap).let { (_, tbl, impExp) ->
                    if (impExp != null && impExp.importModule != null) {
                        if (tbl !is Either.Left) error("Elem segment on import table")
                        handleImport(impExp.importModule, impExp.field, tbl.v)
                    } else {
                        addMaybeExport(impExp, Node.ExternalKind.TABLE, tableCount++)
                        when (tbl) {
                            is Either.Left -> mod = mod.copy(tables = mod.tables + tbl.v)
                            is Either.Right -> mod = mod.copy(
                                tables = mod.tables + Node.Type.Table(
                                    Node.ElemType.ANYFUNC,
                                    Node.ResizableLimits(tbl.v.funcIndices.size, tbl.v.funcIndices.size)
                                ),
                                elems = mod.elems + tbl.v
                            )
                        }
                    }
                }
                "memory" -> toMemory(exp).let { (_, mem, impExp) ->
                    if (impExp != null && impExp.importModule != null) {
                        if (mem !is Either.Left) error("Data segment on import mem")
                        handleImport(impExp.importModule, impExp.field, mem.v)
                    } else {
                        addMaybeExport(impExp, Node.ExternalKind.MEMORY, memoryCount++)
                        when (mem) {
                            is Either.Left -> mod = mod.copy(memories = mod.memories + mem.v)
                            is Either.Right -> mod = mod.copy(
                                memories = mod.memories + Node.Type.Memory(
                                    Node.ResizableLimits(mem.v.data.size, mem.v.data.size)
                                ),
                                data = mod.data + mem.v
                            )
                        }
                    }
                }
                else -> error("Unknown module exp: $exp")
            }
        }

        if (mod.memories.size + mod.imports.count { it.kind is Node.Import.Kind.Memory } > 1)
            throw IoErr.MultipleMemories()
        if (mod.tables.size + mod.imports.count { it.kind is Node.Import.Kind.Table } > 1)
            throw IoErr.MultipleTables()

        return name to mod
    }

    fun toModuleFromBytes(bytes: ByteArray) = BinaryToAst.toModule(ByteReader.InputStream(ByteArrayInputStream(bytes)))

    fun toModuleForwardNameMap(exps: List<SExpr.Multi>): NameMap {
        // We break into import and non-import because the index
        // tables do imports first
        val (importExps, nonImportExps) = exps.partition {
            when(it.vals.firstOrNull()?.symbolStr()) {
                "import" -> true
                "func", "global", "table", "memory" -> {
                    (it.vals.getOrNull(if (it.maybeName(1) == null) 1 else 2) as? SExpr.Multi)?.
                        vals?.firstOrNull()?.symbolStr() == "import"
                }
                else -> false
            }
        }

        var funcCount = 0
        var globalCount = 0
        var tableCount = 0
        var memoryCount = 0
        var namesToIndices = emptyMap<String, Int>()
        fun maybeAddName(name: String?, index: Int, type: String) {
            name?.let { namesToIndices += "$type:$it" to index }
        }

        // All imports first
        importExps.forEach {
            val kindExp = when (it.vals.firstOrNull()?.symbolStr()) {
                "import" -> it.vals[3] as SExpr.Multi
                else -> it
            }
            val kindName = kindExp.maybeName(1)
            when (kindExp.vals.firstOrNull()?.symbolStr()) {
                "func" -> maybeAddName(kindName, funcCount++, "func")
                "global" -> maybeAddName(kindName, globalCount++, "global")
                "table" -> maybeAddName(kindName, tableCount++, "table")
                "memory" -> maybeAddName(kindName, memoryCount++, "memory")
                else -> throw Exception("Unknown import exp: $it")
            }
        }
        // Now the rest sans type
        nonImportExps.forEach {
            val kindName = it.maybeName(1)
            when (it.vals.firstOrNull()?.symbolStr()) {
                "func" -> maybeAddName(kindName, funcCount++, "func")
                "global" -> maybeAddName(kindName, globalCount++, "global")
                "table" -> maybeAddName(kindName, tableCount++, "table")
                "memory" -> maybeAddName(kindName, memoryCount++, "memory")
                else -> {}
            }
        }
        return namesToIndices
    }

    fun toOpMaybe(exp: SExpr.Multi, offset: Int, ctx: ExprContext): Pair<Node.Instr, Int>? {
        if (offset >= exp.vals.size) return null
        val head = exp.vals[offset].symbol()!!
        fun varIsStringRef(off: Int = offset + 1) = exp.vals[off].symbolStr()?.firstOrNull() == '$'
        fun oneVar(type: String, off: Int = offset + 1) = toVar(exp.vals[off].symbol()!!, ctx.nameMap, type)
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
                if (varIsStringRef()) Pair(op.create(ctx.blockDepth - oneVar("block")), 2)
                else Pair(op.create(oneVar("block")), 2)
            }
            is InstrOp.ControlFlowOp.TableArg -> {
                val vars = exp.vals.drop(offset + 1).takeUntilNullLazy {
                    toVarMaybe(it, ctx.nameMap, "block")?.let { tableVar ->
                        // Named depth is subtracted from current depth
                        if (it.symbolStr()?.firstOrNull() == '$') ctx.blockDepth - tableVar else tableVar
                    }
                }
                Pair(op.create(vars.dropLast(1), vars.last()), offset + 1 + vars.size)
            }
            is InstrOp.CallOp.IndexArg -> Pair(op.create(oneVar("func")), 2)
            is InstrOp.CallOp.IndexReservedArg -> Pair(op.create(oneVar("type"), false), 2)
            is InstrOp.ParamOp.NoArg -> Pair(op.create, 1)
            is InstrOp.VarOp.IndexArg -> Pair(op.create(
                oneVar(if (head.contents.endsWith("global")) "global" else "local")), 2)
            is InstrOp.MemOp.AlignOffsetArg -> {
                var count = 1
                var instrOffset = 0L
                var instrAlign = 0
                if (exp.vals.size > offset + count) exp.vals[offset + count].symbolStr().also {
                    if (it != null && it.startsWith("offset=")) {
                        instrOffset = it.substring(7).toUnsignedIntConst()
                        count++
                    }
                }
                if (exp.vals.size > offset + count) exp.vals[offset + count].symbolStr().also {
                    if (it != null && it.startsWith("align=")) {
                        instrAlign = it.substring(6).toInt()
                        require(instrAlign > 0 && instrAlign and (instrAlign - 1) == 0) {
                            "Alignment expected to be positive power of 2, but got $instrAlign"
                        }
                        if (instrAlign > op.argBits / 8) throw IoErr.InvalidAlign(instrAlign, op.argBits)
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
        var max: Long? = null
        if (offset + 1 < exp.vals.size && exp.vals[offset + 1] is SExpr.Symbol) {
            max = exp.vals[offset + 1].symbolStr()?.toLongOrNull()
        }
        val init = exp.vals[offset].symbolStr()!!.toLong()
        if (init > Mem.PAGE_SIZE) throw IoErr.MemorySizeOverflow(init)
        if (max != null && max > Mem.PAGE_SIZE) throw IoErr.MemorySizeOverflow(max)
        if (max != null && init > max) throw IoErr.MemoryInitMaxMismatch(init.toInt(), max.toInt())
        return Node.ResizableLimits(init.toInt(), max?.toInt())
    }

    fun toResult(exp: SExpr.Multi): Node.Type.Value {
        exp.requireFirstSymbol("result")
        if (exp.vals.size > 2) throw IoErr.InvalidResultArity()
        return toType(exp.vals[1].symbol()!!)
    }

    fun toScript(exp: SExpr.Multi): Script {
        return Script(exp.vals.map { toCmd(it as SExpr.Multi) })
    }

    fun toStart(exp: SExpr.Multi, nameMap: NameMap): Int {
        exp.requireFirstSymbol("start")
        return toVar(exp.vals[1].symbol()!!, nameMap, "func")
    }

    fun toTable(
        exp: SExpr.Multi,
        nameMap: NameMap
    ): Triple<String?, Either<Node.Type.Table, Node.Elem>, ImportOrExport?> {
        exp.requireFirstSymbol("table")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currIndex)
        if (maybeImpExp != null) currIndex++
        // If elem type is there, we load the elems instead
        val elemType = toElemTypeMaybe(exp, currIndex)
        val tableOrElems =
            if (elemType != null) {
                require(maybeImpExp?.importModule == null)
                val elem = exp.vals[currIndex + 1] as SExpr.Multi
                elem.requireFirstSymbol("elem")
                Either.Right(Node.Elem(
                    0,
                    listOf(Node.Instr.I32Const(0)),
                    elem.vals.drop(1).map { toVar(it.symbol()!!, nameMap, "func") }
                ))
            } else Either.Left(toTableSig(exp, currIndex))
        return Triple(name, tableOrElems, maybeImpExp)
    }

    fun toTableSig(exp: SExpr.Multi, offset: Int): Node.Type.Table {
        val limits = toResizeableLimits(exp, offset)
        return Node.Type.Table(toElemType(exp, offset + if (limits.maximum == null) 1 else 2), limits)
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
        return Pair(name, toFuncSig(funcSigExp, 1, nameMap, emptyList()).third)
    }

    fun toVar(exp: SExpr.Symbol, nameMap: NameMap, nameType: String): Int {
        return toVarMaybe(exp, nameMap, nameType) ?: throw Exception("No var for on exp $exp")
    }

    fun toVarMaybe(exp: SExpr, nameMap: NameMap, nameType: String): Int? {
        return exp.symbolStr()?.let { it ->
            if (it.startsWith("$"))
                nameMap["$nameType:$it"] ?:
                    throw Exception("Unable to find index for name $it of type $nameType in $nameMap")
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
        (if (this.contains("0x")) Long.valueOf(this.replace("0x", ""), 16)
        else Long.valueOf(this)).unsignedToSignedInt().toUnsignedLong()

    private fun String.toFloatConst() =
        if (this == "infinity" || this == "+infinity") Float.POSITIVE_INFINITY
        else if (this == "-infinity") Float.NEGATIVE_INFINITY
        else if (this == "nan" || this == "+nan") Float.fromIntBits(0x7fc00000)
        else if (this == "-nan") Float.fromIntBits(0xffc00000.toInt())
        else if (this.startsWith("nan:") || this.startsWith("+nan:")) Float.fromIntBits(
            0x7f800000 + this.substring(this.indexOf(':') + 1).toIntConst()
        ) else if (this.startsWith("-nan:")) Float.fromIntBits(
            0xff800000.toInt() + this.substring(this.indexOf(':') + 1).toIntConst()
        ) else if (this.startsWith("0x") && !this.contains('P', true)) this.toLongConst().toFloat()
        else this.toFloat()
    private fun String.toDoubleConst() =
        if (this == "infinity" || this == "+infinity") Double.POSITIVE_INFINITY
        else if (this == "-infinity") Double.NEGATIVE_INFINITY
        else if (this == "nan" || this == "+nan") Double.fromLongBits(0x7ff8000000000000)
        else if (this == "-nan") Double.fromLongBits(-2251799813685248) // i.e. 0xfff8000000000000
        else if (this.startsWith("nan:") || this.startsWith("+nan:")) Double.fromLongBits(
            0x7ff0000000000000 + this.substring(this.indexOf(':') + 1).toLongConst()
        ) else if (this.startsWith("-nan:")) Double.fromLongBits(
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
