package asmble.io

import asmble.ast.Node
import asmble.ast.Node.InstrOp
import asmble.ast.SExpr
import asmble.ast.Script
import asmble.compile.jvm.Mem
import asmble.util.*
import java.io.ByteArrayInputStream
import java.math.BigInteger

open class SExprToAst(
    val includeNames: Boolean = true
) {
    data class ExprContext(
        val nameMap: NameMap,
        val blockDepth: Int = 0,
        val types: List<Node.Type.Func> = emptyList(),
        val callIndirectNeverBeforeSeenFuncTypes: MutableList<Node.Type.Func> = mutableListOf()
    ) {
        companion object {
            val empty = ExprContext(NameMap(emptyMap(), null, null))
        }
    }

    data class FuncResult(
        val name: String?,
        val func: Node.Func,
        val importOrExport: ImportOrExport?,
        // These come from call_indirect insns
        val additionalFuncTypesToAdd: List<Node.Type.Func>,
        val nameMap: NameMap
    )

    fun toAction(exp: SExpr.Multi): Script.Cmd.Action {
        var index = 1
        val name = exp.maybeName(index)
        if (name != null) index++
        val str = exp.vals[index].symbolStr()!!
        index++
        return when(exp.vals.first().symbolStr()) {
            "invoke" ->
                Script.Cmd.Action.Invoke(name, str, exp.vals.drop(index).map {
                    toExprMaybe(it as SExpr.Multi, ExprContext.empty)
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
                    exp.vals.drop(2).map { toExprMaybe(it as SExpr.Multi, ExprContext.empty) })
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
        val multi = exp.vals.getOrNull(offset) as? SExpr.Multi
        if (multi == null || multi.vals.firstOrNull()?.symbolStr() != "result") return emptyList()
        val types = multi.vals.drop(1).map { it.symbol()?.let { toTypeMaybe(it) } ?: error("Unknown type on $it") }
        // We can only handle one type for now
        require(types.size  <= 1)
        return types
    }

    fun toCmdMaybe(exp: SExpr.Multi): Script.Cmd? {
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
                null
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
        val field = exp.vals[1].symbolUtf8Str()!!
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
                toExprMaybe(it as SExpr.Multi, ctx).also { if (it.isEmpty()) throw IoErr.UnknownOperator() }
            } + maybeOpAndOffset.first
        }
        // Other blocks take up the rest (ignore names)
        val blockName = exp.vals.first().symbolStr()
        var opOffset = 1
        var innerCtx = ctx.copy(blockDepth = ctx.blockDepth + 1)
        exp.maybeName(opOffset)?.also {
            opOffset++
            innerCtx = innerCtx.copy(nameMap = innerCtx.nameMap.add("block", it, innerCtx.blockDepth))
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
    ): FuncResult {
        exp.requireFirstSymbol("func")
        var currentIndex = 1
        val name = exp.maybeName(currentIndex)
        if (name != null) currentIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currentIndex)
        maybeImpExp?.also { currentIndex += it.itemCount }
        var (nameMap, exprsUsed, sig) = toFuncSig(exp, currentIndex, origNameMap, types)
        currentIndex += exprsUsed
        val locals = exp.repeated("local", currentIndex, { toLocals(it) }).mapIndexed { index, (nameMaybe, vals) ->
            nameMaybe?.also { require(vals.size == 1); nameMap = nameMap.add("local", it, index + sig.params.size) }
            vals
        }
        currentIndex += locals.size
        // We create a context for insn parsing (it even has sa mutable var)
        val ctx = ExprContext(
            nameMap = nameMap,
            // We add ourselves to the context type list if we're not there to keep the indices right
            types = if (types.contains(sig)) types else types + sig
        )
        val (instrs, _) = toInstrs(exp, currentIndex, ctx)
        // Imports can't have locals or instructions
        if (maybeImpExp is ImportOrExport.Import) require(locals.isEmpty() && instrs.isEmpty())
        return FuncResult(
            name = name,
            func = Node.Func(sig, locals.flatten(), instrs),
            importOrExport = maybeImpExp,
            additionalFuncTypesToAdd = ctx.callIndirectNeverBeforeSeenFuncTypes,
            nameMap = nameMap
        )
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
            nameMaybe?.also { require(vals.size == 1); nameMap = nameMap.add("local", it, index) }
            vals
        }
        val resultExps = exp.repeated("result", offset + params.size, this::toResult)
        val results = resultExps.flatten()
        if (results.size > 1) throw IoErr.InvalidResultArity()
        val usedExps = params.size + resultExps.size + if (typeRef == null) 0 else 1
        // Make sure there aren't parameters following the result
        if (resultExps.isNotEmpty() && (exp.vals.getOrNull(offset + params.size + resultExps.size) as? SExpr.Multi)?.
                vals?.firstOrNull()?.symbolStr() == "param") {
            throw IoErr.ResultBeforeParameter()
        }
        // Check against type ref
        if (typeRef != null) {
            // No params or results means just use it
            if (params.isEmpty() && results.isEmpty()) return Triple(nameMap, usedExps, typeRef)
            // Otherwise, just make sure it matches
            if (typeRef.params != params.flatten() || typeRef.ret != results.firstOrNull()) {
                throw IoErr.FuncTypeRefMismatch()
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
        maybeImpExp?.also { currIndex += it.itemCount }
        val sig = toGlobalSig(exp.vals[currIndex])
        currIndex++
        val (instrs, _) = toInstrs(exp, currIndex, ExprContext(nameMap))
        // Imports can't have instructions
        if (maybeImpExp is ImportOrExport.Import) require(instrs.isEmpty())
        return Triple(name, Node.Global(sig, instrs), maybeImpExp)
    }

    fun toGlobalSig(exp: SExpr): Node.Type.Global = when(exp) {
        is SExpr.Symbol -> Node.Type.Global(toType(exp), false)
        is SExpr.Multi -> {
            exp.vals.first().requireSymbol("mut")
            Node.Type.Global(toType(exp.vals[1] as SExpr.Symbol), true)
        }
    }

    fun toImport(
        exp: SExpr.Multi,
        origNameMap: NameMap,
        types: List<Node.Type.Func>
    ): Triple<String, String, Node.Type> {
        exp.requireFirstSymbol("import")
        val module = exp.vals[1].symbolUtf8Str()!!
        val field = exp.vals[2].symbolUtf8Str()!!
        val kind = exp.vals[3] as SExpr.Multi
        val kindName = kind.vals.firstOrNull()?.symbolStr()
        val kindSubOffset = if (kind.maybeName(1) == null) 1 else 2
        return Triple(module, field, when(kindName) {
            "func" -> toFuncSig(kind, kindSubOffset, origNameMap, types).third
            "global" -> toGlobalSig(kind.vals[kindSubOffset])
            "table" -> toTableSig(kind, kindSubOffset)
            "memory" -> toMemorySig(kind, kindSubOffset)
            else -> throw Exception("Unrecognized type: $kindName")
        })
    }

    fun toImportOrExportMaybe(exp: SExpr.Multi, offset: Int): ImportOrExport? {
        if (offset >= exp.vals.size) return null
        var currOffset = offset
        // Get all export fields first
        var exportFields = emptyList<String>()
        while (true) {
            val multi = exp.vals.getOrNull(currOffset) as? SExpr.Multi
            when (multi?.vals?.firstOrNull()?.symbolStr()) {
                "import" -> return ImportOrExport.Import(
                    name = multi.vals.getOrNull(2)?.symbolUtf8Str() ?: error("No import name"),
                    module = multi.vals.getOrNull(1)?.symbolUtf8Str() ?: error("No import module"),
                    exportFields = exportFields
                )
                "export" -> multi.vals.getOrNull(1)?.symbolUtf8Str().also {
                    exportFields += it ?: error("No export field")
                }
                else -> return if (exportFields.isEmpty()) null else ImportOrExport.Export(exportFields)
            }
            currOffset++
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
        if (mustCompleteExp && offset + runningOffset != exp.vals.size) {
            throw IoErr.UnrecognizedInstruction(exp.vals[offset + runningOffset].toString())
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
            innerCtx = innerCtx.copy(nameMap = innerCtx.nameMap.add("block", maybeName, innerCtx.blockDepth))
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
                            innerCtx = innerCtx.copy(nameMap = innerCtx.nameMap.add("block", it, ctx.blockDepth))
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
            if (it != maybeName) throw IoErr.MismatchLabelEnd(maybeName, it)
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
        maybeImpExp?.also { currIndex += it.itemCount }
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
        // As a special case, if this isn't a "module", wrap it and try again
        if (exp.vals.firstOrNull()?.symbolStr() != "module") {
            return toModule(SExpr.Multi(listOf(SExpr.Symbol("module")) + exp.vals))
        }
        exp.requireFirstSymbol("module")
        val name = exp.maybeName(1)

        // Special cases for "quote" and "binary" modules.
        val quoteOrBinary = exp.vals.elementAtOrNull(if (name == null) 1 else 2)?.
            symbolStr()?.takeIf { it == "quote" || it == "binary" }
        if (quoteOrBinary != null) {
            val bytes = exp.vals.drop(if (name == null) 2 else 3).fold(byteArrayOf()) { bytes, expr ->
                bytes + (
                    expr.symbol()?.takeIf { it.quoted }?.rawContentCharsToBytes() ?: error("Expected quoted string")
                )
            }
            // For binary, just load from bytes
            if (quoteOrBinary == "binary") return name to toModuleFromBytes(bytes)
            // Otherwise, take the quoted strings and parse em
            return toModuleFromQuotedString(bytes.toString(Charsets.US_ASCII))
        }

        var mod = Node.Module()
        val exps = exp.vals.mapNotNull { it as? SExpr.Multi }

        // Eagerly build the names (for forward decls)
        var (nameMap, eagerTypes) = toModuleForwardNameMapAndTypes(exps)
        mod = mod.copy(types = eagerTypes)

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
        fun handleImport(module: String, field: String, kind: Node.Type, exportFields: List<String>) {
            // We make sure that an import doesn't happen after a non-import
            if (mod.funcs.isNotEmpty()) throw IoErr.ImportAfterNonImport("function")
            if (mod.globals.isNotEmpty()) throw IoErr.ImportAfterNonImport("global")
            if (mod.tables.isNotEmpty()) throw IoErr.ImportAfterNonImport("table")
            if (mod.memories.isNotEmpty()) throw IoErr.ImportAfterNonImport("memory")
            val (importKind, indexAndExtKind) = when(kind) {
                is Node.Type.Func -> mod.addTypeIfNotPresent(kind).let { (m, idx) ->
                    mod = m
                    Node.Import.Kind.Func(idx) to (funcCount++ to Node.ExternalKind.FUNCTION)
                }
                is Node.Type.Global ->
                    Node.Import.Kind.Global(kind) to (globalCount++ to Node.ExternalKind.GLOBAL)
                is Node.Type.Table ->
                    Node.Import.Kind.Table(kind) to (tableCount++ to Node.ExternalKind.TABLE)
                is Node.Type.Memory ->
                    Node.Import.Kind.Memory(kind) to (memoryCount++ to Node.ExternalKind.MEMORY)
                else -> throw Exception("Unrecognized import kind: $kind")
            }

            mod = mod.copy(
                imports = mod.imports + Node.Import(module, field, importKind),
                exports = mod.exports + exportFields.map {
                    Node.Export(it, indexAndExtKind.second, indexAndExtKind.first)
                }
            )
        }

        fun addExport(exp: ImportOrExport.Export, extKind: Node.ExternalKind, index: Int) {
            mod = mod.copy(exports = mod.exports + exp.fields.map { Node.Export(it, extKind, index) })
        }

        // Now just handle all expressions in order
        exps.forEach { exp ->
            when(exp.vals.firstOrNull()?.symbolStr()) {
                "import" -> toImport(exp, nameMap, mod.types).let { (module, field, type) ->
                    handleImport(module, field, type, emptyList())
                }
                // We do not handle types here anymore. They are handled eagerly as part of the forward pass.
                "type" -> { }
                "export" -> mod = mod.copy(exports = mod.exports + toExport(exp, nameMap))
                "elem" -> mod = mod.copy(elems = mod.elems + toElem(exp, nameMap))
                "data" -> mod = mod.copy(data = mod.data + toData(exp, nameMap))
                "start" -> mod = mod.copy(startFuncIndex = toStart(exp, nameMap))
                "func" -> toFunc(exp, nameMap, mod.types).also { (_, fn, impExp, additionalFuncTypes, localNameMap) ->
                    if (impExp is ImportOrExport.Import) {
                        handleImport(impExp.module, impExp.name, fn.type, impExp.exportFields)
                    } else {
                        if (impExp is ImportOrExport.Export) addExport(impExp, Node.ExternalKind.FUNCTION, funcCount)
                        if (includeNames) nameMap = nameMap.copy(
                            localNames = nameMap.localNames!! + (funcCount to localNameMap.getAllNamesByIndex("local"))
                        )
                        funcCount++
                        mod = mod.copy(funcs = mod.funcs + fn).addTypeIfNotPresent(fn.type).first
                        mod = additionalFuncTypes.fold(mod) { mod, typ -> mod.addTypeIfNotPresent(typ).first }
                    }
                }
                "global" -> toGlobal(exp, nameMap).let { (_, glb, impExp) ->
                    if (impExp is ImportOrExport.Import) {
                        handleImport(impExp.module, impExp.name, glb.type, impExp.exportFields)
                    } else {
                        if (impExp is ImportOrExport.Export) addExport(impExp, Node.ExternalKind.GLOBAL, globalCount)
                        globalCount++
                        mod = mod.copy(globals = mod.globals + glb)
                    }
                }
                "table" -> toTable(exp, nameMap).let { (_, tbl, impExp) ->
                    if (impExp is ImportOrExport.Import) {
                        if (tbl !is Either.Left) error("Elem segment on import table")
                        handleImport(impExp.module, impExp.name, tbl.v, impExp.exportFields)
                    } else {
                        if (impExp is ImportOrExport.Export) addExport(impExp, Node.ExternalKind.TABLE, tableCount)
                        tableCount++
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
                    if (impExp is ImportOrExport.Import) {
                        if (mem !is Either.Left) error("Data segment on import mem")
                        handleImport(impExp.module, impExp.name, mem.v, impExp.exportFields)
                    } else {
                        if (impExp is ImportOrExport.Export) addExport(impExp, Node.ExternalKind.MEMORY, memoryCount)
                        memoryCount++
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

        // Set the name map pieces if we're including them
        if (includeNames) mod = mod.copy(
            names = Node.NameSection(
                moduleName = name,
                funcNames = nameMap.funcNames!!,
                localNames = nameMap.localNames!!
            )
        )

        return name to mod
    }

    fun toModuleFromBytes(bytes: ByteArray) = BinaryToAst.toModule(ByteReader.InputStream(ByteArrayInputStream(bytes)))

    fun toModuleFromQuotedString(str: String) = StrToSExpr.parse(str).let {
        when (it) {
            is StrToSExpr.ParseResult.Error -> error("Failed parsing quoted module: ${it.msg}")
            is StrToSExpr.ParseResult.Success -> {
                // If the result is not a single module sexpr, wrap it in one
                val sexpr = it.vals.singleOrNull()?.let { it as? SExpr.Multi }?.takeIf {
                    it.vals.firstOrNull()?.symbolStr() == "module"
                } ?: SExpr.Multi(listOf(SExpr.Symbol("module")) + it.vals)
                toModule(sexpr)
            }
        }
    }

    fun toModuleForwardNameMapAndTypes(exps: List<SExpr.Multi>): Pair<NameMap, List<Node.Type.Func>> {
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
        var nameMap = NameMap(
            names = emptyMap(),
            funcNames = if (includeNames) emptyMap() else null,
            localNames = if (includeNames) emptyMap() else null
        )
        var types = emptyList<Node.Type.Func>()
        fun maybeAddName(name: String?, index: Int, type: String) {
            name?.also { nameMap = nameMap.add(type, it, index) }
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
                // We go ahead and do the full type def build here eagerly
                "type" -> maybeAddName(kindName, types.size, "type").also { _ ->
                    toTypeDef(it, nameMap).also { (_, type) -> types += type }
                }
                else -> {}
            }
        }
        return nameMap to types
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
            is InstrOp.CallOp.IndexReservedArg -> {
                // First lookup the func sig
                val (updatedNameMap, expsUsed, funcType) = toFuncSig(exp, offset + 1, ctx.nameMap, ctx.types)
                // Make sure there are no changes to the name map
                if (ctx.nameMap.size != updatedNameMap.size)
                    throw IoErr.IndirectCallSetParamNames()
                // Obtain the func index from the types table, the indirects table, or just add it
                var funcTypeIndex = ctx.types.indexOf(funcType)
                // If it's not in the type list, check the call indirect list
                if (funcTypeIndex == -1) {
                    funcTypeIndex = ctx.callIndirectNeverBeforeSeenFuncTypes.indexOf(funcType)
                    // If it's not there either, add it as a fresh
                    if (funcTypeIndex == -1) {
                        funcTypeIndex = ctx.callIndirectNeverBeforeSeenFuncTypes.size
                        ctx.callIndirectNeverBeforeSeenFuncTypes += funcType
                    }
                    // And of course increase it by the overall type list size since they'll be added to that later
                    funcTypeIndex += ctx.types.size
                }
                Pair(op.create(funcTypeIndex, false), expsUsed + 1)

            }
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
                        if (instrAlign <= 0 || instrAlign and (instrAlign - 1) != 0) {
                            throw IoErr.InvalidAlignPower(instrAlign)
                        }
                        if (instrAlign > op.argBits / 8) throw IoErr.InvalidAlignTooLarge(instrAlign, op.argBits)
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

    fun toResult(exp: SExpr.Multi): List<Node.Type.Value> {
        exp.requireFirstSymbol("result")
        return exp.vals.drop(1).map { toType(it.symbol() ?: error("Invalid result type")) }
    }

    fun toScript(exp: SExpr.Multi): Script {
        val cmds = exp.vals.map { toCmdMaybe(it as SExpr.Multi) }
        // If the commands are non-empty but they are all null, it's an inline module
        if (cmds.isNotEmpty() && cmds.all { it == null }) {
            return toModule(exp).let { Script(listOf(Script.Cmd.Module(it.second, it.first))) }
        }
        return Script(cmds.filterNotNull())
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
        maybeImpExp?.also { currIndex += it.itemCount }
        // If elem type is there, we load the elems instead
        val elemType = toElemTypeMaybe(exp, currIndex)
        val tableOrElems =
            if (elemType != null) {
                require(maybeImpExp !is ImportOrExport.Import)
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
        return toTypeMaybe(exp) ?: throw IoErr.InvalidType(exp.contents)
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
        return toVarMaybe(exp, nameMap, nameType) ?: throw IoErr.InvalidVar(exp.toString())
    }

    fun toVarMaybe(exp: SExpr, nameMap: NameMap, nameType: String): Int? {
        return exp.symbolStr()?.let { it ->
            if (it.startsWith("$"))
                nameMap.get(nameType, it.drop(1)) ?:
                    throw Exception("Unable to find index for name $it of type $nameType in $nameMap")
            else if (it.startsWith("0x")) it.substring(2).toIntOrNull(16)
            else it.toIntOrNull()
        }
    }

    private fun String.sansUnderscores(): String {
        // The underscores can only be between digits (which can be hex)
        fun isDigit(c: Char) = c.isDigit() || (startsWith("0x", true) && (c in 'a'..'f' || c in 'A'..'F'))
        var ret = this
        var underscoreIndex = 0
        while (true){
            underscoreIndex = ret.indexOf('_', underscoreIndex)
            if (underscoreIndex == -1) return ret
            // Can't be at beginning or end
            if (underscoreIndex == 0 || underscoreIndex == ret.length - 1 ||
                    !isDigit(ret[underscoreIndex - 1]) || !isDigit(ret[underscoreIndex + 1])) {
                throw IoErr.ConstantUnknownOperator(this)
            }
            ret = ret.removeRange(underscoreIndex, underscoreIndex + 1)
        }
    }
    private fun String.toBigIntegerConst() =
        if (contains("0x")) BigInteger(replace("0x", ""), 16)
        else BigInteger(this)
    private fun String.toIntConst() = sansUnderscores().run {
        toBigIntegerConst().
            also { if (it > MAX_UINT32) throw IoErr.ConstantOutOfRange(it) }.
            also { if (it < MIN_INT32) throw IoErr.ConstantOutOfRange(it) }.
            toInt()
    }
    private fun String.toLongConst() = sansUnderscores().run {
        toBigIntegerConst().
            also { if (it > MAX_UINT64) throw IoErr.ConstantOutOfRange(it) }.
            also { if (it < MIN_INT64) throw IoErr.ConstantOutOfRange(it) }.
            toLong()
    }
    private fun String.toUnsignedIntConst() = sansUnderscores().run {
        (if (contains("0x")) Long.valueOf(replace("0x", ""), 16)
        else Long.valueOf(this)).unsignedToSignedInt().toUnsignedLong()
    }

    private fun String.toFloatConst() = sansUnderscores().run {
        if (this == "infinity" || this == "+infinity" || this == "inf" || this == "+inf") Float.POSITIVE_INFINITY
        else if (this == "-infinity" || this == "-inf") Float.NEGATIVE_INFINITY
        else if (this == "nan" || this == "+nan") Float.fromIntBits(0x7fc00000)
        else if (this == "-nan") Float.fromIntBits(0xffc00000.toInt())
        else if (this.startsWith("nan:") || this.startsWith("+nan:")) Float.fromIntBits(
            0x7f800000 + this.substring(this.indexOf(':') + 1).toIntConst()
        ) else if (this.startsWith("-nan:")) Float.fromIntBits(
            0xff800000.toInt() + this.substring(this.indexOf(':') + 1).toIntConst()
        ) else {
            // If there is no "p" on a hex, we have to add it
            var str = this
            if (str.startsWith("0x", true) && !str.contains('P', true)) str += "p0"
            str.toFloat().also { if (it.isInfinite()) throw IoErr.ConstantOutOfRange(it) }
        }
    }
    private fun String.toDoubleConst() = sansUnderscores().run {
        if (this == "infinity" || this == "+infinity" || this == "inf" || this == "+inf") Double.POSITIVE_INFINITY
        else if (this == "-infinity" || this == "-inf") Double.NEGATIVE_INFINITY
        else if (this == "nan" || this == "+nan") Double.fromLongBits(0x7ff8000000000000)
        else if (this == "-nan") Double.fromLongBits(-2251799813685248) // i.e. 0xfff8000000000000
        else if (this.startsWith("nan:") || this.startsWith("+nan:")) Double.fromLongBits(
            0x7ff0000000000000 + this.substring(this.indexOf(':') + 1).toLongConst()
        ) else if (this.startsWith("-nan:")) Double.fromLongBits(
            -4503599627370496 + this.substring(this.indexOf(':') + 1).toLongConst() // i.e. 0xfff0000000000000
        )  else {
            // If there is no "p" on a hex, we have to add it
            var str = this
            if (str.startsWith("0x", true) && !str.contains('P', true)) str += "p0"
            str.toDouble().also { if (it.isInfinite()) throw IoErr.ConstantOutOfRange(it) }
        }
    }

    private fun SExpr.requireSymbol(contents: String, quotedCheck: Boolean? = null) {
        if (this is SExpr.Symbol && this.contents == contents &&
                (quotedCheck == null || this.quoted == quotedCheck)) {
            return
        }
        throw Exception("Expected symbol of $contents, got $this")
    }


    private fun SExpr.symbol() = this as? SExpr.Symbol
    private fun SExpr.symbolStr() = this.symbol()?.contents
    private fun SExpr.symbolUtf8Str() = this.symbol()?.let {
        if (it.hasNonUtf8ByteSeqs) throw IoErr.InvalidUtf8Encoding()
        it.contents
    }

    private fun SExpr.Multi.maybeName(index: Int): String? {
        if (this.vals.size > index && this.vals[index] is SExpr.Symbol) {
            val sym = this.vals[index] as SExpr.Symbol
            if (!sym.quoted && sym.contents[0] == '$') return sym.contents.drop(1)
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

    data class NameMap(
        // Key prefixed with type then colon before actual name
        val names: Map<String, Int>,
        // Null if not including names
        val funcNames: Map<Int, String>?,
        val localNames: Map<Int, Map<Int, String>>?
    ) {
        val size get() = names.size

        fun add(type: String, name: String, index: Int) = copy(
            names = names + ("$type:$name" to index),
            funcNames = funcNames?.let { if (type == "func") it + (index to name) else it }
        )

        fun get(type: String, name: String) = names["$type:$name"]

        fun getAllNamesByIndex(type: String) = names.mapNotNull { (k, v) ->
            k.takeIf { k.startsWith("$type:") }?.let { v to k.substring(type.length + 1) }
        }.toMap()
    }

    companion object : SExprToAst()
}
