package com.stocks.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stocks.common.time.TimeSource;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/**
 * The JWT access tokens of §17.9.4 (RFC 9068): RS256, header {@code typ: at+jwt} and {@code kid}; claims {@code iss}
 * (the issuer), {@code sub} (the acting player's public id), {@code aud} ({@code ${public-url}/mcp}),
 * {@code client_id}, {@code scope}, {@code gid} (the grant), {@code iat}, {@code nbf}, {@code exp}, {@code jti}, and
 * {@code act: {sub: <human>}} when the token acts as a bot (RFC 8693).
 *
 * <p>Also the {@link JwtDecoder} chain 2 uses ({@code security.OAuthTokenAuthenticationProvider}): signature with the
 * current key (by {@code kid}), {@code alg} RS256, {@code typ} {@code at+jwt}, {@code iss}, {@code aud} containing
 * the resource, and {@code exp}/{@code nbf} against {@link TimeSource} with {@value #SKEW_SECONDS} s of skew. The
 * grant itself is checked by the provider on every request.
 */
@Component
public class AccessTokens implements JwtDecoder {

  static final long SKEW_SECONDS = 60;
  static final JOSEObjectType AT_JWT = new JOSEObjectType("at+jwt");

  private final SigningKeys keys;
  private final OAuthUrls urls;
  private final OAuthProperties properties;
  private final TimeSource time;

  public AccessTokens(SigningKeys keys, OAuthUrls urls, OAuthProperties properties, TimeSource time) {
    this.keys = keys;
    this.urls = urls;
    this.properties = properties;
    this.time = time;
  }

  /** A freshly minted token and its lifetime in seconds. */
  public record Minted(String value, long expiresIn) {}

  /**
   * Signs an access token.
   *
   * @param actorPublicId the player the token acts as ({@code sub})
   * @param humanPublicId the consenting human; becomes {@code act.sub} when it differs from the actor
   */
  public Minted mint(String actorPublicId, String humanPublicId, String clientId, Set<String> scopes,
                     String grantPublicId) {
    Instant now = time.now();
    Duration ttl = properties.accessTokenTtl();
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
        .issuer(urls.issuer())
        .subject(actorPublicId)
        .audience(urls.resource())
        .claim("client_id", clientId)
        .claim("scope", String.join(" ", new TreeSet<>(scopes)))
        .claim("gid", grantPublicId)
        .issueTime(Date.from(now))
        .notBeforeTime(Date.from(now))
        .expirationTime(Date.from(now.plus(ttl)))
        .jwtID(UUID.randomUUID().toString());
    if (!actorPublicId.equals(humanPublicId)) {
      claims.claim("act", Map.of("sub", humanPublicId));
    }
    RSAKey key = keys.current();
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(AT_JWT).keyID(key.getKeyID()).build(),
        claims.build());
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (JOSEException e) {
      throw new IllegalStateException("Could not sign an access token", e);
    }
    return new Minted(jwt.serialize(), ttl.toSeconds());
  }

  @Override
  public Jwt decode(String token) {
    SignedJWT jwt = verifySignature(token);
    JWTClaimsSet claims;
    try {
      claims = jwt.getJWTClaimsSet();
    } catch (ParseException e) {
      throw new BadJwtException("Malformed access token claims");
    }
    if (!urls.issuer().equals(claims.getIssuer())) {
      throw new BadJwtException("The access token was issued by another server");
    }
    List<String> audience = claims.getAudience();
    if (audience == null || !audience.contains(urls.resource())) {
      throw new BadJwtException("The access token is not for this resource");
    }
    Instant now = time.now();
    Date exp = claims.getExpirationTime();
    if (exp == null || !exp.toInstant().plusSeconds(SKEW_SECONDS).isAfter(now)) {
      throw new BadJwtException("The access token has expired");
    }
    Date nbf = claims.getNotBeforeTime();
    if (nbf != null && nbf.toInstant().minusSeconds(SKEW_SECONDS).isAfter(now)) {
      throw new BadJwtException("The access token is not valid yet");
    }
    Map<String, Object> headers = new LinkedHashMap<>(jwt.getHeader().toJSONObject());
    Map<String, Object> claimMap = new LinkedHashMap<>(claims.getClaims());
    claimMap.replaceAll((k, v) -> v instanceof Date d ? d.toInstant() : v);
    return new Jwt(token, claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant(),
        exp.toInstant(), headers, claimMap);
  }

  /**
   * Parses {@code token} and checks its header and signature only (no time or audience checks): the revocation
   * endpoint accepts expired access tokens of the calling client.
   */
  public SignedJWT verifySignature(String token) {
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (ParseException e) {
      throw new BadJwtException("Malformed access token");
    }
    JWSHeader header = jwt.getHeader();
    if (!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || !AT_JWT.equals(header.getType())) {
      throw new BadJwtException("Unexpected access token type or algorithm");
    }
    RSAKey key = keys.current();
    if (header.getKeyID() == null || !header.getKeyID().equals(key.getKeyID())) {
      throw new BadJwtException("The access token was signed with an unknown key");
    }
    try {
      if (!jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))) {
        throw new BadJwtException("Bad access token signature");
      }
    } catch (JOSEException e) {
      throw new BadJwtException("Bad access token signature");
    }
    return jwt;
  }

  /** True when {@code token} has the shape of a JWS (three base64url parts). */
  public static boolean looksLikeJws(String token) {
    return token != null && token.matches("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
  }
}
