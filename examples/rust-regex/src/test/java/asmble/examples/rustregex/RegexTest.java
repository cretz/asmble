package asmble.examples.rustregex;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;

@RunWith(Parameterized.class)
public class RegexTest {
    // Too slow to run regularly
    private static final String TOO_SLOW = "[a-q][^u-z]{13}x";

    @Parameterized.Parameters(name = "pattern: {0}")
    public static String[] data() {
        return new String[] {
            "Twain",
            "(?i)Twain",
            "[a-z]shing",
            "Huck[a-zA-Z]+|Saw[a-zA-Z]+",
            "\\b\\w+nn\\b",
            "[a-q][^u-z]{13}x",
            "Tom|Sawyer|Huckleberry|Finn",
            "(?i)Tom|Sawyer|Huckleberry|Finn",
            ".{0,2}(Tom|Sawyer|Huckleberry|Finn)",
            ".{2,4}(Tom|Sawyer|Huckleberry|Finn)",
            "Tom.{10,25}river|river.{10,25}Tom",
            "[a-zA-Z]+ing",
            "\\s[a-zA-Z]{0,12}ing\\s",
            "([A-Za-z]awyer|[A-Za-z]inn)\\s",
            "[\"'][^\"']{0,30}[?!\\.][\"']",
            "\u221E|\u2713",
            "\\p{Sm}"
        };
    }

    private static RustLib rustLib;
    private static String twainText;
    private static RustLib.Ptr preparedRustTarget;

    @BeforeClass
    public static void setUpClass() throws IOException {
        twainText = Main.loadTwainText();
        rustLib = new RustLib();
        preparedRustTarget = rustLib.prepareTarget(twainText);
    }

    private String pattern;

    public RegexTest(String pattern) {
        this.pattern = pattern;
    }

    @Test
    public void checkJavaVersusRust() {
        Assume.assumeFalse("Skipped for being too slow", pattern.equals(TOO_SLOW));
        int expected = new JavaLib().compile(pattern).matchCount(twainText);
        // System.out.println("Found " + expected + " matches for pattern: " + pattern);
        Assert.assertEquals(
            expected,
            rustLib.compile(pattern).matchCount(preparedRustTarget)
        );
    }
}
