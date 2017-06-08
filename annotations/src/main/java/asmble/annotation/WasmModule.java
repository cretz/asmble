package asmble.annotation;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WasmModule {
    String name() default "";
    String binary() default "";
}
