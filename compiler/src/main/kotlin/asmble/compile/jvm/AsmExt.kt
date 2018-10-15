package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod

val <R> KFunction<R>.asmDesc: String get() = Type.getMethodDescriptor(this.javaMethod)

val <R> KFunction<R>.declarer: Class<*> get() = this.javaMethod!!.declaringClass

fun KFunction<*>.invokeStatic() =
    MethodInsnNode(Opcodes.INVOKESTATIC, this.declarer.ref.asmName, this.name, this.asmDesc, false)

fun KFunction<*>.invokeVirtual() =
    MethodInsnNode(Opcodes.INVOKEVIRTUAL, this.declarer.ref.asmName, this.name, this.asmDesc, false)

@Suppress("NOTHING_TO_INLINE")
inline fun <T : Function<*>> forceFnType(fn: T) = fn as KFunction<*>

val KClass<*>.const: LdcInsnNode get() = (if (this == Void::class) Void.TYPE else this.java).const
val KClass<*>.asmType: Type get() = (if (this == Void::class) Void.TYPE else this.java).asmType
val KClass<*>.ref: TypeRef get() = (if (this == Void::class) Void.TYPE else this.java).ref

fun <T : Exception> KClass<T>.athrow(msg: String) = listOf(
    TypeInsnNode(Opcodes.NEW, this.ref.asmName),
    InsnNode(Opcodes.DUP),
    msg.const,
    MethodInsnNode(Opcodes.INVOKESPECIAL, this.ref.asmName, "<init>",
        Void::class.ref.asMethodRetDesc(String::class.ref), false),
    InsnNode(Opcodes.ATHROW)
)

// Ug: https://youtrack.jetbrains.com/issue/KT-17064
fun KClass<*>.invokeStatic(name: String, retType: KClass<*>, vararg params: KClass<*>) =
    MethodInsnNode(Opcodes.INVOKESTATIC, this.javaObjectType.ref.asmName, name,
        retType.ref.asMethodRetDesc(*params.map { it.ref }.toTypedArray()), false)

val Class<*>.const: LdcInsnNode get() = LdcInsnNode(this)

val Class<*>.asmType: Type get() = Type.getType(this)

val Class<*>.ref: TypeRef get() = TypeRef(this.asmType)

val Class<*>.valueType: Node.Type.Value? get() = when (this) {
    Void.TYPE -> null
    Int::class.java, java.lang.Integer::class.java -> Node.Type.Value.I32
    Long::class.java, java.lang.Long::class.java -> Node.Type.Value.I64
    Float::class.java, java.lang.Float::class.java -> Node.Type.Value.F32
    Double::class.java, java.lang.Double::class.java -> Node.Type.Value.F64
    else -> error("Unrecognized value type class: $this")
}

val Executable.ref: TypeRef get() = when (this) {
    is Method -> TypeRef(Type.getType(this))
    is Constructor<*> -> TypeRef(Type.getType(this))
    else -> error("Unknown executable $this")
}


val KProperty<*>.declarer: Class<*> get() = this.javaField!!.declaringClass
val KProperty<*>.asmDesc: String get() = Type.getDescriptor(this.javaField!!.type)

fun KProperty<*>.getStatic() =
    FieldInsnNode(Opcodes.GETSTATIC, this.declarer.ref.asmName, this.name, this.asmDesc)

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

val Long.const: AbstractInsnNode get() = when (this) {
    0L -> InsnNode(Opcodes.LCONST_0)
    1L -> InsnNode(Opcodes.LCONST_1)
    else -> LdcInsnNode(this)
}

val Float.const: AbstractInsnNode get() = when (this) {
    // Ref: https://discuss.kotlinlang.org/t/when-isnt-equals/2452
    0F -> if (this.equals(-0.0f)) LdcInsnNode(this) else InsnNode(Opcodes.FCONST_0)
    1F -> InsnNode(Opcodes.FCONST_1)
    2F -> InsnNode(Opcodes.FCONST_2)
    else -> LdcInsnNode(this)
}

val Double.const: AbstractInsnNode get() = when (this) {
    // Ref: https://discuss.kotlinlang.org/t/when-isnt-equals/2452
    0.0 -> if (this.equals(-0.0)) LdcInsnNode(this) else InsnNode(Opcodes.DCONST_0)
    1.0 -> InsnNode(Opcodes.DCONST_1)
    else -> LdcInsnNode(this)
}

val Number?.valueType get() = when (this) {
    null -> null
    is Int -> Node.Type.Value.I32
    is Long-> Node.Type.Value.I64
    is Float -> Node.Type.Value.F32
    is Double -> Node.Type.Value.F64
    else -> error("Unrecognized value type class: $this")
}

val String.const: AbstractInsnNode get() = LdcInsnNode(this)

val javaKeywords = setOf("abstract", "assert", "boolean",
    "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "extends", "false",
    "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native",
    "new", "null", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch",
    "synchronized", "this", "throw", "throws", "transient", "true",
    "try", "void", "volatile", "while")

val String.javaIdent: String get() {
    // What we're going to do is:
    // * If it's empty, becomes $empty$
    // * If it's a java keyword, add "wasm$" prefix
    // * If it doesn't start with a valid java ident start, add "wasm$" prefix
    // * All other invalid chars become $num$ where "num" is the int char value
    return if (this.isEmpty()) "\$empty\$"
        else if (javaKeywords.contains(this)) "wasm\$$this"
        else {
            (if (this.first().isJavaIdentifierStart()) this else "wasm\$$this").
                fold(StringBuilder()) { builder, char ->
                    if (char.isJavaIdentifierPart()) builder.append(char)
                    else builder.append('_').append(char.toInt())
                }.toString()
        }
}

fun Node.Func.localByIndex(index: Int) =
    this.type.params.getOrNull(index) ?:
        this.locals.getOrNull(index - this.type.params.size) ?:
            throw CompileErr.UnknownLocal(index)
fun Node.Func.actualLocalIndex(givenIndex: Int) =
    // Add 1 for "this"
    (this.type.params + this.locals).take(givenIndex).sumBy { it.typeRef.stackSize } + 1
val Node.Func.localsSize: Int get() = this.type.params.size + this.locals.size

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

val AbstractInsnNode.isUnconditionalJump: Boolean get() = when (this.opcode) {
    Opcodes.GOTO, Opcodes.JSR -> true
    else -> false
}

fun MethodNode.addInsns(vararg insn: AbstractInsnNode): MethodNode {
    insn.forEach(this.instructions::add)
    return this
}

fun MethodNode.cloneWithInsnRange(range: IntRange) =
    MethodNode(access, name, desc, signature, exceptions.toTypedArray()).also { new ->
        accept(new)
        val indexesToRemove = (0 until range.start) + ((range.endInclusive + 1) until new.instructions.size())
        // Remove em all in reverse
        indexesToRemove.asReversed().forEach { new.instructions.remove(new.instructions[it]) }
    }

fun MethodNode.toAsmString(): String {
    val stringWriter = StringWriter()
    val cv = TraceClassVisitor(PrintWriter(stringWriter))
    this.accept(cv)
    cv.p.print(PrintWriter(stringWriter))
    return stringWriter.toString()
}

val Node.Type.Func.asmDesc: String get() =
    (this.ret?.typeRef ?: Void::class.ref).asMethodRetDesc(*this.params.map { it.typeRef }.toTypedArray())

fun ClassNode.toAsmString(): String {
    val stringWriter = StringWriter()
    this.accept(TraceClassVisitor(PrintWriter(stringWriter)))
    return stringWriter.toString()
}

fun ByteArray.asClassNode(): ClassNode {
    val newNode = ClassNode()
    ClassReader(this).accept(newNode, 0)
    return newNode
}

fun ByteArray.chunked(v: Int) = (0 until size step v).asSequence().map {
    copyOfRange(it, (it + v).takeIf { it < size } ?: size)
}