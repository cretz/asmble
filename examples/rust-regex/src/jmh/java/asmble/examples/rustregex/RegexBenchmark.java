package asmble.examples.rustregex;

import org.openjdk.jmh.annotations.*;

import java.io.IOException;

@State(Scope.Thread)
public class RegexBenchmark {
    @Param({
        "Twain",
        "(?i)Twain",
        "[a-z]shing",
        "Huck[a-zA-Z]+|Saw[a-zA-Z]+",
        "\\b\\w+nn\\b",
        // Too slow
        // "[a-q][^u-z]{13}x",
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
    })
    private String patternString;

    private String twainString;
    private JavaLib javaLib;
    private JavaLib.JavaPattern precompiledJavaPattern;
    private RustLib rustLib;
    private RustLib.Ptr preparedRustTarget;
    private RustLib.RustPattern precompiledRustPattern;

    @Setup
    public void init() throws IOException {
        // JMH is not handling this right, so we replace inline
        if ("?|?".equals(patternString)) {
            patternString = "\u221E|\u2713";
        }
        twainString = Main.loadTwainText();
        javaLib = new JavaLib();
        precompiledJavaPattern = javaLib.compile(patternString);
        rustLib = new RustLib();
        preparedRustTarget = rustLib.prepareTarget(twainString);
        precompiledRustPattern = rustLib.compile(patternString);
    }

    @Benchmark
    public void javaRegexCheck() {
        precompiledJavaPattern.matchCount(twainString);
    }

    @Benchmark
    public void rustRegexCheck() {
        precompiledRustPattern.matchCount(preparedRustTarget);
    }
}