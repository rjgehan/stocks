package com.stocks.oauth;

import com.stocks.common.id.Ulid;
import com.stocks.common.time.TimeSource;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Public ids of the authorization server (§17.9): {@code ogr_<ULID>} grants, {@code ocl_<ULID>} DCR clients (both
 * monotonic, from {@link TimeSource}) and {@code oar_<32 random base64url>} pending authorization requests (random,
 * because the id is the only handle on a request before login). Not in {@code common.id.Prefix}: these ids never
 * leave the OAuth slice.
 */
@Component
public class OAuthIds {

  public static final String GRANT = "ogr_";
  public static final String CLIENT = "ocl_";
  public static final String REQUEST = "oar_";

  private final TimeSource time;
  private final Ulid.Generator ulids = new Ulid.Generator(new SecureRandom());

  public OAuthIds(TimeSource time) {
    this.time = time;
  }

  public String grant() {
    return GRANT + ulids.next(time.now().toEpochMilli());
  }

  public String client() {
    return CLIENT + ulids.next(time.now().toEpochMilli());
  }

  public String request() {
    return REQUEST + Secrets.random(24);
  }
}
