package asmble.examples.rustregex;

public interface RegexLib {

    RegexPattern compile(String str);

    interface RegexPattern {
        int matchCount(String target);
    }
}
