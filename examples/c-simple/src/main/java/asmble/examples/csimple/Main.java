package asmble.examples.csimple;

import java.lang.invoke.MethodHandle;

import asmble.generated.CSimple;

class Main {
    public static void main(String[] args) {
        // Doesn't need memory or method table
        CSimple simple = new CSimple(0, new MethodHandle[0]);
        System.out.println("25 + 1 = " + simple.addOne(25));
    }
}