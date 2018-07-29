package asmble.ast.opt

import asmble.ast.Node
import asmble.ast.Stack

// This is a naive implementation that just grabs adjacent sets of restricted insns and breaks the one that will save
// the most instructions off into its own function.
open class SplitLargeFunc(
    val minSetLength: Int = 5,
    val maxSetLength: Int = 40,
    val maxParamCount: Int = 30
) {

    // Null if no replacement. Second value is number of instructions saved. fnIndex must map to actual func,
    // not imported one.
    fun apply(mod: Node.Module, fnIndex: Int): Pair<Node.Module, Int>? {
        // Get the func
        val importFuncCount = mod.imports.count { it.kind is Node.Import.Kind.Func }
        val actualFnIndex = fnIndex - importFuncCount
        val func = mod.funcs.getOrElse(actualFnIndex) {
            error("Unable to find non-import func at $fnIndex (actual $actualFnIndex)")
        }

        // Just take the best pattern and apply it
        val newFuncIndex = importFuncCount + mod.funcs.size
        return commonPatterns(mod, func).firstOrNull()?.let { pattern ->
            // Name it as <funcname>$splitN (n is num just to disambiguate) if names are part of the mod
            val newName = mod.names?.funcNames?.get(fnIndex)?.let {
                "$it\$split".let { it + mod.names.funcNames.count { (_, v) -> v.startsWith(it) } }
            }

            // Go over every replacement in reverse, changing the instructions to our new set
            val newInsns = pattern.replacements.foldRight(func.instructions) { repl, insns ->
                insns.take(repl.range.start) +
                    repl.preCallConsts +
                    Node.Instr.Call(newFuncIndex) +
                    insns.drop(repl.range.endInclusive + 1)
            }

            // Return the module w/ the new function, it's new name, and the insns saved
            mod.copy(
                funcs = mod.funcs.toMutableList().also {
                    it[actualFnIndex] = func.copy(instructions = newInsns)
                } + pattern.newFunc,
                names = mod.names?.copy(funcNames = mod.names.funcNames.toMutableMap().also {
                    it[newFuncIndex] = newName!!
                })
            ) to pattern.insnsSaved
        }
    }

    // Results are by most insns saved. There can be overlap across patterns but never within a single pattern.
    fun commonPatterns(mod: Node.Module, fn: Node.Func): List<CommonPattern> {
        // Walk the stack for validation needs
        val stack = Stack.walkStrict(mod, fn)

        // Let's grab sets of insns that qualify. In this naive impl, in order to qualify the insn set needs to
        // only have a certain set of insns that can be broken off. It can also only change the stack by 0 or 1
        // value while never dipping below the starting stack. We also store the index they started at.
        var insnSets = emptyList<InsnSet>()
        // Pair in fold keyed by insn index
        fn.instructions.foldIndexed(null as List<Pair<Int, Node.Instr>>?) { index, lastInsns, insn ->
            if (!insn.canBeMoved) null else (lastInsns ?: emptyList()).plus(index to insn).also { fullNewInsnSet ->
                // Get all final instructions between min and max size and with allowed param count (i.e. const count)
                val trailingInsnSet = fullNewInsnSet.takeLast(maxSetLength)

                // Get all instructions between the min and max
                insnSets += (minSetLength..maxSetLength).
                    asSequence().
                    flatMap { trailingInsnSet.asSequence().windowed(it) }.
                    filter { it.count { it.second is Node.Instr.Args.Const<*> } <= maxParamCount }.
                    mapNotNull { newIndexedInsnSet ->
                        // Before adding, make sure it qualifies with the stack
                        InsnSet(
                            startIndex = newIndexedInsnSet.first().first,
                            insns = newIndexedInsnSet.map { it.second },
                            valueAddedToStack = null
                        ).withStackValueIfValid(stack)
                    }
            }
        }
        // Sort the insn sets by the ones with the most insns
        insnSets = insnSets.sortedByDescending { it.insns.size }

        // Now let's create replacements for each, keyed by the extracted func
        val patterns = insnSets.fold(emptyMap<Node.Func, List<Replacement>>()) { map, insnSet ->
            insnSet.extractCommonFunc().let { (func, replacement) ->
                val existingReplacements = map.getOrDefault(func, emptyList())
                // Ignore if there is any overlap
                if (existingReplacements.any(replacement::overlaps)) map
                else map + (func to existingReplacements.plus(replacement))
            }
        }

        // Now sort the patterns by most insns saved and return
        return patterns.map { (k, v) ->
            CommonPattern(k, v.sortedBy { it.range.first })
        }.sortedByDescending { it.insnsSaved }
    }

    val Node.Instr.canBeMoved get() =
        // No blocks
        this !is Node.Instr.Block && this !is Node.Instr.Loop && this !is Node.Instr.If &&
        this !is Node.Instr.Else && this !is Node.Instr.End &&
        // No breaks
        this !is Node.Instr.Br && this !is Node.Instr.BrIf && this !is Node.Instr.BrTable &&
        // No return
        this !is Node.Instr.Return &&
        // No local access
        this !is Node.Instr.GetLocal && this !is Node.Instr.SetLocal && this !is Node.Instr.TeeLocal

    fun InsnSet.withStackValueIfValid(stack: Stack): InsnSet? {
        // This makes sure that the stack only changes by at most one item and never dips below its starting val.
        // If it is invalid, null is returned. If it qualifies and does change 1 value, it is set.

        // First, make sure the stack after the last insn is the same as the first or the same + 1 val
        val startingStack = stack.insnApplies[startIndex].stackAtBeginning!!
        val endingStack = stack.insnApplies.getOrNull(startIndex + insns.size)?.stackAtBeginning ?: stack.current!!
        if (endingStack.size != startingStack.size && endingStack.size != startingStack.size + 1) return null
        if (endingStack.take(startingStack.size) != startingStack) return null

        // Now, walk the insns and make sure they never pop below the start
        var stackCounter = 0
        stack.insnApplies.subList(startIndex, startIndex + insns.size).forEach {
            it.stackChanges.forEach {
                stackCounter += if (it.pop) -1 else 1
                if (stackCounter < 0) return null
            }
        }
        // We're good, now only if the ending stack is one over the start do we have a ret val
        return copy(
            valueAddedToStack = endingStack.lastOrNull()?.takeIf { endingStack.size == startingStack.size + 1 }
        )
    }

    fun InsnSet.extractCommonFunc() =
        // This extracts a function with constants changed to parameters
        insns.fold(Pair(
            Node.Func(Node.Type.Func(params = emptyList(), ret = valueAddedToStack), emptyList(), emptyList()),
            Replacement(range = startIndex until startIndex + insns.size, preCallConsts = emptyList()))
        ) { (func, repl), insn ->
            if (insn !is Node.Instr.Args.Const<*>) func.copy(instructions = func.instructions + insn) to repl
            else func.copy(
                type = func.type.copy(params = func.type.params + insn.constType),
                instructions = func.instructions + Node.Instr.GetLocal(func.type.params.size)
            ) to repl.copy(preCallConsts = repl.preCallConsts + insn)
        }

    protected val Node.Instr.Args.Const<*>.constType get() = when (this) {
        is Node.Instr.I32Const -> Node.Type.Value.I32
        is Node.Instr.I64Const -> Node.Type.Value.I64
        is Node.Instr.F32Const -> Node.Type.Value.F32
        is Node.Instr.F64Const -> Node.Type.Value.F64
        else -> error("unreachable")
    }

    data class InsnSet(
        val startIndex: Int,
        val insns: List<Node.Instr>,
        val valueAddedToStack: Node.Type.Value?
    )

    data class Replacement(
        val range: IntRange,
        val preCallConsts: List<Node.Instr>
    ) {
        // Subtract one because there is a call after this
        val insnsSaved get() = (range.last + 1) - range.first - 1 - preCallConsts.size
        fun overlaps(o: Replacement) = range.contains(o.range.first) || range.contains(o.range.last) ||
            o.range.contains(range.first) || o.range.contains(range.last)
    }

    data class CommonPattern(
        val newFunc: Node.Func,
        // In order by earliest replacement first
        val replacements: List<Replacement>
    ) {
        // Replacement pieces saved (with one added for the invocation) less new func instructions
        val insnsSaved get() = replacements.sumBy { it.insnsSaved } - newFunc.instructions.size
    }

    companion object : SplitLargeFunc()
}