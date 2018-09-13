package asmble.compile.jvm.msplit;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

class Util {
  private Util() { }

  static final Type OBJECT_TYPE = Type.getType(Object.class);

  static AbstractInsnNode zeroVal(Type type) {
    if (type == Type.INT_TYPE) return new InsnNode(Opcodes.ICONST_0);
    else if (type == Type.LONG_TYPE) return new InsnNode(Opcodes.LCONST_0);
    else if (type == Type.FLOAT_TYPE) return new InsnNode(Opcodes.FCONST_0);
    else if (type == Type.DOUBLE_TYPE) return new InsnNode(Opcodes.DCONST_0);
    else return new InsnNode(Opcodes.ACONST_NULL);
  }

  static boolean isStoreOp(int opcode) {
    return opcode == Opcodes.ISTORE || opcode == Opcodes.LSTORE || opcode == Opcodes.FSTORE ||
        opcode == Opcodes.DSTORE || opcode == Opcodes.ASTORE;
  }

  static int storeOpFromType(Type type) {
    if (type == Type.INT_TYPE) return Opcodes.ISTORE;
    else if (type == Type.LONG_TYPE) return Opcodes.LSTORE;
    else if (type == Type.FLOAT_TYPE) return Opcodes.FSTORE;
    else if (type == Type.DOUBLE_TYPE) return Opcodes.DSTORE;
    else return Opcodes.ASTORE;
  }

  static int loadOpFromType(Type type) {
    if (type == Type.INT_TYPE) return Opcodes.ILOAD;
    else if (type == Type.LONG_TYPE) return Opcodes.LLOAD;
    else if (type == Type.FLOAT_TYPE) return Opcodes.FLOAD;
    else if (type == Type.DOUBLE_TYPE) return Opcodes.DLOAD;
    else return Opcodes.ALOAD;
  }

  static Type boxedTypeIfNecessary(Type type) {
    if (type == Type.INT_TYPE) return Type.getType(Integer.class);
    else if (type == Type.LONG_TYPE) return Type.getType(Long.class);
    else if (type == Type.FLOAT_TYPE) return Type.getType(Float.class);
    else if (type == Type.DOUBLE_TYPE) return Type.getType(Double.class);
    else return type;
  }

  static void boxStackIfNecessary(Type type, MethodNode method) {
    if (type == Type.INT_TYPE) boxCall(Integer.class, type).accept(method);
    else if (type == Type.FLOAT_TYPE) boxCall(Float.class, type).accept(method);
    else if (type == Type.LONG_TYPE) boxCall(Long.class, type).accept(method);
    else if (type == Type.DOUBLE_TYPE) boxCall(Double.class, type).accept(method);
  }

  static void unboxStackIfNecessary(Type type, MethodNode method) {
    if (type == Type.INT_TYPE) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
        "java/lang/Integer", "intValue", Type.getMethodDescriptor(Type.INT_TYPE), false);
    else if (type == Type.FLOAT_TYPE) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
        "java/lang/Float", "floatValue", Type.getMethodDescriptor(Type.FLOAT_TYPE), false);
    else if (type == Type.LONG_TYPE) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
        "java/lang/Long", "longValue", Type.getMethodDescriptor(Type.LONG_TYPE), false);
    else if (type == Type.DOUBLE_TYPE) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
        "java/lang/Double", "doubleValue", Type.getMethodDescriptor(Type.DOUBLE_TYPE), false);
  }

  static AbstractInsnNode intConst(int v) {
    switch (v) {
      case -1: return new InsnNode(Opcodes.ICONST_M1);
      case 0: return new InsnNode(Opcodes.ICONST_0);
      case 1: return new InsnNode(Opcodes.ICONST_1);
      case 2: return new InsnNode(Opcodes.ICONST_2);
      case 3: return new InsnNode(Opcodes.ICONST_3);
      case 4: return new InsnNode(Opcodes.ICONST_4);
      case 5: return new InsnNode(Opcodes.ICONST_5);
      default: return new LdcInsnNode(v);
    }
  }

  static MethodInsnNode boxCall(Class<?> boxType, Type primType) {
    return new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(boxType),
        "valueOf", Type.getMethodDescriptor(Type.getType(boxType), primType), false);
  }
}
