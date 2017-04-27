package run.jvm.emscripten;

import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public class Env {

    public static final List<Function<Env, Object>> subModules = Arrays.asList(
        Common::new,
        Syscall::new
    );

    final Mem mem;
    final OutputStream out;
    final int argc;
    final int argv;
    private final List<AtExitCallback> atExitCallbacks = new ArrayList<>();

    public Env(int staticBump, OutputStream out, String programName, String... args) {
        this(new Mem(staticBump), out, programName, args);
    }

    public Env(Mem mem, OutputStream out, String programName, String... args) {
        this.mem = mem;
        this.out = out;
        // We need to add the args which is an int array
        argc = args.length + 1;
        int[] argv = new int[argc];
        argv[0] = mem.putCString(programName);
        for (int i = 0; i < args.length; i++) {
            argv[i + 1] = mem.putCString(args[i]);
        }
        this.argv = mem.putIntArray(argv);
    }

    public ByteBuffer getMemory() {
        return mem.buf;
    }

    public int getArgc() {
        return argc;
    }

    public int getArgv() {
        return argv;
    }

    public void addCallback(int funcPtr, Integer arg) {
        atExitCallbacks.add(new AtExitCallback(funcPtr, arg));
    }

    public void runAtExitCallbacks(Object moduleInst) throws Throwable {
        MethodHandle noArg = null;
        MethodHandle withArg = null;
        for (int i = atExitCallbacks.size() - 1; i >= 0; i--) {
            AtExitCallback cb = atExitCallbacks.get(i);
            if (cb.arg == null) {
                Field table = moduleInst.getClass().getDeclaredField("table");
                table.setAccessible(true);
                MethodHandle[] h = (MethodHandle[]) MethodHandles.lookup().unreflectGetter(table).invoke(moduleInst);
                h[cb.funcPtr].invoke();
                if (noArg == null) {
                    noArg = MethodHandles.lookup().bind(moduleInst,
                        "dynCall_v", MethodType.methodType(Void.TYPE, Integer.TYPE));
                }
                noArg.invokeExact(cb.funcPtr);
            } else {
                if (withArg == null) {
                    withArg = MethodHandles.lookup().bind(moduleInst,
                        "dynCall_vi", MethodType.methodType(Void.TYPE, Integer.TYPE, Integer.TYPE));
                }
                withArg.invokeExact(cb.funcPtr, cb.arg.intValue());
            }
        }
    }

    public static class AtExitCallback {
        public final int funcPtr;
        public final Integer arg;

        public AtExitCallback(int funcPtr, Integer arg) {
            this.funcPtr = funcPtr;
            this.arg = arg;
        }
    }
}
