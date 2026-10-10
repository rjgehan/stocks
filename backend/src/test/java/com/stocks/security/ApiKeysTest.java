package com.stocks.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** §17.2 key format: fixed-position parsing, generation and the peppered HMAC. */
class ApiKeysTest {

  /** The §18.4 example token (well-formed, not a real key). */
  static final String EXAMPLE = "stk_i6hqouwcme6b_d-Tf233Tnb6d0PNPR_yOEHlNYu2i5eD6f531UpQ2dY8";

  @Test
  void parsesTheSpecExampleByPositionNotByUnderscores() {
    ApiKeys.Parsed p = ApiKeys.parse(EXAMPLE).orElseThrow();
    assertThat(p.keyId()).isEqualTo("i6hqouwcme6b");
    assertThat(p.secret()).isEqualTo("d-Tf233Tnb6d0PNPR_yOEHlNYu2i5eD6f531UpQ2dY8").hasSize(43).contains("_");
    assertThat(ApiKeys.format(p.keyId(), p.secret())).isEqualTo(EXAMPLE);
    assertThat(p.toString()).doesNotContain(p.secret());
  }

  @Test
  void rejectsMalformedTokens() {
    assertThat(ApiKeys.parse(null)).isEmpty();
    assertThat(ApiKeys.parse("")).isEmpty();
    assertThat(ApiKeys.parse(EXAMPLE + "x")).isEmpty();
    assertThat(ApiKeys.parse(EXAMPLE.replace("stk_", "sk__"))).isEmpty();
    assertThat(ApiKeys.parse("stk_I6HQOUWCME6B_" + EXAMPLE.substring(17))).as("uppercase key id").isEmpty();
    assertThat(ApiKeys.parse("stk_i6hqouwcme61_" + EXAMPLE.substring(17))).as("1 is not base32").isEmpty();
    assertThat(ApiKeys.parse("stk_i6hqouwcme6b-" + EXAMPLE.substring(17))).as("separator").isEmpty();
    assertThat(ApiKeys.parse(EXAMPLE.substring(0, 59) + "=")).as("padding").isEmpty();
  }

  @Test
  void generatedKeysRoundTrip() {
    SecureRandom random = new SecureRandom();
    for (int i = 0; i < 200; i++) {
      String token = ApiKeys.format(ApiKeys.newKeyId(random), ApiKeys.newSecret(random));
      assertThat(token).hasSize(ApiKeys.TOKEN_LENGTH);
      assertThat(ApiKeys.parse(token)).isPresent();
    }
  }

  @Test
  void hmacIsHexSha256OfTheSecretUnderThePepper() {
    String secret = ApiKeys.newSecret(new Random(1));
    String hex = ApiKeys.hmacHex("pepper-0123456789abcdef0123456789abcdef", secret);
    assertThat(hex).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(ApiKeys.matches("pepper-0123456789abcdef0123456789abcdef", secret, hex)).isTrue();
    assertThat(ApiKeys.matches("pepper-0123456789abcdef0123456789abcdef", secret, hex.toUpperCase())).isTrue();
    assertThat(ApiKeys.matches("another-pepper", secret, hex)).isFalse();
    assertThat(ApiKeys.matches("pepper-0123456789abcdef0123456789abcdef", secret + "x", hex)).isFalse();
    assertThatThrownBy(() -> ApiKeys.hmacHex("", secret)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void storedScopesAreRestrictedToTheFourScopes() {
    assertThat(ApiKeyAuthenticationProvider.parseScopes("market:read  trade admin league:read"))
        .containsExactlyInAnyOrder("market:read", "trade", "league:read");
    assertThat(ApiKeyAuthenticationProvider.parseScopes(" ")).isEmpty();
  }
}
