package asmble.examples.rustregex;

public interface RegexLib<T> {

    RegexPattern<T> compile(String str);

    T prepareTarget(String target);
    
    interface RegexPattern<T> {
        int matchCount(T target);
    }
}
