package asmble.compile.jvm.msplit;


import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

import static asmble.compile.jvm.msplit.Util.*;

/** Splits a method into two */
public class SplitMethod {

  protected final int api;

  /** @param api Same as for {@link org.objectweb.asm.MethodVisitor#MethodVisitor(int)} or any other ASM class */
  public SplitMethod(int api) { this.api = api; }

  /**
   * Calls {@link #split(String, MethodNode, int, int, int)} with minSize as 20% + 1 of the original, maxSize as
   * 70% + 1 of the original, and firstAtLeast as maxSize. The original method is never modified and the result can
   * be null if no split points are found.
   */
  public Result split(String owner, MethodNode method) {
    // Between 20% + 1 and 70% + 1 of size
    int insnCount = method.instructions.size();
    int minSize = (int) (insnCount * 0.2) + 1;
    int maxSize = (int) (insnCount * 0.7) + 1;
    return split(owner, method, minSize, maxSize, maxSize);
  }

  /**
   * Splits the given method into two. This uses a {@link Splitter} to consistently create
   * {@link asmble.compile.jvm.msplit.Splitter.SplitPoint}s until one reaches firstAtLeast or the largest otherwise, and then calls
   * {@link #fromSplitPoint(String, MethodNode, Splitter.SplitPoint)}.
   *
   * @param owner The internal name of the owning class. Needed when splitting to call the split off method.
   * @param method The method to split, never modified
   * @param minSize The minimum number of instructions the split off method must have
   * @param maxSize The maximum number of instructions the split off method can have
   * @param firstAtLeast The number of instructions that, when first reached, will immediately be used without
   *                     continuing. Since split points are streamed, this allows splitting without waiting to
   *                     find the largest overall. If this is &lt= 0, it will not apply and all split points will be
   *                     checked to find the largest before doing the split.
   * @return The resulting split method or null if there were no split points found
   */
  public Result split(String owner, MethodNode method, int minSize, int maxSize, int firstAtLeast) {
    // Get the largest split point
    Splitter.SplitPoint largest = null;
    for (Splitter.SplitPoint point : new Splitter(api, owner, method, minSize, maxSize)) {
      if (largest == null || point.length > largest.length) {
        largest = point;
        // Early exit?
        if (firstAtLeast > 0 && largest.length >= firstAtLeast) break;
      }
    }
    if (largest == null) return null;
    return fromSplitPoint(owner, method, largest);
  }

  /**
   * Split the given method at the given split point. Called by {@link #split(String, MethodNode, int, int, int)}. The
   * original method is never modified.
   */
  public Result fromSplitPoint(String owner, MethodNode orig, Splitter.SplitPoint splitPoint) {
    MethodNode splitOff = createSplitOffMethod(orig, splitPoint);
    MethodNode trimmed = createTrimmedMethod(owner, orig, splitOff, splitPoint);
    return new Result(trimmed, splitOff);
  }

  protected MethodNode createSplitOffMethod(MethodNode orig, Splitter.SplitPoint splitPoint) {
    // The new method is a static synthetic method named method.name + "$split" that returns an object array
    // Key is previous local index, value is new local index
    Map<Integer, Integer> localsMap = new HashMap<>();
    // The new method's parameters are all stack items + all read locals
    List<Type> args = new ArrayList<>(splitPoint.neededFromStackAtStart);
    splitPoint.localsRead.forEach((index, type) -> {
      args.add(type);
      localsMap.put(index, args.size() - 1);
    });
    // Create the new method
    String name = orig.name.replace("<", "__").replace(">", "__") + "$split";
    MethodNode newMethod = new MethodNode(api,
        Opcodes.ACC_STATIC + Opcodes.ACC_PRIVATE + Opcodes.ACC_SYNTHETIC, name,
        Type.getMethodDescriptor(Type.getType(Object[].class), args.toArray(new Type[0])), null, null);
    // Add the written locals to the map that are not already there
    int newLocalIndex = args.size();
    for (Integer key : splitPoint.localsWritten.keySet()) {
      if (!localsMap.containsKey(key)) {
        localsMap.put(key, newLocalIndex);
        newLocalIndex++;
      }
    }
    // First set of instructions is pushing the new stack from the params
    for (int i = 0; i < splitPoint.neededFromStackAtStart.size(); i++) {
      Type item = splitPoint.neededFromStackAtStart.get(i);
      newMethod.visitVarInsn(loadOpFromType(item), i);
    }
    // Next set of instructions comes verbatim from the original, but we have to change the local indexes
    Set<Label> seenLabels = new HashSet<>();
    for (int i = 0; i < splitPoint.length; i++) {
      AbstractInsnNode insn = orig.instructions.get(i + splitPoint.start);
      // Skip frames
      if (insn instanceof FrameNode) continue;
      // Store the label
      if (insn instanceof LabelNode) seenLabels.add(((LabelNode) insn).getLabel());
      // Change the local if needed
      if (insn instanceof VarInsnNode) {
        insn = insn.clone(Collections.emptyMap());
        ((VarInsnNode) insn).var = localsMap.get(((VarInsnNode) insn).var);
      } else if (insn instanceof IincInsnNode) {
        insn = insn.clone(Collections.emptyMap());
        ((VarInsnNode) insn).var = localsMap.get(((VarInsnNode) insn).var);
      }
      insn.accept(newMethod);
    }
    // Final set of instructions is an object array of stack to set and then locals written
    // Create the object array
    int retArrSize = splitPoint.putOnStackAtEnd.size() + splitPoint.localsWritten.size();
    intConst(retArrSize).accept(newMethod);
    newMethod.visitTypeInsn(Opcodes.ANEWARRAY, OBJECT_TYPE.getInternalName());
    // So, we're going to store the arr in the next avail local
    int retArrLocalIndex = newLocalIndex;
    newMethod.visitVarInsn(Opcodes.ASTORE, retArrLocalIndex);
    // Now go over each stack item and load the arr, swap w/ the stack, add the index, swap with the stack, and store
    for (int i = splitPoint.putOnStackAtEnd.size() - 1; i >= 0; i--) {
      Type item = splitPoint.putOnStackAtEnd.get(i);
      // Box the item on the stack if necessary
      boxStackIfNecessary(item, newMethod);
      // Load the array
      newMethod.visitVarInsn(Opcodes.ALOAD, retArrLocalIndex);
      // Swap to put stack back on top
      newMethod.visitInsn(Opcodes.SWAP);
      // Add the index
      intConst(i).accept(newMethod);
      // Swap to put the stack value back on top
      newMethod.visitInsn(Opcodes.SWAP);
      // Now that we have arr, index, value, we can store in the array
      newMethod.visitInsn(Opcodes.AASTORE);
    }
    // Do the same with written locals
    int currIndex = splitPoint.putOnStackAtEnd.size();
    for (Integer index : splitPoint.localsWritten.keySet()) {
      Type item = splitPoint.localsWritten.get(index);
      // Load the array
      newMethod.visitVarInsn(Opcodes.ALOAD, retArrLocalIndex);
      // Add the arr index
      intConst(currIndex).accept(newMethod);
      currIndex++;
      // Load the var
      newMethod.visitVarInsn(loadOpFromType(item), localsMap.get(index));
      // Box it if necessary
      boxStackIfNecessary(item, newMethod);
      // Store in array
      newMethod.visitInsn(Opcodes.AASTORE);
    }
    // Load the array out and return it
    newMethod.visitVarInsn(Opcodes.ALOAD, retArrLocalIndex);
    newMethod.visitInsn(Opcodes.ARETURN);
    // Any try catch blocks that start in here
    for (TryCatchBlockNode tryCatch : orig.tryCatchBlocks) {
      if (seenLabels.contains(tryCatch.start.getLabel())) tryCatch.accept(newMethod);
    }
    // Reset the labels
    newMethod.instructions.resetLabels();
    return newMethod;
  }

  protected MethodNode createTrimmedMethod(String owner, MethodNode orig,
      MethodNode splitOff, Splitter.SplitPoint splitPoint) {
    // The trimmed method is the same as the original, yet the split area is replaced with a call to the split off
    // portion. Before calling the split-off, we have to add locals to the stack part. Then afterwards, we have to
    // replace the stack and written locals.
    // Effectively clone the orig
    MethodNode newMethod = new MethodNode(api, orig.access, orig.name, orig.desc,
        orig.signature, orig.exceptions.toArray(new String[0]));
    orig.accept(newMethod);
    // Remove all insns, we'll re-add the ones outside the split range
    newMethod.instructions.clear();
    // Remove all try catch blocks and keep track of seen labels, we'll re-add them at the end
    newMethod.tryCatchBlocks.clear();
    Set<Label> seenLabels = new HashSet<>();
    // Also keep track of the locals that have been stored, need to know
    Set<Integer> seenStoredLocals = new HashSet<>();
    int paramOffset = 0;
    // If this is an instance method, we consider "0" (i.e. "this") as seen
    if ((orig.access & Opcodes.ACC_STATIC) == 0) {
      seenStoredLocals.add(0);
      paramOffset = 1;
    }
    // We also consider parameters as seen
    int paramCount = Type.getArgumentTypes(orig.desc).length;
    for (int i = 0; i < paramCount; i++) seenStoredLocals.add(i + paramOffset);
    // Add the insns before split
    for (int i = 0; i < splitPoint.start; i++) {
      AbstractInsnNode insn = orig.instructions.get(i);
      // Skip frames
      if (insn instanceof FrameNode) continue;
      // Record label
      if (insn instanceof LabelNode) seenLabels.add(((LabelNode) insn).getLabel());
      // Check a local store has happened
      if (insn instanceof VarInsnNode && isStoreOp(insn.getOpcode())) seenStoredLocals.add(((VarInsnNode) insn).var);
      insn.accept(newMethod);
    }
    // Push all the read locals on the stack
    splitPoint.localsRead.forEach((index, type) -> {
      // We've seen a store for this, so just load it, otherwise use a zero val
      // TODO: safe? if not, maybe just put at the top of the method a bunch of defaulted locals?
      if (seenStoredLocals.contains(index)) newMethod.visitVarInsn(loadOpFromType(type), index);
      else zeroVal(type).accept(newMethod);
    });
    // Invoke the split off method
    newMethod.visitMethodInsn(Opcodes.INVOKESTATIC, owner, splitOff.name, splitOff.desc, false);
    // Now the object array is on the stack which contains stack pieces + written locals
    // Take off the locals
    int localArrIndex = splitPoint.putOnStackAtEnd.size();
    for (Integer index : splitPoint.localsWritten.keySet()) {
      // Dupe the array
      newMethod.visitInsn(Opcodes.DUP);
      // Put the index on the stack
      intConst(localArrIndex).accept(newMethod);
      localArrIndex++;
      // Load the written local
      Type item = splitPoint.localsWritten.get(index);
      newMethod.visitInsn(Opcodes.AALOAD);
      // Cast to local type
      if (!item.equals(OBJECT_TYPE)) {
        newMethod.visitTypeInsn(Opcodes.CHECKCAST, boxedTypeIfNecessary(item).getInternalName());
      }
      // Unbox if necessary
      unboxStackIfNecessary(item, newMethod);
      // Store in the local
      newMethod.visitVarInsn(storeOpFromType(item), index);
    }
    // Now just load up the stack
    for (int i = 0; i < splitPoint.putOnStackAtEnd.size(); i++) {
      boolean last = i == splitPoint.putOnStackAtEnd.size() - 1;
      // Since the loop started with the array, we only dupe the array every time but the last
      if (!last) newMethod.visitInsn(Opcodes.DUP);
      // Put the index on the stack
      intConst(i).accept(newMethod);
      // Load the stack item
      Type item = splitPoint.putOnStackAtEnd.get(i);
      newMethod.visitInsn(Opcodes.AALOAD);
      // Cast to local type
      if (!item.equals(OBJECT_TYPE)) {
        newMethod.visitTypeInsn(Opcodes.CHECKCAST, boxedTypeIfNecessary(item).getInternalName());
      }
      // Unbox if necessary
      unboxStackIfNecessary(item, newMethod);
      // For all but the last stack item, we need to swap with the arr ref above.
      if (!last) {
        // Note if the stack item takes two slots, we do a form of dup then pop since there's no swap1x2
        if (item == Type.LONG_TYPE || item == Type.DOUBLE_TYPE) {
          newMethod.visitInsn(Opcodes.DUP_X2);
          newMethod.visitInsn(Opcodes.POP);
        } else {
          newMethod.visitInsn(Opcodes.SWAP);
        }
      }
    }
    // Now we have restored all locals and all stack...add the rest of the insns after the split
    for (int i = splitPoint.start + splitPoint.length; i < orig.instructions.size(); i++) {
      AbstractInsnNode insn = orig.instructions.get(i);
      // Skip frames
      if (insn instanceof FrameNode) continue;
      // Record label
      if (insn instanceof LabelNode) seenLabels.add(((LabelNode) insn).getLabel());
      insn.accept(newMethod);
    }
    // Add any try catch blocks that started in here
    for (TryCatchBlockNode tryCatch : orig.tryCatchBlocks) {
      if (seenLabels.contains(tryCatch.start.getLabel())) tryCatch.accept(newMethod);
    }
    // Reset the labels
    newMethod.instructions.resetLabels();
    return newMethod;
  }

  /** Result of a split method */
  public static class Result {
    /** A copy of the original method, but changed to invoke {@link #splitOffMethod} */
    public final MethodNode trimmedMethod;
    /** The new method that was split off the original and is called by {@link #splitOffMethod} */
    public final MethodNode splitOffMethod;

    public Result(MethodNode trimmedMethod, MethodNode splitOffMethod) {
      this.trimmedMethod = trimmedMethod;
      this.splitOffMethod = splitOffMethod;
    }
  }
}
