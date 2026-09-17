package android.platform.test.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for the platform test annotation. Upstream, a CheckFlagsRule skips the method when the named
 * aconfig flag is off; this module has no such rule, so the annotation only documents the flag and the
 * test's own capability checks decide whether it does anything.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresFlagsEnabled {
    String[] value();
}
