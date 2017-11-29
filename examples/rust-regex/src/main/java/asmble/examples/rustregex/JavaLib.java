package asmble.examples.rustregex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaLib implements RegexLib {
    @Override
    public JavaPattern compile(String str) {
        return new JavaPattern(str);
    }

    public static class JavaPattern implements RegexPattern {

        final Pattern pattern;

        JavaPattern(String pattern) {
            this(Pattern.compile(pattern));
        }

        JavaPattern(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public int matchCount(String target) {
            Matcher matcher = pattern.matcher(target);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }
    }
}
