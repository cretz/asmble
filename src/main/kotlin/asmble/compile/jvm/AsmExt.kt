package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.reflect

val <R> KFunction<R>.asmDesc: String get() = Type.getMethodDescriptor(this.javaMethod)

val <R> KFunction<R>.declarer: Class<*> get() = this.javaMethod!!.declaringClass

fun KFunction<*>.invokeStatic() =
    MethodInsnNode(Opcodes.INVOKESTATIC, this.declarer.ref.asmName, this.name, this.asmDesc, false)

fun KFunction<*>.invokeVirtual() =
    MethodInsnNode(Opcodes.INVOKEVIRTUAL, this.declarer.ref.asmName, this.name, this.asmDesc, false)

inline fun <T> forceType(fn: T) = fn
inline fun <T : Function<*>> forceFnType(fn: T) = fn.reflect()!!

val KClass<*>.ref: TypeRef get() = this.java.ref

fun <T : Exception> KClass<T>.athrow(msg: String) = listOf(
    TypeInsnNode(Opcodes.NEW, this.ref.asmName),
    InsnNode(Opcodes.DUP),
    msg.const,
    MethodInsnNode(Opcodes.INVOKESPECIAL, this.ref.asmName, "<init>",
        Void::class.ref.asMethodRetDesc(String::class.ref), false)
)

val Class<*>.ref: TypeRef get() = TypeRef(Type.getType(this))

val KProperty<*>.declarer: Class<*> get() = this.javaField!!.declaringClass
val KProperty<*>.asmDesc: String get() = Type.getDescriptor(this.javaField!!.type)

fun KProperty<*>.getStatic() =
    FieldInsnNode(Opcodes.GETSTATIC, this.declarer.ref.asmDesc, this.name, this.asmDesc)

val Int.const: AbstractInsnNode get() = when (this) {
    -1 -> InsnNode(Opcodes.ICONST_M1)
    0 -> InsnNode(Opcodes.ICONST_0)
    1 -> InsnNode(Opcodes.ICONST_1)
    2 -> InsnNode(Opcodes.ICONST_2)
    3 -> InsnNode(Opcodes.ICONST_3)
    4 -> InsnNode(Opcodes.ICONST_4)
    5 -> InsnNode(Opcodes.ICONST_5)
    in 6..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, this)
    in (Byte.MAX_VALUE + 1)..Short.MAX_VALUE -> IntInsnNode(Opcodes.SIPUSH, this)
    else -> LdcInsnNode(this)
}

fun Int.isAccess(access: Int) = (this and access) == access
val Int.isAccessStatic: Boolean get() = this.isAccess(Opcodes.ACC_STATIC)

val String.const: AbstractInsnNode get() = LdcInsnNode(this)

val Node.Type.Value.kclass: KClass<*> get() = when (this) {
    Node.Type.Value.I32 -> Int::class
    Node.Type.Value.I64 -> Long::class
    Node.Type.Value.F32 -> Float::class
    Node.Type.Value.F64 -> Double::class
}

val Node.Type.Value.typeRef: TypeRef get() = this.jclass.ref
val Node.Type.Value.jclass: Class<*> get() = this.kclass.java

val AbstractInsnNode.isTerminating: Boolean get() = when (this.opcode) {
    Opcodes.ARETURN, Opcodes.ATHROW, Opcodes.DRETURN, Opcodes.FRETURN,
        Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.RETURN -> true
    else -> false
}

val Node.Type.Func.asmDesc: String get() =
    (this.ret?.typeRef ?: Void::class.ref).asMethodRetDesc(*this.params.map { it.typeRef }.toTypedArray())

fun Node.Func.actualLocalIndex(givenIndex: Int) =
    this.locals.take(givenIndex).sumBy { if (it == Node.Type.Value.I64 || it == Node.Type.Value.F64) 2 else 1 }