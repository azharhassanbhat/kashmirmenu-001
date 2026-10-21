package org.junit.jupiter.api.condition;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
public @interface EnabledIfSystemProperty { String named(); String matches(); }
