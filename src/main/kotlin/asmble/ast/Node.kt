package asmble.ast

import kotlin.reflect.KClass

sealed class Node {
    data class Module(
        val types: List<Type.Func>,
        val imports: List<Import>,
        val tables: List<Type.Table>,
        val memories: List<Type.Memory>,
        val globals: List<Global>,
        val exports: List<Export>,
        val startFuncIndex: Int?,
        val elems: List<Elem>,
        val funcs: List<Func>,
        val data: List<Data>,
        val customSections: List<CustomSection>
    ) : Node()

    enum class ExternalKind {
        FUNCTION, TABLE, MEMORY, GLOBAL
    }

    enum class ElemType {
        ANYFUNC
    }

    sealed class Type : Node() {

        sealed class Value : Type() {
            object I32 : Value()
            object I64 : Value()
            object F32 : Value()
            object F64 : Value()
        }

        data class Func(
            val params: List<Value>,
            val ret: Value?
        ) : Type()

        data class Global(
            val contentType: Value,
            val mutable: Boolean
        ) : Type()

        data class Table(
            val elemType: ElemType,
            val limits: ResizableLimits
        ) : Type()

        data class Memory(
            val limits: ResizableLimits
        ) : Type()
    }

    data class ResizableLimits(
        val initial: Int,
        val maximum: Int?
    )

    data class Import(
        val module: String,
        val field: String,
        val kind: Kind
    ) : Node() {
        sealed class Kind {
            data class Func(val typeIndex: Int) : Kind()
            data class Table(val type: Type.Table) : Kind()
            data class Memory(val type: Type.Memory) : Kind()
            data class Global(val type: Type.Global) : Kind()
        }
    }

    data class Global(
        val type: Type.Global,
        val init: List<Instr>
    ) : Node()

    data class Export(
        val field: String,
        val kind: ExternalKind,
        val index: Int
    ) : Node()

    data class Elem(
        val index: Int,
        val offset: List<Instr>,
        val funcIndices: List<Int>
    ) : Node()

    data class Func(
        val type: Type.Func,
        val locals: List<Type.Value>,
        val instructions: List<Instr>
    ) : Node()

    data class Data(
        val index: Int,
        val offset: List<Instr>,
        val data: ByteArray
    ) : Node()

    data class CustomSection(
        val sectionIndex: Int,
        val name: String,
        val payload: ByteArray
    ) : Node()

    sealed class Instr : Node() {
        // Control flow
        object Unreachable : Instr()
        object Nop : Instr()
        data class Block(val type: Type.Value?) : Instr()
        data class Loop(val type: Type.Value?) : Instr()
        data class If(val type: Type.Value?) : Instr()
        object Else : Instr()
        object End : Instr()
        data class Br(val relativeDepth: Int) : Instr()
        data class BrIf(val relativeDepth: Int) : Instr()
        data class BrTable(
            val targetTable: List<Int>,
            val default: Int
        ) : Instr()
        object Return : Instr()

        // Call operators
        data class Call(val funcIndex: Int) : Instr()
        data class CallIndirect(
            val typeIndex: Int,
            val reserved: Boolean
        ) : Instr()

        // Parametric operators
        object Drop : Instr()
        object Select : Instr()

        // Variable access
        data class GetLocal(val index: Int) : Instr()
        data class SetLocal(val index: Int) : Instr()
        data class TeeLocal(val index: Int) : Instr()
        data class GetGlobal(val index: Int) : Instr()
        data class SetGlobal(val index: Int) : Instr()

        // Memory operators
        data class I32Load(val flags: Int, val offset: Int) : Instr()
        data class I64Load(val flags: Int, val offset: Int) : Instr()
        data class F32Load(val flags: Int, val offset: Int) : Instr()
        data class F64Load(val flags: Int, val offset: Int) : Instr()
        data class I32Load8S(val flags: Int, val offset: Int) : Instr()
        data class I32Load8U(val flags: Int, val offset: Int) : Instr()
        data class I32Load16S(val flags: Int, val offset: Int) : Instr()
        data class I32Load16U(val flags: Int, val offset: Int) : Instr()
        data class I64Load8S(val flags: Int, val offset: Int) : Instr()
        data class I64Load8U(val flags: Int, val offset: Int) : Instr()
        data class I64Load16S(val flags: Int, val offset: Int) : Instr()
        data class I64Load16U(val flags: Int, val offset: Int) : Instr()
        data class I64Load32S(val flags: Int, val offset: Int) : Instr()
        data class I64Load32U(val flags: Int, val offset: Int) : Instr()
        data class I32Store(val flags: Int, val offset: Int) : Instr()
        data class I64Store(val flags: Int, val offset: Int) : Instr()
        data class F32Store(val flags: Int, val offset: Int) : Instr()
        data class F64Store(val flags: Int, val offset: Int) : Instr()
        data class I32Store8(val flags: Int, val offset: Int) : Instr()
        data class I32Store16(val flags: Int, val offset: Int) : Instr()
        data class I64Store8(val flags: Int, val offset: Int) : Instr()
        data class I64Store16(val flags: Int, val offset: Int) : Instr()
        data class I64Store32(val flags: Int, val offset: Int) : Instr()
        data class CurrentMemory(val reserved: Boolean) : Instr()
        data class GrowMemory(val reserved: Boolean) : Instr()

        // Constants
        data class I32Const(val value: Int) : Instr()
        data class I64Const(val value: Long) : Instr()
        data class F32Const(val value: Float) : Instr()
        data class F64Const(val value: Double) : Instr()

        // Comparison operators
        object I32Eqz : Instr()
        object I32Eq : Instr()
        object I32Ne : Instr()
        object I32LtS : Instr()
        object I32LtU : Instr()
        object I32GtS : Instr()
        object I32GtU : Instr()
        object I32LeS : Instr()
        object I32LeU : Instr()
        object I32GeS : Instr()
        object I32GeU : Instr()
        object I64Eqz : Instr()
        object I64Eq : Instr()
        object I64Ne : Instr()
        object I64LtS : Instr()
        object I64LtU : Instr()
        object I64GtS : Instr()
        object I64GtU : Instr()
        object I64LeS : Instr()
        object I64LeU : Instr()
        object I64GeS : Instr()
        object I64GeU : Instr()
        object F32Eq : Instr()
        object F32Ne : Instr()
        object F32Lt : Instr()
        object F32Gt : Instr()
        object F32Le : Instr()
        object F32Ge : Instr()
        object F64Eq : Instr()
        object F64Ne : Instr()
        object F64Lt : Instr()
        object F64Gt : Instr()
        object F64Le : Instr()
        object F64Ge : Instr()

        // Numeric operators
        object I32Clz : Instr()
        object I32Ctz : Instr()
        object I32Popcnt : Instr()
        object I32Add : Instr()
        object I32Sub : Instr()
        object I32Mul : Instr()
        object I32DivS : Instr()
        object I32DivU : Instr()
        object I32RemS : Instr()
        object I32RemU : Instr()
        object I32And : Instr()
        object I32Or : Instr()
        object I32Xor : Instr()
        object I32Shl : Instr()
        object I32ShrS : Instr()
        object I32ShrU : Instr()
        object I32Rotl : Instr()
        object I32Rotr : Instr()
        object I64Clz : Instr()
        object I64Ctz : Instr()
        object I64Popcnt : Instr()
        object I64Add : Instr()
        object I64Sub : Instr()
        object I64Mul : Instr()
        object I64DivS : Instr()
        object I64DivU : Instr()
        object I64RemS : Instr()
        object I64RemU : Instr()
        object I64And : Instr()
        object I64Or : Instr()
        object I64Xor : Instr()
        object I64Shl : Instr()
        object I64ShrS : Instr()
        object I64ShrU : Instr()
        object I64Rotl : Instr()
        object I64Rotr : Instr()
        object F32Abs : Instr()
        object F32Neg : Instr()
        object F32Ceil : Instr()
        object F32Floor : Instr()
        object F32Trunc : Instr()
        object F32Nearest : Instr()
        object F32Sqrt : Instr()
        object F32Add : Instr()
        object F32Sub : Instr()
        object F32Mul : Instr()
        object F32Div : Instr()
        object F32Min : Instr()
        object F32Max : Instr()
        object F32CopySign : Instr()
        object F64Abs : Instr()
        object F64Neg : Instr()
        object F64Ceil : Instr()
        object F64Floor : Instr()
        object F64Trunc : Instr()
        object F64Nearest : Instr()
        object F64Sqrt : Instr()
        object F64Add : Instr()
        object F64Sub : Instr()
        object F64Mul : Instr()
        object F64Div : Instr()
        object F64Min : Instr()
        object F64Max : Instr()
        object F64CopySign : Instr()

        // Conversions
        object I32WrapI64 : Instr()
        object I32TruncSF32 : Instr()
        object I32TruncUF32 : Instr()
        object I32TruncSF64 : Instr()
        object I32TruncUF64 : Instr()
        object I64ExtendSI32 : Instr()
        object I64ExtendUI32 : Instr()
        object I64TruncSF32 : Instr()
        object I64TruncUF32 : Instr()
        object I64TruncSF64 : Instr()
        object I64TruncUF64 : Instr()
        object F32ConvertSI32 : Instr()
        object F32ConvertUI32 : Instr()
        object F32ConvertSI64 : Instr()
        object F32ConvertUI64 : Instr()
        object F32DemoteF64 : Instr()
        object F64ConvertSI32 : Instr()
        object F64ConvertUI32 : Instr()
        object F64ConvertSI64 : Instr()
        object F64ConvertUI64 : Instr()
        object F64PromoteF32 : Instr()

        // Reinterpretations
        object I32ReinterpretF32 : Instr()
        object I64ReinterpretF64 : Instr()
        object F32ReinterpretI32 : Instr()
        object F64ReinterpretI64 : Instr()
    }

    sealed class InstrOp(name: String) {

        sealed class ControlFlowOp(name: String) : InstrOp(name) {
            data class NoArg(val name: String, val create: Instr) : ControlFlowOp(name)
            data class TypeArg(val name: String, val create: (Type.Value?) -> Instr) : ControlFlowOp(name)
            data class DepthArg(val name: String, val create: (Int) -> Instr) : ControlFlowOp(name)
            data class TableArg(val name: String, val create: (List<Int>, Int) -> Instr) : ControlFlowOp(name)
        }

        sealed class CallOp(name: String) : InstrOp(name) {
            data class IndexArg(val name: String, val create: (Int) -> Instr) : CallOp(name)
            data class IndexReservedArg(val name: String, val create: (Int, Boolean) -> Instr) : CallOp(name)
        }

        sealed class ParamOp(name: String) : InstrOp(name) {
            data class NoArg(val name: String, val create: Instr) : ParamOp(name)
        }

        sealed class VarOp(name: String) : InstrOp(name) {
            data class IndexArg(val name: String, val create: (Int) -> Instr) : VarOp(name)
        }

        sealed class MemOp(name: String) : InstrOp(name) {
            data class FlagsOffsetArg(val name: String, val create: (Int, Int) -> Instr) : MemOp(name)
            data class ReservedArg(val name: String, val create: (Boolean) -> Instr) : MemOp(name)
        }

        sealed class ConstOp(name: String) : InstrOp(name) {
            data class IntArg(val name: String, val create: (Int) -> Instr) : ConstOp(name)
            data class LongArg(val name: String, val create: (Long) -> Instr) : ConstOp(name)
            data class FloatArg(val name: String, val create: (Float) -> Instr) : ConstOp(name)
            data class DoubleArg(val name: String, val create: (Double) -> Instr) : ConstOp(name)
        }

        sealed class CompareOp(name: String) : InstrOp(name) {
            data class NoArg(val name: String, val create: Instr) : CompareOp(name)
        }

        sealed class NumOp(name: String) : InstrOp(name) {
            data class NoArg(val name: String, val create: Instr) : NumOp(name)
        }

        sealed class ConvertOp(name: String) : InstrOp(name) {
            data class NoArg(val name: String, val create: Instr) : ConvertOp(name)
        }

        sealed class ReinterpretOp(name: String) : InstrOp(name) {
            data class NoArg(val name: String, val create: Instr) : ReinterpretOp(name)
        }

        companion object {
            // TODO: why can't I set a val in init?
            var strToOpMap = emptyMap<String, InstrOp>(); private set
            var classToOpMap = emptyMap<KClass<out Instr>, InstrOp>(); private set

            init {
                // Can't use reification here because inline funcs not allowed in nested context :-(
                fun <T> opMapEntry(name: String, newOp: (String, T) -> InstrOp, create: T, clazz: KClass<out Instr>) {
                    require(!strToOpMap.contains(name) && !classToOpMap.contains(clazz))
                    val op = newOp(name, create)
                    strToOpMap += name to op
                    classToOpMap += clazz to op
                }

                opMapEntry("unreachable", ControlFlowOp::NoArg, Instr.Unreachable, Instr.Unreachable::class)
                opMapEntry("nop", ControlFlowOp::NoArg, Instr.Nop, Instr.Nop::class)
                opMapEntry("block", ControlFlowOp::TypeArg, Instr::Block, Instr.Block::class)
                opMapEntry("loop", ControlFlowOp::TypeArg, Instr::Loop, Instr.Loop::class)
                opMapEntry("if", ControlFlowOp::TypeArg, Instr::If, Instr.If::class)
                opMapEntry("else", ControlFlowOp::NoArg, Instr.Else, Instr.Else::class)
                opMapEntry("end", ControlFlowOp::NoArg, Instr.End, Instr.End::class)
                opMapEntry("br", ControlFlowOp::DepthArg, Instr::Br, Instr.Br::class)
                opMapEntry("br_if", ControlFlowOp::DepthArg, Instr::BrIf, Instr.BrIf::class)
                opMapEntry("br_if", ControlFlowOp::TableArg, Instr::BrTable, Instr.BrTable::class)
                opMapEntry("return", ControlFlowOp::NoArg, Instr.Return, Instr.Return::class)

                opMapEntry("call", CallOp::IndexArg, Instr::Call, Instr.Call::class)
                opMapEntry("call_indirect", CallOp::IndexReservedArg, Instr::CallIndirect, Instr.CallIndirect::class)

                opMapEntry("drop", ParamOp::NoArg, Instr.Drop, Instr.Drop::class)
                opMapEntry("select", ParamOp::NoArg, Instr.Select, Instr.Drop::class)

                opMapEntry("get_local", VarOp::IndexArg, Instr::GetLocal, Instr.GetLocal::class)
                opMapEntry("set_local", VarOp::IndexArg, Instr::SetLocal, Instr.SetLocal::class)
                opMapEntry("tee_local", VarOp::IndexArg, Instr::TeeLocal, Instr.TeeLocal::class)
                opMapEntry("get_global", VarOp::IndexArg, Instr::GetGlobal, Instr.GetGlobal::class)
                opMapEntry("set_global", VarOp::IndexArg, Instr::SetGlobal, Instr.SetGlobal::class)

                opMapEntry("i32.load", MemOp::FlagsOffsetArg, Instr::I32Load, Instr.I32Load::class)
                opMapEntry("i64.load", MemOp::FlagsOffsetArg, Instr::I64Load, Instr.I64Load::class)
                opMapEntry("f32.load", MemOp::FlagsOffsetArg, Instr::F32Load, Instr.F32Load::class)
                opMapEntry("f64.load", MemOp::FlagsOffsetArg, Instr::F64Load, Instr.F64Load::class)
                opMapEntry("i32.load8_s", MemOp::FlagsOffsetArg, Instr::I32Load8S, Instr.I32Load8S::class)
                opMapEntry("i32.load8_u", MemOp::FlagsOffsetArg, Instr::I32Load8U, Instr.I32Load8U::class)
                opMapEntry("i32.load16_s", MemOp::FlagsOffsetArg, Instr::I32Load16S, Instr.I32Load16S::class)
                opMapEntry("i32.load16_u", MemOp::FlagsOffsetArg, Instr::I32Load16U, Instr.I32Load16U::class)
                opMapEntry("i64.load8_s", MemOp::FlagsOffsetArg, Instr::I64Load8S, Instr.I64Load8S::class)
                opMapEntry("i64.load8_u", MemOp::FlagsOffsetArg, Instr::I64Load8U, Instr.I64Load8U::class)
                opMapEntry("i64.load16_s", MemOp::FlagsOffsetArg, Instr::I64Load16S, Instr.I64Load16S::class)
                opMapEntry("i64.load16_u", MemOp::FlagsOffsetArg, Instr::I64Load16U, Instr.I64Load16U::class)
                opMapEntry("i64.load32_s", MemOp::FlagsOffsetArg, Instr::I64Load32S, Instr.I64Load32S::class)
                opMapEntry("i64.load32_u", MemOp::FlagsOffsetArg, Instr::I64Load32U, Instr.I64Load32U::class)
                opMapEntry("i32.store", MemOp::FlagsOffsetArg, Instr::I32Store, Instr.I32Store::class)
                opMapEntry("i64.store", MemOp::FlagsOffsetArg, Instr::I64Store, Instr.I64Store::class)
                opMapEntry("f32.store", MemOp::FlagsOffsetArg, Instr::F32Store, Instr.F32Store::class)
                opMapEntry("f64.store", MemOp::FlagsOffsetArg, Instr::F64Store, Instr.F64Store::class)
                opMapEntry("i32.store8", MemOp::FlagsOffsetArg, Instr::I32Store8, Instr.I32Store8::class)
                opMapEntry("i32.store16", MemOp::FlagsOffsetArg, Instr::I32Store16, Instr.I32Store16::class)
                opMapEntry("i64.store8", MemOp::FlagsOffsetArg, Instr::I64Store8, Instr.I64Store8::class)
                opMapEntry("i64.store16", MemOp::FlagsOffsetArg, Instr::I64Store16, Instr.I64Store16::class)
                opMapEntry("i64.store32", MemOp::FlagsOffsetArg, Instr::I64Store32, Instr.I64Store32::class)
                opMapEntry("current_memory", MemOp::ReservedArg, Instr::CurrentMemory, Instr.CurrentMemory::class)
                opMapEntry("grow_memory", MemOp::ReservedArg, Instr::GrowMemory, Instr.GrowMemory::class)

                opMapEntry("i32.const", ConstOp::IntArg, Instr::I32Const, Instr.I32Const::class)
                opMapEntry("i64.const", ConstOp::LongArg, Instr::I64Const, Instr.I64Const::class)
                opMapEntry("f32.const", ConstOp::FloatArg, Instr::F32Const, Instr.F32Const::class)
                opMapEntry("f64.const", ConstOp::DoubleArg, Instr::F64Const, Instr.F64Const::class)

                opMapEntry("i32.eqz", CompareOp::NoArg, Instr.I32Eqz, Instr.I32Eqz::class)
                opMapEntry("i32.eq", CompareOp::NoArg, Instr.I32Eq, Instr.I32Eq::class)
                opMapEntry("i32.ne", CompareOp::NoArg, Instr.I32Ne, Instr.I32Ne::class)
                opMapEntry("i32.lt_s", CompareOp::NoArg, Instr.I32LtS, Instr.I32LtS::class)
                opMapEntry("i32.lt_u", CompareOp::NoArg, Instr.I32LtU, Instr.I32LtU::class)
                opMapEntry("i32.gt_s", CompareOp::NoArg, Instr.I32GtS, Instr.I32GtS::class)
                opMapEntry("i32.gt_u", CompareOp::NoArg, Instr.I32GtU, Instr.I32GtU::class)
                opMapEntry("i32.le_s", CompareOp::NoArg, Instr.I32LeS, Instr.I32LeS::class)
                opMapEntry("i32.le_u", CompareOp::NoArg, Instr.I32LeU, Instr.I32LeU::class)
                opMapEntry("i32.ge_s", CompareOp::NoArg, Instr.I32GeS, Instr.I32GeS::class)
                opMapEntry("i32.ge_u", CompareOp::NoArg, Instr.I32GeU, Instr.I32GeU::class)
                opMapEntry("i64.eqz", CompareOp::NoArg, Instr.I64Eqz, Instr.I64Eqz::class)
                opMapEntry("i64.eq", CompareOp::NoArg, Instr.I64Eq, Instr.I64Eq::class)
                opMapEntry("i64.ne", CompareOp::NoArg, Instr.I64Ne, Instr.I64Ne::class)
                opMapEntry("i64.lt_s", CompareOp::NoArg, Instr.I64LtS, Instr.I64LtS::class)
                opMapEntry("i64.lt_u", CompareOp::NoArg, Instr.I64LtU, Instr.I64LtU::class)
                opMapEntry("i64.gt_s", CompareOp::NoArg, Instr.I64GtS, Instr.I64GtS::class)
                opMapEntry("i64.gt_u", CompareOp::NoArg, Instr.I64GtU, Instr.I64GtU::class)
                opMapEntry("i64.le_s", CompareOp::NoArg, Instr.I64LeS, Instr.I64LeS::class)
                opMapEntry("i64.le_u", CompareOp::NoArg, Instr.I64LeU, Instr.I64LeU::class)
                opMapEntry("i64.ge_s", CompareOp::NoArg, Instr.I64GeS, Instr.I64GeS::class)
                opMapEntry("i64.ge_u", CompareOp::NoArg, Instr.I64GeU, Instr.I64GeU::class)
                opMapEntry("f32.eq", CompareOp::NoArg, Instr.F32Eq, Instr.F32Eq::class)
                opMapEntry("f32.ne", CompareOp::NoArg, Instr.F32Ne, Instr.F32Ne::class)
                opMapEntry("f32.lt", CompareOp::NoArg, Instr.F32Lt, Instr.F32Lt::class)
                opMapEntry("f32.gt", CompareOp::NoArg, Instr.F32Gt, Instr.F32Gt::class)
                opMapEntry("f32.le", CompareOp::NoArg, Instr.F32Le, Instr.F32Le::class)
                opMapEntry("f32.ge", CompareOp::NoArg, Instr.F32Ge, Instr.F32Ge::class)
                opMapEntry("f64.eq", CompareOp::NoArg, Instr.F64Eq, Instr.F64Eq::class)
                opMapEntry("f64.ne", CompareOp::NoArg, Instr.F64Ne, Instr.F64Ne::class)
                opMapEntry("f64.lt", CompareOp::NoArg, Instr.F64Lt, Instr.F64Lt::class)
                opMapEntry("f64.gt", CompareOp::NoArg, Instr.F64Gt, Instr.F64Gt::class)
                opMapEntry("f64.le", CompareOp::NoArg, Instr.F64Le, Instr.F64Le::class)
                opMapEntry("f64.ge", CompareOp::NoArg, Instr.F64Ge, Instr.F64Ge::class)

                opMapEntry("i32.clz", NumOp::NoArg, Instr.I32Clz, Instr.I32Clz::class)
                opMapEntry("i32.ctz", NumOp::NoArg, Instr.I32Ctz, Instr.I32Ctz::class)
                opMapEntry("i32.popcnt", NumOp::NoArg, Instr.I32Popcnt, Instr.I32Popcnt::class)
                opMapEntry("i32.add", NumOp::NoArg, Instr.I32Add, Instr.I32Add::class)
                opMapEntry("i32.sub", NumOp::NoArg, Instr.I32Sub, Instr.I32Sub::class)
                opMapEntry("i32.mul", NumOp::NoArg, Instr.I32Mul, Instr.I32Mul::class)
                opMapEntry("i32.div_s", NumOp::NoArg, Instr.I32DivS, Instr.I32DivS::class)
                opMapEntry("i32.div_u", NumOp::NoArg, Instr.I32DivU, Instr.I32DivU::class)
                opMapEntry("i32.rem_s", NumOp::NoArg, Instr.I32RemS, Instr.I32RemS::class)
                opMapEntry("i32.rem_u", NumOp::NoArg, Instr.I32RemU, Instr.I32RemU::class)
                opMapEntry("i32.and", NumOp::NoArg, Instr.I32And, Instr.I32And::class)
                opMapEntry("i32.or", NumOp::NoArg, Instr.I32Or, Instr.I32Or::class)
                opMapEntry("i32.xor", NumOp::NoArg, Instr.I32Xor, Instr.I32Xor::class)
                opMapEntry("i32.shl", NumOp::NoArg, Instr.I32Shl, Instr.I32Shl::class)
                opMapEntry("i32.shr_s", NumOp::NoArg, Instr.I32ShrS, Instr.I32ShrS::class)
                opMapEntry("i32.shr_u", NumOp::NoArg, Instr.I32ShrU, Instr.I32ShrU::class)
                opMapEntry("i32.rotl", NumOp::NoArg, Instr.I32Rotl, Instr.I32Rotl::class)
                opMapEntry("i32.rotr", NumOp::NoArg, Instr.I32Rotr, Instr.I32Rotr::class)
                opMapEntry("i64.clz", NumOp::NoArg, Instr.I64Clz, Instr.I64Clz::class)
                opMapEntry("i64.ctz", NumOp::NoArg, Instr.I64Ctz, Instr.I64Ctz::class)
                opMapEntry("i64.popcnt", NumOp::NoArg, Instr.I64Popcnt, Instr.I64Popcnt::class)
                opMapEntry("i64.add", NumOp::NoArg, Instr.I64Add, Instr.I64Add::class)
                opMapEntry("i64.sub", NumOp::NoArg, Instr.I64Sub, Instr.I64Sub::class)
                opMapEntry("i64.mul", NumOp::NoArg, Instr.I64Mul, Instr.I64Mul::class)
                opMapEntry("i64.div_s", NumOp::NoArg, Instr.I64DivS, Instr.I64DivS::class)
                opMapEntry("i64.div_u", NumOp::NoArg, Instr.I64DivU, Instr.I64DivU::class)
                opMapEntry("i64.rem_s", NumOp::NoArg, Instr.I64RemS, Instr.I64RemS::class)
                opMapEntry("i64.rem_u", NumOp::NoArg, Instr.I64RemU, Instr.I64RemU::class)
                opMapEntry("i64.and", NumOp::NoArg, Instr.I64And, Instr.I64And::class)
                opMapEntry("i64.or", NumOp::NoArg, Instr.I64Or, Instr.I64Or::class)
                opMapEntry("i64.xor", NumOp::NoArg, Instr.I64Xor, Instr.I64Xor::class)
                opMapEntry("i64.shl", NumOp::NoArg, Instr.I64Shl, Instr.I64Shl::class)
                opMapEntry("i64.shr_s", NumOp::NoArg, Instr.I64ShrS, Instr.I64ShrS::class)
                opMapEntry("i64.shr_u", NumOp::NoArg, Instr.I64ShrU, Instr.I64ShrU::class)
                opMapEntry("i64.rotl", NumOp::NoArg, Instr.I64Rotl, Instr.I64Rotl::class)
                opMapEntry("i64.rotr", NumOp::NoArg, Instr.I64Rotr, Instr.I64Rotr::class)
                opMapEntry("f32.abs", NumOp::NoArg, Instr.F32Abs, Instr.F32Abs::class)
                opMapEntry("f32.neg", NumOp::NoArg, Instr.F32Neg, Instr.F32Neg::class)
                opMapEntry("f32.ceil", NumOp::NoArg, Instr.F32Ceil, Instr.F32Ceil::class)
                opMapEntry("f32.floor", NumOp::NoArg, Instr.F32Floor, Instr.F32Floor::class)
                opMapEntry("f32.trunc", NumOp::NoArg, Instr.F32Trunc, Instr.F32Trunc::class)
                opMapEntry("f32.nearest", NumOp::NoArg, Instr.F32Nearest, Instr.F32Nearest::class)
                opMapEntry("f32.sqrt", NumOp::NoArg, Instr.F32Sqrt, Instr.F32Sqrt::class)
                opMapEntry("f32.add", NumOp::NoArg, Instr.F32Add, Instr.F32Add::class)
                opMapEntry("f32.sub", NumOp::NoArg, Instr.F32Sub, Instr.F32Sub::class)
                opMapEntry("f32.mul", NumOp::NoArg, Instr.F32Mul, Instr.F32Mul::class)
                opMapEntry("f32.div", NumOp::NoArg, Instr.F32Div, Instr.F32Div::class)
                opMapEntry("f32.min", NumOp::NoArg, Instr.F32Min, Instr.F32Min::class)
                opMapEntry("f32.max", NumOp::NoArg, Instr.F32Max, Instr.F32Max::class)
                opMapEntry("f32.copysign", NumOp::NoArg, Instr.F32CopySign, Instr.F32CopySign::class)
                opMapEntry("f64.abs", NumOp::NoArg, Instr.F64Abs, Instr.F64Abs::class)
                opMapEntry("f64.neg", NumOp::NoArg, Instr.F64Neg, Instr.F64Neg::class)
                opMapEntry("f64.ceil", NumOp::NoArg, Instr.F64Ceil, Instr.F64Ceil::class)
                opMapEntry("f64.floor", NumOp::NoArg, Instr.F64Floor, Instr.F64Floor::class)
                opMapEntry("f64.trunc", NumOp::NoArg, Instr.F64Trunc, Instr.F64Trunc::class)
                opMapEntry("f64.nearest", NumOp::NoArg, Instr.F64Nearest, Instr.F64Nearest::class)
                opMapEntry("f64.sqrt", NumOp::NoArg, Instr.F64Sqrt, Instr.F64Sqrt::class)
                opMapEntry("f64.add", NumOp::NoArg, Instr.F64Add, Instr.F64Add::class)
                opMapEntry("f64.sub", NumOp::NoArg, Instr.F64Sub, Instr.F64Sub::class)
                opMapEntry("f64.mul", NumOp::NoArg, Instr.F64Mul, Instr.F64Mul::class)
                opMapEntry("f64.div", NumOp::NoArg, Instr.F64Div, Instr.F64Div::class)
                opMapEntry("f64.min", NumOp::NoArg, Instr.F64Min, Instr.F64Min::class)
                opMapEntry("f64.max", NumOp::NoArg, Instr.F64Max, Instr.F64Max::class)
                opMapEntry("f64.copysign", NumOp::NoArg, Instr.F64CopySign, Instr.F64CopySign::class)

                opMapEntry("i32.wrap/i64", ConvertOp::NoArg, Instr.I32WrapI64, Instr.I32WrapI64::class)
                opMapEntry("i32.trunc_s/f32", ConvertOp::NoArg, Instr.I32TruncSF32, Instr.I32TruncSF32::class)
                opMapEntry("i32.trunc_u/f32", ConvertOp::NoArg, Instr.I32TruncUF32, Instr.I32TruncUF32::class)
                opMapEntry("i32.trunc_s/f64", ConvertOp::NoArg, Instr.I32TruncSF64, Instr.I32TruncSF64::class)
                opMapEntry("i32.trunc_u/f64", ConvertOp::NoArg, Instr.I32TruncUF64, Instr.I32TruncUF64::class)
                opMapEntry("i64.extend_s/i32", ConvertOp::NoArg, Instr.I64ExtendSI32, Instr.I64ExtendSI32::class)
                opMapEntry("i64.extend_u/i32", ConvertOp::NoArg, Instr.I64ExtendUI32, Instr.I64ExtendUI32::class)
                opMapEntry("i64.trunc_s/f32", ConvertOp::NoArg, Instr.I64TruncSF32, Instr.I64TruncSF32::class)
                opMapEntry("i64.trunc_u/f32", ConvertOp::NoArg, Instr.I64TruncUF32, Instr.I64TruncUF32::class)
                opMapEntry("i64.trunc_s/f64", ConvertOp::NoArg, Instr.I64TruncSF64, Instr.I64TruncSF64::class)
                opMapEntry("i64.trunc_u/f64", ConvertOp::NoArg, Instr.I64TruncUF64, Instr.I64TruncUF64::class)
                opMapEntry("f32.convert_s/i32", ConvertOp::NoArg, Instr.F32ConvertSI32, Instr.F32ConvertSI32::class)
                opMapEntry("f32.convert_u/i32", ConvertOp::NoArg, Instr.F32ConvertUI32, Instr.F32ConvertUI32::class)
                opMapEntry("f32.convert_s/i64", ConvertOp::NoArg, Instr.F32ConvertSI64, Instr.F32ConvertSI64::class)
                opMapEntry("f32.convert_u/i64", ConvertOp::NoArg, Instr.F32ConvertUI64, Instr.F32ConvertUI64::class)
                opMapEntry("f32.demote/f64", ConvertOp::NoArg, Instr.F32DemoteF64, Instr.F32DemoteF64::class)
                opMapEntry("f64.convert_s/i32", ConvertOp::NoArg, Instr.F64ConvertSI32, Instr.F64ConvertSI32::class)
                opMapEntry("f64.convert_u/i32", ConvertOp::NoArg, Instr.F64ConvertUI32, Instr.F64ConvertUI32::class)
                opMapEntry("f64.convert_s/i64", ConvertOp::NoArg, Instr.F64ConvertSI64, Instr.F64ConvertSI64::class)
                opMapEntry("f64.convert_u/i64", ConvertOp::NoArg, Instr.F64ConvertUI64, Instr.F64ConvertUI64::class)
                opMapEntry("f64.promote/f32", ConvertOp::NoArg, Instr.F64PromoteF32, Instr.F64PromoteF32::class)

                opMapEntry("i32.reinterpret/f32", ReinterpretOp::NoArg, Instr.I32ReinterpretF32, Instr.I32ReinterpretF32::class)
                opMapEntry("i64.reinterpret/f64", ReinterpretOp::NoArg, Instr.I64ReinterpretF64, Instr.I64ReinterpretF64::class)
                opMapEntry("f32.reinterpret/i32", ReinterpretOp::NoArg, Instr.F32ReinterpretI32, Instr.F32ReinterpretI32::class)
                opMapEntry("f64.reinterpret/i64", ReinterpretOp::NoArg, Instr.F64ReinterpretI64, Instr.F64ReinterpretI64::class)
            }
        }
    }
}
