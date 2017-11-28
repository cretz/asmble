package asmble.examples.rustsimple;

import asmble.generated.RustSimple;

class Main {
    // 20 pages is good for now
    private static final int PAGE_SIZE = 65536;
    private static final int MAX_MEMORY = 20 * PAGE_SIZE;

    public static void main(String[] args) {
        RustSimple simple = new RustSimple(MAX_MEMORY);
        System.out.println("25 + 1 = " + simple.add_one(25));
    }
}