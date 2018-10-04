package asmble.compile.jvm.msplit;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.*;

import java.util.*;

import static asmble.compile.jvm.msplit.Util.*;

/** For a given method, iterate over possible split points */
public class Splitter implements Iterable<Splitter.SplitPoint> {
  protected final int api;
  protected final String owner;
  protected final MethodNode method;
  protected final int minSize;
  protected final int maxSize;

  /**
   * @param api Same as for {@link org.objectweb.asm.MethodVisitor#MethodVisitor(int)} or any other ASM class
   * @param owner Internal name of the method's owner
   * @param method The method to find split points for
   * @param minSize The minimum number of instructions required for the split point to be valid
   * @param maxSize The maximum number of instructions that split points cannot exceeed
   */
  public Splitter(int api, String owner, MethodNode method, int minSize, int maxSize) {
    this.api = api;
    this.owner = owner;
    this.method = method;
    this.minSize = minSize;
    this.maxSize = maxSize;
  }

  @Override
  public Iterator<SplitPoint> iterator() { return new Iter(); }

  // Types are always int, float, long, double, or ref (no other primitives)
  /** A split point in a method that can be split off into another method */
  public static class SplitPoint {
    /**
     * The locals read in this split area, keyed by index. Value type is always int, float, long, double, or object.
     */
    public final SortedMap<Integer, Type> localsRead;
    /**
     * The locals written in this split area, keyed by index. Value type is always int, float, long, double, or object.
     */
    public final SortedMap<Integer, Type> localsWritten;
    /**
     * The values of the stack needed at the start of this split area. Type is always int, float, long, double, or
     * object.
     */
    public final List<Type> neededFromStackAtStart;
    /**
     * The values of the stack at the end of this split area that are needed to put back on the original. Type is always
     * int, float, long, double, or object.
     */
    public final List<Type> putOnStackAtEnd;
    /**
     * The instruction index this split area begins at.
     */
    public final int start;
    /**
     * The number of instructions this split area has.
     */
    public final int length;

    public SplitPoint(SortedMap<Integer, Type> localsRead, SortedMap<Integer, Type>localsWritten,
        List<Type> neededFromStackAtStart, List<Type> putOnStackAtEnd, int start, int length) {
      this.localsRead = localsRead;
      this.localsWritten = localsWritten;
      this.neededFromStackAtStart = neededFromStackAtStart;
      this.putOnStackAtEnd = putOnStackAtEnd;
      this.start = start;
      this.length = length;
    }
  }

  protected int compareInsnIndexes(AbstractInsnNode o1, AbstractInsnNode o2) {
    return Integer.compare(method.instructions.indexOf(o1), method.instructions.indexOf(o2));
  }

  protected class Iter implements Iterator<SplitPoint> {
    protected final AbstractInsnNode[] insns;
    protected final List<TryCatchBlockNode> tryCatchBlocks;
    protected int currIndex = -1;
    protected boolean peeked;
    protected SplitPoint peekedValue;

    protected Iter() {
      insns = method.instructions.toArray();
      tryCatchBlocks = new ArrayList<>(method.tryCatchBlocks);
      // Must be sorted by earliest starting index then earliest end index then earliest handler
      tryCatchBlocks.sort((o1, o2) -> {
        int cmp = compareInsnIndexes(o1.start, o2.start);
        if (cmp == 0) compareInsnIndexes(o1.end, o2.end);
        if (cmp == 0) compareInsnIndexes(o1.handler, o2.handler);
        return cmp;
      });
    }

    @Override
    public boolean hasNext() {
      if (!peeked) {
        peeked = true;
        peekedValue = nextOrNull();
      }
      return peekedValue != null;
    }

    @Override
    public SplitPoint next() {
      // If we've peeked in hasNext, use that
      SplitPoint ret;
      if (peeked) {
        peeked = false;
        ret = peekedValue;
      } else {
        ret = nextOrNull();
      }
      if (ret == null) throw new NoSuchElementException();
      return ret;
    }

    protected SplitPoint nextOrNull() {
      // Try for each index
      while (++currIndex + minSize <= insns.length) {
        SplitPoint longest = longestForCurrIndex();
        if (longest != null) return longest;
      }
      return null;
    }

    protected SplitPoint longestForCurrIndex() {
      // As a special case, if the previous insn was a line number, that was good enough
      if (currIndex - 1 >- 0 && insns[currIndex - 1] instanceof LineNumberNode) return null;
      // Build the info object
      InsnTraverseInfo info = new InsnTraverseInfo();
      info.startIndex = currIndex;
      info.endIndex = Math.min(currIndex + maxSize - 1, insns.length - 1);
      // Reduce the end by special calls
      constrainEndByInvokeSpecial(info);
      // Reduce the end based on try/catch blocks the start is in or that jump to
      constrainEndByTryCatchBlocks(info);
      // Reduce the end based on any jumps within
      constrainEndByInternalJumps(info);
      // Reduce the end based on any jumps into
      constrainEndByExternalJumps(info);
      // Make sure we didn't reduce the end too far
      if (info.getSize() < minSize) return null;
      // Now that we have our largest range from the start index, we can go over each updating the local refs and stack
      // For the stack, we are going to use the
      return splitPointFromInfo(info);
    }

    protected void constrainEndByInvokeSpecial(InsnTraverseInfo info) {
      // Can't have an invoke special of <init>
      for (int i = info.startIndex; i <= info.endIndex; i++) {
        AbstractInsnNode node = insns[i];
        if (node.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) node).name.equals("<init>")) {
          info.endIndex = Math.max(info.startIndex, i - 1);
          return;
        }
      }
    }

    protected void constrainEndByTryCatchBlocks(InsnTraverseInfo info) {
      // Go over all the try/catch blocks, sorted by earliest
      for (TryCatchBlockNode block : tryCatchBlocks) {
        int handleIndex = method.instructions.indexOf(block.handler);
        int startIndex = method.instructions.indexOf(block.start);
        int endIndex = method.instructions.indexOf(block.end) - 1;
        boolean catchWithinDisallowed;

        if (info.startIndex <= startIndex && info.endIndex >= endIndex) {
          // The try block is entirely inside the range...
          catchWithinDisallowed = false;
          // Since it's entirely within, we need the catch handler within too
          if (handleIndex < info.startIndex || handleIndex > info.endIndex) {
            // Well, it's not within, so that means we can't include this try block at all
            info.endIndex = Math.min(info.endIndex, startIndex - 1);
          }
        } else if (info.startIndex > startIndex && info.endIndex > endIndex) {
          // The try block started before this range, but ends inside of it...
          // The end has to be changed to the block's end so it doesn't go over the boundary
          info.endIndex = Math.min(info.endIndex, endIndex);
          // The catch can't jump in here
          catchWithinDisallowed = true;
        } else if (info.startIndex <= startIndex && info.endIndex < endIndex) {
          // The try block started in this range, but ends outside of it...
          // Can't have the block then, reduce it to before the start
          info.endIndex = Math.min(info.endIndex, startIndex - 1);
          // Since we don't have the block, we can't jump in here either
          catchWithinDisallowed = true;
        } else {
          // The try block is completely outside, just restrict the catch from jumping in
          catchWithinDisallowed = true;
        }
        // If the catch is within and not allowed to be, we have to change the end to before it
        if (catchWithinDisallowed && info.startIndex <= handleIndex && info.endIndex >= handleIndex) {
          info.endIndex = Math.min(info.endIndex, handleIndex - 1);
        }
      }
    }

    protected void constrainEndByInternalJumps(InsnTraverseInfo info) {
      for (int i = info.startIndex; i <= info.endIndex; i++) {
        AbstractInsnNode node = insns[i];
        int earliestIndex;
        int furthestIndex;
        if (node instanceof JumpInsnNode) {
          earliestIndex = method.instructions.indexOf(((JumpInsnNode) node).label);
          furthestIndex = earliestIndex;
        } else if (node instanceof TableSwitchInsnNode) {
          earliestIndex = method.instructions.indexOf(((TableSwitchInsnNode) node).dflt);
          furthestIndex = earliestIndex;
          for (LabelNode label : ((TableSwitchInsnNode) node).labels) {
            int index = method.instructions.indexOf(label);
            earliestIndex = Math.min(earliestIndex, index);
            furthestIndex = Math.max(furthestIndex, index);
          }
        } else if (node instanceof LookupSwitchInsnNode) {
          earliestIndex = method.instructions.indexOf(((LookupSwitchInsnNode) node).dflt);
          furthestIndex = earliestIndex;
          for (LabelNode label : ((LookupSwitchInsnNode) node).labels) {
            int index = method.instructions.indexOf(label);
            earliestIndex = Math.min(earliestIndex, index);
            furthestIndex = Math.max(furthestIndex, index);
          }
        } else continue;
        // Stop here if any indexes are out of range, otherwise, change end
        if (earliestIndex < info.startIndex || furthestIndex > info.endIndex) {
          info.endIndex = i - 1;
          return;
        }
        info.endIndex = Math.max(info.endIndex, furthestIndex);
      }
    }

    protected void constrainEndByExternalJumps(InsnTraverseInfo info) {
      // Basically, if any external jumps jump into our range, that can't be included in the range
      for (int i = 0; i < insns.length; i++) {
        if (i >= info.startIndex && i <= info.endIndex) continue;
        AbstractInsnNode node = insns[i];
        if (node instanceof JumpInsnNode) {
          int index = method.instructions.indexOf(((JumpInsnNode) node).label);
          if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
        } else if (node instanceof TableSwitchInsnNode) {
          int index = method.instructions.indexOf(((TableSwitchInsnNode) node).dflt);
          if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          for (LabelNode label : ((TableSwitchInsnNode) node).labels) {
            index = method.instructions.indexOf(label);
            if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          }
        } else if (node instanceof LookupSwitchInsnNode) {
          int index = method.instructions.indexOf(((LookupSwitchInsnNode) node).dflt);
          if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          for (LabelNode label : ((LookupSwitchInsnNode) node).labels) {
            index = method.instructions.indexOf(label);
            if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          }
        }
      }
    }

    protected SplitPoint splitPointFromInfo(InsnTraverseInfo info) {
      // We're going to use the analyzer adapter and run it for the up until the end, a step at a time
      StackAndLocalTrackingAdapter adapter = new StackAndLocalTrackingAdapter(Splitter.this);
      // Visit all of the insns up our start.
      // XXX: I checked the source of AnalyzerAdapter to confirm I don't need any of the surrounding stuff
      for (int i = 0; i < info.startIndex; i++) insns[i].accept(adapter);
      // Take the stack at the start and copy it off
      List<Object> stackAtStart = new ArrayList<>(adapter.stack);
      // Reset some adapter state
      adapter.lowestStackSize = stackAtStart.size();
      adapter.localsRead.clear();
      adapter.localsWritten.clear();
      // Now go over the remaining range
      for (int i = info.startIndex; i <= info.endIndex; i++) insns[i].accept(adapter);
      // Build the split point
      return new SplitPoint(
          localMapFromAdapterLocalMap(adapter.localsRead, adapter.uninitializedTypes),
          localMapFromAdapterLocalMap(adapter.localsWritten, adapter.uninitializedTypes),
          typesFromAdapterStackRange(stackAtStart, adapter.lowestStackSize, adapter.uninitializedTypes),
          typesFromAdapterStackRange(adapter.stack, adapter.lowestStackSize, adapter.uninitializedTypes),
          info.startIndex,
          info.getSize()
      );
    }

    protected SortedMap<Integer, Type> localMapFromAdapterLocalMap(
        SortedMap<Integer, Object> map, Map<Object, Object> uninitializedTypes) {
      SortedMap<Integer, Type> ret = new TreeMap<>();
      map.forEach((k, v) -> ret.put(k, typeFromAdapterStackItem(v, uninitializedTypes)));
      return ret;
    }

    protected List<Type> typesFromAdapterStackRange(
        List<Object> stack, int start, Map<Object, Object> uninitializedTypes) {
      List<Type> ret = new ArrayList<>();
      for (int i = start; i < stack.size(); i++) {
        Object item = stack.get(i);
        ret.add(typeFromAdapterStackItem(item, uninitializedTypes));
        // Jump an extra spot for longs and doubles
        if (item == Opcodes.LONG || item == Opcodes.DOUBLE) {
          if (stack.get(++i) != Opcodes.TOP) throw new IllegalStateException("Expected top after long/double");
        }
      }
      return ret;
    }

    protected Type typeFromAdapterStackItem(Object item, Map<Object, Object> uninitializedTypes) {
      if (item == Opcodes.INTEGER) return Type.INT_TYPE;
      else if (item == Opcodes.FLOAT) return Type.FLOAT_TYPE;
      else if (item == Opcodes.LONG) return Type.LONG_TYPE;
      else if (item == Opcodes.DOUBLE) return Type.DOUBLE_TYPE;
      else if (item == Opcodes.NULL) return OBJECT_TYPE;
      else if (item == Opcodes.UNINITIALIZED_THIS) return Type.getObjectType(owner);
      else if (item instanceof Label) return Type.getObjectType((String) uninitializedTypes.get(item));
      else if (item instanceof String) return Type.getObjectType((String) item);
      else throw new IllegalStateException("Unrecognized stack item: " + item);
    }
  }

  protected static class StackAndLocalTrackingAdapter extends AnalyzerAdapter {
    public int lowestStackSize;
    public final SortedMap<Integer, Object> localsRead = new TreeMap<>();
    public final SortedMap<Integer, Object> localsWritten = new TreeMap<>();

    protected StackAndLocalTrackingAdapter(Splitter splitter) {
      super(splitter.api, splitter.owner, splitter.method.access, splitter.method.name, splitter.method.desc, null);
      stack = new SizeChangeNotifyList<Object>() {
        @Override
        protected void onSizeChanged() { lowestStackSize = Math.min(lowestStackSize, size()); }
      };
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
      switch (opcode) {
        case Opcodes.ILOAD:
        case Opcodes.LLOAD:
        case Opcodes.FLOAD:
        case Opcodes.DLOAD:
        case Opcodes.ALOAD:
          localsRead.put(var, locals.get(var));
          break;
        case Opcodes.ISTORE:
        case Opcodes.FSTORE:
        case Opcodes.ASTORE:
          localsWritten.put(var, stack.get(stack.size() - 1));
          break;
        case Opcodes.LSTORE:
        case Opcodes.DSTORE:
          localsWritten.put(var, stack.get(stack.size() - 2));
          break;
      }
      super.visitVarInsn(opcode, var);
    }

    @Override
    public void visitIincInsn(int var, int increment) {
      localsRead.put(var, Type.INT_TYPE);
      localsWritten.put(var, Type.INT_TYPE);
      super.visitIincInsn(var, increment);
    }
  }

  protected static class SizeChangeNotifyList<T> extends AbstractList<T> {
    protected final ArrayList<T> list = new ArrayList<>();

    protected void onSizeChanged() { }

    @Override
    public T get(int index) { return list.get(index); }

    @Override
    public int size() { return list.size(); }

    @Override
    public T set(int index, T element) { return list.set(index, element); }

    @Override
    public void add(int index, T element) {
      list.add(index, element);
      onSizeChanged();
    }

    @Override
    public T remove(int index) {
      T ret = list.remove(index);
      onSizeChanged();
      return ret;
    }
  }

  protected static class InsnTraverseInfo {
    public int startIndex;
    // Can only shrink, never increase in size
    public int endIndex;

    public int getSize() { return endIndex - startIndex + 1; }
  }
}
