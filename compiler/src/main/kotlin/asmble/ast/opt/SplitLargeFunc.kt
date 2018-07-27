package asmble.ast.opt

import asmble.ast.Node
import asmble.ast.Stack
import kotlin.math.roundToInt
import kotlin.reflect.KClass

// This is a naive implementation that just grabs adjacent sets of restricted insns and breaks the one that will save
// the most instructions off into its own function.
open class SplitLargeFunc(
    val minSetLength: Int = 5,
    val maxPercentOfWhole: Double = 0.6
) {

    // Null if no replacement. Second value is number of instructions saved.
    fun apply(mod: Node.Module, fnIndex: Int): Pair<Node.Module, Int>? =
        // Just take the best pattern and apply it
        commonPatterns(mod, mod.funcs[fnIndex]).firstOrNull()?.let {
            TODO()
        }

    // Results are by most insns saved. There can be overlap across patterns but never within a single pattern.
    fun commonPatterns(mod: Node.Module, fn: Node.Func): List<CommonPattern> {
        // Walk the full stack of instructions to get stack info
        val stack = Stack.walkStrict(mod, fn)

        // Let's grab sets of insns that qualify. In this naive impl, in order to qualify the insn set needs to
        // only have a certain set of insns that can be broken off. It can also only change the stack by 0 or 1
        // value while never dipping below the starting stack. We also store the index they started at.
        var insnSets = emptyList<InsnSet>()
        val maxSetLength = (fn.instructions.size * maxPercentOfWhole).roundToInt()
        fn.instructions.foldIndexed(null as List<Node.Instr>?) { index, lastInsns, insn ->
            if (!insn.canBeMoved) null else (lastInsns ?: emptyList()).plus(insn).also { newInsnSet ->
                // If within the len requirement, it may be added
                if (newInsnSet.size in minSetLength..maxSetLength) {
                    // Before adding, make sure it qualifies with the stack
                    val insnSet = InsnSet(index + 1 - newInsnSet.size, newInsnSet, null).withStackValueIfValid(stack)
                    insnSet?.also { insnSets += it }
                }
            }
        }

        // Now let's create replacements for each, keyed by the extracted func
        val patterns = insnSets.fold(emptyMap<ExtractedFunc, List<Replacement>>()) { map, insnSet ->
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
        TODO()
    }

    fun InsnSet.extractCommonFunc(): Pair<ExtractedFunc, Replacement> {
        // This extracts a function with constants changed to parameters
        TODO()
    }

    data class InsnSet(
        val startIndex: Int,
        val insns: List<Node.Instr>,
        val valueAddedToStack: Node.Type.Value?
    )

    data class ExtractedFunc(
        val func: Node.Func,
        // This is what must be put on the stack as consts before calling
        val preCallConstTypes: List<Node.Type.Value>
    )

    data class Replacement(
        val range: IntRange,
        val preCallConsts: List<Number>
    ) {
        val insnsSaved get() = range.last - range.first + 1 - preCallConsts.size
        fun overlaps(o: Replacement) = range.contains(o.range.first) || range.contains(o.range.last) ||
            o.range.contains(range.first) || o.range.contains(range.last)
    }

    data class CommonPattern(
        val newFunc: ExtractedFunc,
        // In order by earliest replacement first
        val replacements: List<Replacement>
    ) {
        // Replacement pieces saved (with one added for the invocation) less new func instructions
        val insnsSaved get() = replacements.sumBy { 1 + it.insnsSaved } - newFunc.func.instructions.size
    }

    companion object : SplitLargeFunc()
}