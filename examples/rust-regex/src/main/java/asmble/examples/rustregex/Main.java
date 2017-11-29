package asmble.examples.rustregex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Main {

    // 20 pages is good for now
    private static final int PAGE_SIZE = 65536;
    private static final int MAX_MEMORY = 20 * PAGE_SIZE;

    public static void main(String[] args) throws Exception {
        String twainText = loadTwainText();
        System.out.println("Appearances of 'Twain': " + new JavaLib().compile("Twain").matchCount(twainText));
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