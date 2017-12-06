package asmble.examples.rustregex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaLib implements RegexLib<String> {
    @Override
    public JavaPattern compile(String str) {
        return new JavaPattern(str);
    }

    @Override
    public String prepareTarget(String target) {
        return target;
    }

    public class JavaPattern implements RegexPattern<String> {

        private final Pattern pattern;

        private JavaPattern(String pattern) {
            this(Pattern.compile(pattern));
        }

        private JavaPattern(Pattern pattern) {
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
