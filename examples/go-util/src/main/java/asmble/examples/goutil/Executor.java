package asmble.examples.goutil;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.IntConsumer;

public class Executor<T> {
  @FunctionalInterface
  public interface ConstructorNoJs<T> {
    T create(ByteBuffer mem, MethodHandle debug, MethodHandle runtimeWasmExit, MethodHandle runtimeWasmWrite,
        MethodHandle runtimeNanotime, MethodHandle runtimeWalltime, MethodHandle runtimeScheduleCallback,
        MethodHandle runtimeClearScheduledCallback, MethodHandle runtimeGetRandomData);
  }

  @FunctionalInterface
  public interface Run<T> {
    void run(T instance, int argc, int argv);
  }

  protected static final int PAGE_SIZE = 65536;

  public Random random = new SecureRandom();
  public OutputStream out = System.out;

  // Always little endian
  protected final ByteBuffer mem;
  protected final T instance;
  protected IntConsumer debug;
  protected Integer exitCode;

  public Executor(ConstructorNoJs<T> constructor) {
    this(16384, constructor);
  }

  public Executor(int maxMemPages, ConstructorNoJs<T> constructor) {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    MethodType singleIntParam = MethodType.methodType(Void.TYPE, Integer.TYPE);
    try {
      // Set to little endian by the constructor, not us
      mem = ByteBuffer.allocateDirect(maxMemPages * PAGE_SIZE);
      instance = constructor.create(
          mem,
          lookup.bind(this, "debug", singleIntParam),
          lookup.bind(this, "runtimeWasmExit", singleIntParam),
          lookup.bind(this, "runtimeWasmWrite", singleIntParam),
          lookup.bind(this, "runtimeNanotime", singleIntParam),
          lookup.bind(this, "runtimeWalltime", singleIntParam),
          lookup.bind(this, "runtimeScheduleCallback", singleIntParam),
          lookup.bind(this, "runtimeClearScheduledCallback", singleIntParam),
          lookup.bind(this, "runtimeGetRandomData", singleIntParam)
      );
    } catch (NoSuchMethodException|IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  public Integer run(Run<T> run, String... args) {
    return run(run, args, Collections.emptyMap());
  }

  public Integer run(Run<T> run, String[] args, Map<String, String> env) {
    // Argc + argv, then env appended to argv as key=val
    int offset = 4096;
    int argc = args.length;
    List<Integer> strPtrs = new ArrayList<>();
    for (String arg : args) {
      strPtrs.add(offset);
      offset += newString(arg, offset);
    }
    for (Map.Entry<String, String> var : env.entrySet()) {
      strPtrs.add(offset);
      offset += newString(var.getKey() + "=" + var.getValue(), offset);
    }
    int argv = offset;
    for (int strPtr : strPtrs) {
      mem.putLong(offset, strPtr);
      offset += 8;
    }
    // Run and return exit code
    run.run(instance, argc, argv);
    return exitCode;
  }

  // Returns size, aligned to 8
  protected int newString(String str, int ptr) {
    byte[] bytes = (str + '\0').getBytes(StandardCharsets.UTF_8);
    putBytes(ptr, bytes);
    return bytes.length + (8 - (bytes.length % 8));
  }

  protected byte[] getBytes(int offset, byte[] bytes) {
    ByteBuffer buf = mem.duplicate();
    buf.position(offset);
    buf.get(bytes);
    return bytes;
  }

  protected void putBytes(int offset, byte[] bytes) {
    ByteBuffer buf = mem.duplicate();
    buf.position(offset);
    buf.put(bytes);
  }

  protected void debug(int v) {
    if (debug != null) debug.accept(v);
  }

  protected void runtimeWasmExit(int sp) {
    exitCode = mem.getInt(sp + 8);
  }

  protected void wasmExit(int exitCode) {
    this.exitCode = exitCode;
  }

  protected void runtimeWasmWrite(int sp) {
    long fd = mem.getLong(sp + 8);
    long ptr = mem.getLong(sp + 16);
    int len = mem.getInt(sp + 24);
    wasmWrite(fd, getBytes((int) ptr, new byte[len]));
  }

  protected void wasmWrite(long fd, byte[] bytes) {
    if (fd != 2) throw new UnsupportedOperationException("Only fd 2 support on write, got " + fd);
    if (out != null) {
      try {
        out.write(bytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  protected void runtimeNanotime(int sp) {
    mem.putLong(sp + 8, System.nanoTime());
  }

  protected void runtimeWalltime(int sp) {
    Instant now = Instant.now();
    mem.putLong(sp + 8, now.getEpochSecond());
    mem.putInt(sp + 16, now.getNano());
  }

  protected void runtimeScheduleCallback(int sp) {
    throw new UnsupportedOperationException("runtime.scheduleCallback");
  }

  protected void runtimeClearScheduledCallback(int sp) {
    throw new UnsupportedOperationException("runtime.clearScheduledCallback");
  }

  protected void runtimeGetRandomData(int sp) {
    long len = mem.getLong(sp + 16);
    byte[] bytes = new byte[(int) len];
    random.nextBytes(bytes);
    putBytes((int) mem.getLong(sp + 8), bytes);
  }
}
