package asmble.examples.ruststring;

import asmble.generated.RustString;

public class Main {
    // 20 pages is good for now
    private static final int PAGE_SIZE = 65536;
    private static final int MAX_MEMORY = 20 * PAGE_SIZE;

    public static void main(String[] args) {
        Lib lib = new Lib(new RustString(MAX_MEMORY));
        System.out.println("Char count of 'tester': " + lib.stringLength("tester"));
        String russianHello = "\u0417\u0434\u0440\u0430\u0432\u0441\u0442\u0432\u0443\u0439\u0442\u0435";
        System.out.println("Char count of Russian hello (" + russianHello + "): " + lib.stringLength(russianHello));
        System.out.println(lib.prependFromRust("Hello, World!"));
    }
}
