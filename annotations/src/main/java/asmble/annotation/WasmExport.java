package asmble.annotation;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface WasmExport {
    String value();
    WasmExternalKind kind() default WasmExternalKind.FUNCTION;
}
