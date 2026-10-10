package com.stocks.security;

import java.io.Serial;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * An unauthenticated bearer request: the raw {@code stk_…} token from {@code Authorization: Bearer}. Only
 * {@link ApiKeyAuthenticationProvider} accepts it; a successful authentication returns a
 * {@link PlayerAuthenticationToken}. Never stored anywhere.
 */
public final class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

  @Serial
  private static final long serialVersionUID = 1L;

  private transient String token;

  public ApiKeyAuthenticationToken(String token) {
    super(List.of());
    this.token = token;
    super.setAuthenticated(false);
  }

  @Override
  public Object getCredentials() {
    return token;
  }

  @Override
  public Object getPrincipal() {
    return ApiKeys.parse(token).map(ApiKeys.Parsed::keyId).orElse("invalid");
  }

  @Override
  public void eraseCredentials() {
    super.eraseCredentials();
    token = null;
  }

  @Override
  public boolean equals(Object o) {
    return this == o;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public String toString() {
    return "ApiKeyAuthenticationToken[keyId=" + getPrincipal() + "]";
  }
}
