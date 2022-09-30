package org.nrg.containers.secrets;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResolverFor {
    Class<? extends SecretSource> source() default SecretSource.AnySource.class;
    Class<? extends SecretDestination> destination();
}
