package asmble.compile.jvm

import org.objectweb.asm.Type

data class TypeRef(val asm: Type) {
    val asmName: String get() = asm.internalName
    val asmDesc: String get() = asm.descriptor

    fun asMethodRetDesc(vararg args: TypeRef) = Type.getMethodDescriptor(asm, *args.map { it.asm }.toTypedArray())

    val stackSize: Int get() = if (asm == Type.DOUBLE_TYPE || asm == Type.LONG_TYPE) 2 else 1

    fun equivalentTo(other: TypeRef) = this == other || this == Unknown || other == Unknown

    object UnknownType

    companion object {
        val Unknown = UnknownType::class.ref
    }
}