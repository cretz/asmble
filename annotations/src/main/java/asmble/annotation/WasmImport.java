package asmble.annotation;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface WasmImport {
    String module();
    String field();
    // The JVM method descriptor of an export that will match this
    String desc();
    WasmExternalKind kind();
    int resizableLimitInitial() default -1;
    int resizableLimitMaximum() default -1;
    boolean globalSetter() default false;
}
