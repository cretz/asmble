package asmble.run.jvm;

import java.lang.invoke.MethodHandle;

public class MethodHandleUtil {
    public static int invokeInt(MethodHandle mh) throws Throwable { return (int) mh.invokeExact(); }
    public static long invokeLong(MethodHandle mh) throws Throwable { return (long) mh.invokeExact(); }
    public static float invokeFloat(MethodHandle mh) throws Throwable { return (float) mh.invokeExact(); }
    public static double invokeDouble(MethodHandle mh) throws Throwable { return (double) mh.invokeExact(); }
    public static void invokeVoid(MethodHandle mh) throws Throwable { mh.invokeExact(); }
}
