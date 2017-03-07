package asmble.io

import asmble.ast.Node
import asmble.ast.Node.InstrOp
import asmble.ast.SExpr
import asmble.ast.Script
import asmble.util.takeUntilNull
import asmble.util.takeUntilNullLazy

open class SExprToAst {
    data class ImportOrExport(val field: String, val importModule: String?)

    fun toBlockSigMaybe(exp: SExpr.Multi, offset: Int): List<Node.Type.Value> {
        val types = exp.vals.drop(offset).takeUntilNullLazy { if (it is SExpr.Symbol) toTypeMaybe(it) else null }
        // We can only handle one type for now
        require(types.size  <= 1)
        return types
    }

    fun toCmd(exp: SExpr.Multi): Script.Cmd {
        val expName = exp.vals.first().symbolStr()
        when(expName) {
            "module" -> return Script.Cmd.Module(toModule(exp).second)
            "register" -> return toRegister(exp)
            "invoke", "get" -> return toAction(exp)
            "assert_return", "assert_return_nan", "assert_trap", "assert_malformed",
                "assert_invalid", "assert_soft_invalid", "assert_unlinkable" -> return toAssertion(exp)
            "script", "input", "output" -> return toMeta(exp)
            else -> throw Exception("Unrecognized cmd expr '$expName'")
        }
    }

    fun toData(exp: SExpr.Multi): Pair<String?, Node.Data> {
        exp.requireFirstSymbol("data")
        var currIndex = 1
        val name = exp.maybeName(1)
        if (name == null) require(toVarMaybe(exp.vals[currIndex]) == null, { "Int name not supported" })
        else currIndex++
        val offsetMulti = exp.vals[currIndex] as SExpr.Multi
        val instrs =
                if (offsetMulti.vals.firstOrNull()?.symbolStr() == "offset") toInstrs(offsetMulti, 1).first
                else toExprMaybe(offsetMulti)
        currIndex++
        val strs = exp.vals.drop(currIndex + 1).fold("") { str, sym -> str + (sym as SExpr.Symbol).contents }
        return Pair(name, Node.Data(0, instrs, strs.toByteArray(Charsets.UTF_8)))
    }

    fun toElem(exp: SExpr.Multi): Pair<String?, Node.Elem> {
        exp.requireFirstSymbol("elem")
        var currIndex = 1
        val name = exp.maybeName(1)
        if (name == null) require(toVarMaybe(exp.vals[currIndex]) == null, { "Int name not supported" })
        else currIndex++
        val offsetMulti = exp.vals[currIndex] as SExpr.Multi
        val instrs =
            if (offsetMulti.vals.firstOrNull()?.symbolStr() == "offset") toInstrs(offsetMulti, 1).first
            else toExprMaybe(offsetMulti)
        currIndex++
        val vars = exp.vals.drop(currIndex + 1).map { toVar(it as SExpr.Symbol) }
        return Pair(name, Node.Elem(0, instrs, vars))
    }

    fun toElemType(exp: SExpr.Multi, offset: Int): Node.ElemType {
        exp.requireFirstSymbol("anyfunc")
        return Node.ElemType.ANYFUNC
    }

    fun toElemTypeMaybe(exp: SExpr.Multi, offset: Int): Node.ElemType? {
        if (exp.vals[offset].symbolStr() == "anyfunc") return Node.ElemType.ANYFUNC
        return null
    }

    fun toExport(exp: SExpr.Multi): Node.Export {
        exp.requireFirstSymbol("export")
        val field = exp.vals[1].symbolStr()!!
        val kind = exp.vals[2] as SExpr.Multi
        val kindIndex = toVar(kind.vals[1].symbol()!!)
        val extKind = when(kind.vals[0].symbolStr()) {
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
        val blockName = exp.vals.first().symbolStr()
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
                val exprs = toExprMaybe(exprMulti)
                // Conditional?
                if (exprs.isNotEmpty()) {
                    // First expression means it's the conditional, so push on stack
                    ret += exprs
                    opOffset++
                    exprMulti = exp.vals[opOffset] as SExpr.Multi
                }
                ret += Node.Instr.If(sigs.firstOrNull())
                // Is it a "then"?
                if (exprMulti.vals.firstOrNull()?.symbolStr() == "then") ret += toInstrs(exprMulti, 1).first
                else ret += toExprMaybe(exprMulti)
                // Now check for "else"
                opOffset++
                if (opOffset < exp.vals.size) {
                    ret += Node.Instr.Else
                    exprMulti = exp.vals[opOffset] as SExpr.Multi
                    if (exprMulti.vals.firstOrNull()?.symbolStr() == "else") ret += toInstrs(exprMulti, 1).first
                    else ret += toExprMaybe(exprMulti)
                }
                return ret + Node.Instr.End
            }
            else -> return emptyList()
        }
    }

    fun toFunc(exp: SExpr.Multi): Triple<String?, Node.Func, ImportOrExport?> {
        exp.requireFirstSymbol("func")
        var currentIndex = 1
        val name = exp.maybeName(currentIndex)
        if (name != null) currentIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currentIndex)
        if (maybeImpExp != null) currentIndex++
        val sig = toFuncSig(exp.vals[currentIndex] as SExpr.Multi)
        currentIndex++
        val locals = exp.repeated("local", currentIndex, { toLocals(it).second }).flatten()
        currentIndex += locals.size
        val (instrs, _) = toInstrs(exp, currentIndex)
        // Imports can't have locals or instructions
        require((maybeImpExp?.importModule != null) == (locals.isEmpty() && instrs.isEmpty()))
        return Triple(name, Node.Func(sig, locals, instrs), maybeImpExp)
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
        exp.requireFirstSymbol("import")
        val module = exp.vals[1].symbolStr()!!
        val field = exp.vals[2].symbolStr()!!
        val kind = exp.vals[3] as SExpr.Multi
        val kindName = kind.vals.firstOrNull()?.symbolStr()
        val kindSubOffset = if (kind.maybeName(1) == null) 1 else 2
        return Triple(module, field, when(kindName) {
            "func" -> toFuncSig(kind.vals[kindSubOffset] as SExpr.Multi)
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
            "import" -> ImportOrExport(multi.vals[1].symbolStr()!!, multi.vals[2].symbolStr()!!)
            else -> null
        }
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
        val blockName = exp.vals[offset].symbolStr() ?: return Pair(emptyList(), 0)
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
                    if (exp.vals[offset + opOffset].symbolStr() == "else") {
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
        require(exp.vals[offset + opOffset].symbolStr() == "end")
        opOffset++
        if (exp.maybeName(offset + opOffset) != null) opOffset++
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
        if (maybeImpExp == null) currIndex++
        // Try data approach
        if (exp.vals[currIndex] is SExpr.Multi) throw Exception("Data string not yet supported for memory")
        return Triple(name, toMemorySig(exp, currIndex), maybeImpExp)
    }

    fun toMemorySig(exp: SExpr.Multi, offset: Int): Node.Type.Memory {
        return Node.Type.Memory(toResizeableLimits(exp, offset))
    }

    fun toModule(exp: SExpr.Multi): Pair<String?, Node.Module> {
        exp.requireFirstSymbol("module")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++

        // Func sigs
        var types = exp.repeated("type", currIndex, { toTypeDef(it).second })
        currIndex += types.size

        // Func defs including imports/exports
        val (importFuncs, otherFuncs) = exp.repeated("func", currIndex, this::toFunc).
                partition { (_, _, impExp) -> impExp?.importModule != null }
        currIndex += importFuncs.size + otherFuncs.size
        // Add function imports
        var imports = importFuncs.mapNotNull { (_, fn, impExp) ->
            impExp?.importModule?.let {
                types += fn.type
                Node.Import(it, impExp.field, Node.Import.Kind.Func(types.lastIndex))
            }
        }
        // Add other funcs
        var exports = emptyList<Node.Export>()
        val funcs = otherFuncs.mapIndexedNotNull { index, (_, fn, impExp) ->
            if (impExp != null) exports += Node.Export(impExp.field, Node.ExternalKind.FUNCTION, index)
            fn
        }

        // Import defs
        val explicitImports = exp.repeated("import", currIndex, this::toImport).map {
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
        currIndex += explicitImports.size
        imports += explicitImports

        // Export defs
        val explicitExports = exp.repeated("export", currIndex, this::toExport)
        currIndex += explicitExports.size
        exports += explicitExports

        // Table defs
        val (importTables, otherTables) = exp.repeated("table", currIndex, this::toTable).
                partition { (_, _, impExp) -> impExp?.importModule != null }
        currIndex += importTables.size + otherTables.size
        // Add table imports
        imports += importTables.mapNotNull { (_, tbl, impExp) ->
            impExp?.importModule?.let { Node.Import(it, impExp.field, Node.Import.Kind.Table(tbl)) }
        }
        // Add other tables
        val tables = otherTables.mapIndexedNotNull { index, (_, tbl, impExp) ->
            if (impExp != null) exports += Node.Export(impExp.field, Node.ExternalKind.TABLE, index)
            tbl
        }

        // Memory defs
        val (importMemories, otherMemories) = exp.repeated("memory", currIndex, this::toMemory).
                partition { (_, _, impExp) -> impExp?.importModule != null }
        currIndex += importMemories.size + otherMemories.size
        // Add memory imports
        imports += importMemories.mapNotNull { (_, mem, impExp) ->
            impExp?.importModule?.let { Node.Import(it, impExp.field, Node.Import.Kind.Memory(mem)) }
        }
        // Add other tables
        val memories = otherMemories.mapIndexedNotNull { index, (_, mem, impExp) ->
            if (impExp != null) exports += Node.Export(impExp.field, Node.ExternalKind.MEMORY, index)
            mem
        }

        // Elems
        val elems = exp.repeated("elem", currIndex, this::toElem).map { it.second }
        currIndex += elems.size

        // Data
        val data = exp.repeated("data", currIndex, this::toData).map { it.second }
        currIndex += data.size

        // Start index
        val start = if (currIndex < exp.vals.size) toStart(exp.vals[0] as SExpr.Multi) else null

        val globals = emptyList<Node.Global>()
        val customSections = emptyList<Node.CustomSection>()
        return Pair(name, Node.Module(types, imports, tables, memories, globals,
            exports, start, elems, funcs, data, customSections))
    }

    fun toOpMaybe(exp: SExpr.Multi, offset: Int): Pair<Node.Instr, Int>? {
        if (offset >= exp.vals.size) return null
        val head = exp.vals[offset].symbol()!!
        fun oneVar() = toVar(exp.vals[offset + 1].symbol()!!)
        val op = InstrOp.strToOpMap[head.contents]
        return when(op) {
            null -> null
            is InstrOp.ControlFlowOp.NoArg -> Pair(op.create, 1)
            is InstrOp.ControlFlowOp.TypeArg -> return null // Type not handled here
            is InstrOp.ControlFlowOp.DepthArg -> Pair(op.create(oneVar()), 2)
            is InstrOp.ControlFlowOp.TableArg -> {
                val vars = exp.vals.drop(offset + 1).takeUntilNullLazy(this::toVarMaybe)
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
                if (exp.vals.size > offset + count) exp.vals[offset + count].symbolStr().also {
                    if (it != null && it.startsWith("offset=")) {
                        instrOffset = it.substring(7).toInt()
                        count++
                    }
                }
                if (exp.vals.size > offset + count) exp.vals[offset + count].symbolStr().also {
                    if (it != null && it.startsWith("align=")) {
                        instrAlign = it.substring(6).toInt()
                        count++
                    }
                }
                Pair(op.create(instrAlign, instrOffset), count)
            }
            is InstrOp.MemOp.ReservedArg -> Pair(op.create(false), 1)
            is InstrOp.ConstOp.IntArg -> Pair(op.create(exp.vals[offset + 1].symbol()!!.contents.toInt()), 2)
            is InstrOp.ConstOp.LongArg -> Pair(op.create(exp.vals[offset + 1].symbol()!!.contents.toLong()), 2)
            is InstrOp.ConstOp.FloatArg -> Pair(op.create(exp.vals[offset + 1].symbol()!!.contents.toFloat()), 2)
            is InstrOp.ConstOp.DoubleArg -> Pair(op.create(exp.vals[offset + 1].symbol()!!.contents.toDouble()), 2)
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
        TODO()
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

    fun toStart(exp: SExpr.Multi): Int {
        exp.requireFirstSymbol("start")
        return toVar(exp.vals[1].symbol()!!)
    }

    fun toTable(exp: SExpr.Multi): Triple<String?, Node.Type.Table, ImportOrExport?> {
        exp.requireFirstSymbol("table")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        val maybeImpExp = toImportOrExportMaybe(exp, currIndex)
        if (maybeImpExp == null) currIndex++
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

    fun toTypeDef(exp: SExpr.Multi): Pair<String?, Node.Type.Func> {
        exp.requireFirstSymbol("typedef")
        var currIndex = 1
        val name = exp.maybeName(currIndex)
        if (name != null) currIndex++
        val funcSigExp = exp.vals[currIndex] as SExpr.Multi
        funcSigExp.requireFirstSymbol("func")
        return Pair(name, toFuncSig(funcSigExp.vals[1] as SExpr.Multi))
    }

    fun toVar(exp: SExpr.Symbol): Int {
        // TODO: what about name?
        return exp.contents.toInt()
    }

    fun toVarMaybe(exp: SExpr): Int? {
        // TODO: what about name?
        return exp.symbolStr()?.toIntOrNull()
    }

    private fun SExpr.requireSymbol(contents: String, quotedCheck: Boolean? = null) {
        if (this is SExpr.Symbol && this.contents == contents &&
                (quotedCheck == null || this.quoted == quotedCheck)) {
            return
        }
        throw Exception("Expected symbol of $contents, got $this")
    }


    private fun SExpr.symbol() = this as? SExpr.Symbol
    private fun SExpr.symbolStr() = this.symbol()?.let { it.contents }

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
