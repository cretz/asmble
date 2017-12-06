package asmble.examples.rustregex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Main {

    public static void main(String[] args) throws Exception {
        String twainString = loadTwainText();
        System.out.println("'Twain' count in Java: " + matchCount(twainString, "Twain", new JavaLib()));
        System.out.println("'Twain' count in Rust: " + matchCount(twainString, "Twain", new RustLib()));
    }

    public static <T> int matchCount(String target, String pattern, RegexLib<T> lib) {
        RegexLib.RegexPattern<T> compiledPattern = lib.compile(pattern);
        T preparedTarget = lib.prepareTarget(target);
        return compiledPattern.matchCount(preparedTarget);
    }

    public static String loadTwainText() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (InputStream is = Main.class.getResourceAsStream("/twain-for-regex.txt")) {
            byte[] buffer = new byte[0xFFFF];
            while (true) {
                int lastLen = is.read(buffer);
                if (lastLen < 0) {
                    break;
                }
                os.write(buffer, 0, lastLen);
            }
        }
        return new String(os.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}