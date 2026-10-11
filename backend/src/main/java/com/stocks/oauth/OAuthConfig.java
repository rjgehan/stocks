package com.stocks.oauth;

import com.stocks.common.time.TimeSource;
import com.stocks.oauth.cimd.CimdClients;
import com.stocks.oauth.cimd.CimdFetcher;
import com.stocks.oauth.cimd.SafeHttpsFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring of the OAuth 2.1 authorization server (§17.9): the SSRF-safe CIMD fetcher (a test replaces it with a
 * {@code @Primary} bean that serves documents without the network) and the CIMD client cache. At startup the signing
 * key is loaded (or generated and stored) once, so the first connector does not pay for it; a failure is only logged,
 * the key is then retried on first use.
 */
@Configuration(proxyBeanMethods = false)
public class OAuthConfig {

  private static final Logger log = LoggerFactory.getLogger(OAuthConfig.class);

  @Bean
  @ConditionalOnMissingBean
  CimdFetcher cimdFetcher(OAuthProperties properties) {
    return new SafeHttpsFetcher(properties.cimd().timeout(), properties.cimd().maxBytes());
  }

  @Bean
  CimdClients cimdClients(CimdFetcher fetcher, JsonMapper jsonMapper, RedirectUris redirects,
                          OAuthProperties properties, TimeSource time) {
    return new CimdClients(fetcher, jsonMapper, redirects, properties, time);
  }

  /** Loads the signing key once the app is up (§17.9.4). */
  @Bean
  OAuthKeyWarmup oauthKeyWarmup(SigningKeys keys, OAuthProperties properties) {
    return new OAuthKeyWarmup(keys, properties);
  }

  /** Not a lambda listener: a named class keeps the log line attributable. */
  static final class OAuthKeyWarmup {
    private final SigningKeys keys;
    private final OAuthProperties properties;

    OAuthKeyWarmup(SigningKeys keys, OAuthProperties properties) {
      this.keys = keys;
      this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
      if (!properties.enabled()) {
        return;
      }
      try {
        keys.current();
      } catch (RuntimeException e) {
        log.error("OAuth signing key unavailable at startup (retried on first use): {}", e.toString());
      }
    }
  }
}
