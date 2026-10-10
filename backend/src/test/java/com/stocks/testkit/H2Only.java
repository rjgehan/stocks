package com.stocks.testkit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Keeps a Spring test context on in-memory H2 even under {@code -Ppg-it} (the default there is embedded PostgreSQL,
 * {@link EmbeddedPgContextInitializer}). Classes named {@code *H2IT} are treated the same way, by convention.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface H2Only {}
