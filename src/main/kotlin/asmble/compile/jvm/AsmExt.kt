package asmble.compile.jvm

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
    MethodInsnNode(Opcodes.INVOKESTATIC, this.declarer.asmName, this.name, this.asmDesc, false)

fun KFunction<*>.invokeVirtual() =
    MethodInsnNode(Opcodes.INVOKEVIRTUAL, this.declarer.asmName, this.name, this.asmDesc, false)

inline fun <T> forceType(fn: T) = fn
inline fun <T : Function<*>> forceFnType(fn: T) = fn.reflect()!!

val <T : Any> KClass<T>.asmName: String get() = this.java.asmName

val <T : Any> Class<T>.asmName: String get() = Type.getInternalName(this)

val KProperty<*>.declarer: Class<*> get() = this.javaField!!.declaringClass
val KProperty<*>.asmDesc: String get() = Type.getDescriptor(this.javaField!!.type)

fun KProperty<*>.getStatic() =
    FieldInsnNode(Opcodes.GETSTATIC, this.declarer.asmName, this.name, this.asmDesc)

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
