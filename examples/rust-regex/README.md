### Example: Rust Regex

This shows an example of using the Rust regex library on the JVM compiled via WASM. This builds on
the [rust-simple](../rust-simple) and [rust-string](../rust-string) examples. See the former for build prereqs. There is
also a simple benchmark checking the performance compared to the built-in Java regex engine.

#### Main

In this version, we include the `regex` crate. The main loads a ~15MB text file Project Gutenberg collection of Mark
Twain works (taken from [this blog post](https://rust-leipzig.github.io/regex/2017/03/28/comparison-of-regex-engines/)
that does Rust regex performance benchmarks). Both the Java and Rust regex engines are abstracted into a common
interface. When run, it checks how many times the word "Twain" appears via both regex engines.

To run it yourself, run the following from the root `asmble` dir:

    gradlew --no-daemon :examples:rust-regex:run

In release mode, the generated class is 903KB w/ ~575 methods. The output:

    'Twain' count in Java: 811
    'Twain' count in Rust: 811

#### Tests

I wanted to compare the Java regex engine with the Rust regex engine. Before running benchmarks, I wrote a
[unit test](src/test/java/asmble/examples/rustregex/RegexTest.java) to test parity. I used the examples from the
aforementioned [blog post](https://rust-leipzig.github.io/regex/2017/03/28/comparison-of-regex-engines/) to test with.
The test simply confirms the Java regex library and the Rust regex library produce the same match counts across the
Mark Twain corpus. To run the test, execute:

    gradlew --no-daemon :examples:rust-regex:test

Here is my output of the test part:

    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: Twain] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: (?i)Twain] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: [a-z]shing] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: Huck[a-zA-Z]+|Saw[a-zA-Z]+] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: \b\w+nn\b] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: [a-q][^u-z]{13}x] SKIPPED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: Tom|Sawyer|Huckleberry|Finn] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: (?i)Tom|Sawyer|Huckleberry|Finn] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: .{0,2}(Tom|Sawyer|Huckleberry|Finn)] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: .{2,4}(Tom|Sawyer|Huckleberry|Finn)] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: Tom.{10,25}river|river.{10,25}Tom] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: [a-zA-Z]+ing] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: \s[a-zA-Z]{0,12}ing\s] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: ([A-Za-z]awyer|[A-Za-z]inn)\s] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: ["'][^"']{0,30}[?!\.]["']] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: ?|?] PASSED
    asmble.examples.rustregex.RegexTest > checkJavaVersusRust[pattern: \p{Sm}] PASSED

As mentioned in the blog post, `[a-q][^u-z]{13}x` is a very slow pattern for Rust, so I skipped it (but it does produce
the same count if you're willing to wait a couple of minutes). Also, `?|?` is actually `∞|✓`, it's just not printable
unicode in the text output I used.

#### Benchmarks

With the accuracy confirmed, now was time to benchmark the two engines. I wrote a
[JMH benchmark](src/jmh/java/asmble/examples/rustregex/RegexBenchmark.java) to test the same patterns as the unit test
checks. It precompiles the patterns and preloads the target string on the Rust side before checking simple match count.
As with any benchmarks, this is just my empirical data and everyone else's will be different. To run the benchmark,
execute (it takes a while to run):

    gradlew --no-daemon :examples:rust-regex:jmh

Here are my results (reordered and with added linebreaks for readability, higher score is better):

    Benchmark                                          (patternString)   Mode  Cnt   Score   Error  Units

    RegexBenchmark.javaRegexCheck                                Twain  thrpt   15  29.756 ± 1.169  ops/s
    RegexBenchmark.rustRegexCheck                                Twain  thrpt   15  55.012 ± 0.677  ops/s

    RegexBenchmark.javaRegexCheck                            (?i)Twain  thrpt   15   6.181 ± 0.560  ops/s
    RegexBenchmark.rustRegexCheck                            (?i)Twain  thrpt   15   1.333 ± 0.029  ops/s

    RegexBenchmark.javaRegexCheck                           [a-z]shing  thrpt   15   6.138 ± 0.937  ops/s
    RegexBenchmark.rustRegexCheck                           [a-z]shing  thrpt   15  12.352 ± 0.103  ops/s

    RegexBenchmark.javaRegexCheck           Huck[a-zA-Z]+|Saw[a-zA-Z]+  thrpt   15   4.774 ± 0.330  ops/s
    RegexBenchmark.rustRegexCheck           Huck[a-zA-Z]+|Saw[a-zA-Z]+  thrpt   15  56.079 ± 0.487  ops/s

    RegexBenchmark.javaRegexCheck                            \b\w+nn\b  thrpt   15   2.703 ± 0.086  ops/s
    RegexBenchmark.rustRegexCheck                            \b\w+nn\b  thrpt   15   0.131 ± 0.001  ops/s

    RegexBenchmark.javaRegexCheck          Tom|Sawyer|Huckleberry|Finn  thrpt   15   2.633 ± 0.033  ops/s
    RegexBenchmark.rustRegexCheck          Tom|Sawyer|Huckleberry|Finn  thrpt   15  14.388 ± 0.138  ops/s

    RegexBenchmark.javaRegexCheck      (?i)Tom|Sawyer|Huckleberry|Finn  thrpt   15   3.178 ± 0.045  ops/s
    RegexBenchmark.rustRegexCheck      (?i)Tom|Sawyer|Huckleberry|Finn  thrpt   15   8.882 ± 0.110  ops/s

    RegexBenchmark.javaRegexCheck  .{0,2}(Tom|Sawyer|Huckleberry|Finn)  thrpt   15   1.191 ± 0.010  ops/s
    RegexBenchmark.rustRegexCheck  .{0,2}(Tom|Sawyer|Huckleberry|Finn)  thrpt   15   0.572 ± 0.012  ops/s

    RegexBenchmark.javaRegexCheck  .{2,4}(Tom|Sawyer|Huckleberry|Finn)  thrpt   15   1.017 ± 0.024  ops/s
    RegexBenchmark.rustRegexCheck  .{2,4}(Tom|Sawyer|Huckleberry|Finn)  thrpt   15   0.584 ± 0.008  ops/s

    RegexBenchmark.javaRegexCheck    Tom.{10,25}river|river.{10,25}Tom  thrpt   15   5.326 ± 0.050  ops/s
    RegexBenchmark.rustRegexCheck    Tom.{10,25}river|river.{10,25}Tom  thrpt   15  15.705 ± 0.247  ops/s

    RegexBenchmark.javaRegexCheck                         [a-zA-Z]+ing  thrpt   15   1.768 ± 0.057  ops/s
    RegexBenchmark.rustRegexCheck                         [a-zA-Z]+ing  thrpt   15   1.001 ± 0.012  ops/s

    RegexBenchmark.javaRegexCheck                \s[a-zA-Z]{0,12}ing\s  thrpt   15   4.020 ± 0.111  ops/s
    RegexBenchmark.rustRegexCheck                \s[a-zA-Z]{0,12}ing\s  thrpt   15   0.416 ± 0.004  ops/s

    RegexBenchmark.javaRegexCheck        ([A-Za-z]awyer|[A-Za-z]inn)\s  thrpt   15   2.441 ± 0.024  ops/s
    RegexBenchmark.rustRegexCheck        ([A-Za-z]awyer|[A-Za-z]inn)\s  thrpt   15   0.591 ± 0.004  ops/s

    RegexBenchmark.javaRegexCheck            ["'][^"']{0,30}[?!\.]["']  thrpt   15  20.466 ± 0.309  ops/s
    RegexBenchmark.rustRegexCheck            ["'][^"']{0,30}[?!\.]["']  thrpt   15   2.459 ± 0.024  ops/s

    RegexBenchmark.javaRegexCheck                                  ?|?  thrpt   15  15.856 ± 0.158  ops/s
    RegexBenchmark.rustRegexCheck                                  ?|?  thrpt   15  14.657 ± 0.177  ops/s

    RegexBenchmark.javaRegexCheck                               \p{Sm}  thrpt   15  22.156 ± 0.406  ops/s
    RegexBenchmark.rustRegexCheck                               \p{Sm}  thrpt   15   0.592 ± 0.005  ops/s

To keep from making this a big long post like most benchmark posts tend to be, here is a bulleted list of notes:

* I ran this on a Win 10 box, 1.8GHz i7-8550U HP laptop. I used latest Zulu JDK 8. For JMH, I set it at 3 forks, 5
  warmup iterations, and 5 measurement iterations (that's why `cnt` above is 15 = 5 measurements * 3 forks). It took a
  bit over 25 minutes to complete.
* All of the tests had the Java and Rust patterns precompiled. In Rust's case, I also placed the UTF-8 string on the
  accessible heap before the benchmark started to be fair.
* Like the unit test, I excluded `[a-q][^u-z]{13}x` because Rust is really slow at it (Java wins by a mile here). Also
  like the unit test, `?|?` is actually `∞|✓`.
* Of the ones tested, Rust is faster in 6 and Java is faster in the other 10. And where Rust is faster, it is much
  faster. This is quite decent since the Rust+WASM version uses `ByteBuffer`s everywhere, has some overflow checks, and
  in general there are some impedance mismatches with the WASM bytecode and the JVM bytecode.
* Notice the low error numbers on the Rust versions. The error number is the deviation between invocations. This shows
  the WASM-to-JVM ends up quite deterministic (or maybe, that there is just too much cruft to JIT, heh).
* If I were more serious about it, I'd check with other OS's, add more iterations, tweak some compiler options, include 
  regex pattern compilation speed benchmarks, and so on. But I just needed simple proof that speed is reasonable.

Overall, this shows running Rust on the JVM to be entirely reasonable for certain types of workloads. There are still
memory concerns, but not terribly. If given the choice, use a JVM language of course; the safety benefits of Rust don't
outweigh the problems of Rust-to-WASM-to-JVM such as build complexity, security concerns (`ByteBuffer` is where all
memory lives), debugging concerns, etc. But if you have a library in Rust, exposing it to the JVM sans-JNI is a doable
feat if you must.