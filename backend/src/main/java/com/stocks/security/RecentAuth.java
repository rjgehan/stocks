package com.stocks.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or every method of a controller) that needs a <b>recent</b> password check (§17.1,
 * §21.1 column R): the caller must be on a browser session whose {@code authenticatedAt} (login or
 * {@code POST /api/v1/auth/reauth}) is at most {@code stocks.security.recent-auth} (15 min) old. Otherwise
 * {@link RecentAuthInterceptor} answers 403 {@code RECENT_AUTH_REQUIRED} and the SPA prompts for the password.
 * Guards cash transfers, resets, freeze, key issuance, role changes and the break-glass live view.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RecentAuth {}
