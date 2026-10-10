package com.stocks.config;

import com.stocks.common.domain.WireEnum;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.core.io.Resource;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

/**
 * Web MVC configuration (§5.1, WS1).
 *
 * <ul>
 *   <li><b>Enum binding</b> for query and path parameters (§6.3, §18): every enum binds case-insensitively, and the
 *       wire-value enums ({@code HistoryRange}, {@code ChartRange}, {@code Timeframe}) bind by wire value, so
 *       {@code ?range=1D} and {@code ?timeframe=1min} work. A bad value becomes 400 {@code VALIDATION_FAILED}.</li>
 *   <li><b>Static files and the SPA</b> (§24.3, §24.4), from the Boot static locations ({@code classpath:/static/},
 *       filled from {@code frontend/dist} by {@code make jar}). Boot's default mapping is off
 *       ({@code spring.web.resources.add-mappings=false}) and replaced by:
 *       <ul>
 *         <li>{@code /assets/**} (Vite-hashed): {@code Cache-Control: public, max-age=31536000, immutable};</li>
 *         <li>{@code /**}: {@code Cache-Control: no-cache}; a GET for a path without a static-file extension
 *             ({@link #STATIC_EXTENSIONS}; {@code /stock/BRK.B} is a route, not a file) that is not
 *             under {@code /api}, {@code /ws}, {@code /mcp}, {@code /v3}, {@code /swagger-ui}, {@code /oauth2},
 *             {@code /.well-known} or {@code /h2-console} falls back to {@code index.html} (client-side routes).
 *             Anything else that does not exist is a 404 problem.</li>
 *       </ul>
 *       {@code /api/**} and {@code /mcp} responses get {@code Cache-Control: no-store} from the security chain.</li>
 *   <li><b>MIME types</b> the servlet container does not know: {@code .webmanifest} →
 *       {@code application/manifest+json} (the §22.3 PWA manifest; Tomcat would send
 *       {@code application/octet-stream}, which {@code nosniff} makes final).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

  /** First path segments that never fall back to the SPA (§24.3). */
  static final Set<String> NO_SPA_FALLBACK =
      Set.of("api", "ws", "mcp", "v3", "swagger-ui", "oauth2", ".well-known", "h2-console");

  static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

  private final WebProperties webProperties;

  /** Default static locations; for standalone MockMvc setups that only need the converters. */
  public WebConfig() {
    this(new WebProperties());
  }

  @Autowired
  public WebConfig(WebProperties webProperties) {
    this.webProperties = webProperties;
  }

  /** Extension → content type, added to the embedded server's defaults. */
  static final Map<String, String> EXTRA_MIME_TYPES = Map.of("webmanifest", "application/manifest+json");

  @Bean
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> extraMimeMappings() {
    return factory -> factory.addMimeMappings(new MimeMappings(EXTRA_MIME_TYPES));
  }

  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverterFactory(new CaseInsensitiveEnumConverterFactory());
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String[] locations = webProperties.getResources().getStaticLocations();
    String[] assetLocations = new String[locations.length];
    for (int i = 0; i < locations.length; i++) {
      assetLocations[i] = locations[i] + (locations[i].endsWith("/") ? "" : "/") + "assets/";
    }
    registry.addResourceHandler("/assets/**")
        .addResourceLocations(assetLocations)
        .setCacheControl(IMMUTABLE)
        .resourceChain(false);
    registry.addResourceHandler("/**")
        .addResourceLocations(locations)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(false)
        .addResolver(new SpaFallbackResolver());
  }

  /**
   * Extensions of real static files. Only these make a missing path a 404: a dot alone does not, because client
   * routes carry symbols such as {@code /stock/BRK.B} and {@code /stock/BF.B} (a reload or shared link must open the
   * app, not a JSON 404).
   */
  static final Set<String> STATIC_EXTENSIONS = Set.of("js", "mjs", "cjs", "css", "map", "html", "htm", "json", "txt",
      "xml", "webmanifest", "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "avif", "bmp", "woff", "woff2", "ttf",
      "otf", "eot", "wasm", "pdf", "mp3", "mp4", "webm", "ogg", "zip", "gz", "br", "csv", "yml", "yaml", "md");

  /** True for a client-side route: not a static file type, and not under a server-owned prefix. */
  static boolean isSpaRoute(String path) {
    String p = path.startsWith("/") ? path.substring(1) : path;
    int slash = p.indexOf('/');
    String first = slash < 0 ? p : p.substring(0, slash);
    if (NO_SPA_FALLBACK.contains(first)) {
      return false;
    }
    String last = p.substring(p.lastIndexOf('/') + 1);
    int dot = last.lastIndexOf('.');
    return dot < 0 || !STATIC_EXTENSIONS.contains(last.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
  }

  /** Normal static resolution first; then {@code index.html} for client-side routes. */
  static final class SpaFallbackResolver extends PathResourceResolver {
    @Override
    protected Resource resolveResourceInternal(HttpServletRequest request, String requestPath,
                                               List<? extends Resource> locations, ResourceResolverChain chain) {
      Resource found = super.resolveResourceInternal(request, requestPath, locations, chain);
      if (found != null || !isSpaRoute(requestPath)) {
        return found;
      }
      if (request != null && !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
        return null;
      }
      return super.resolveResourceInternal(request, "index.html", locations, chain);
    }
  }

  /** String → any enum: wire value first (for {@link WireEnum}), then the constant name, ignoring case. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static final class CaseInsensitiveEnumConverterFactory implements ConverterFactory<String, Enum> {

    @Override
    public <T extends Enum> Converter<String, T> getConverter(Class<T> targetType) {
      Class<?> enumType = targetType;
      while (enumType != null && !enumType.isEnum()) {
        enumType = enumType.getSuperclass();
      }
      Class<T> type = (Class<T>) enumType;
      return source -> {
        String value = source.trim();
        if (value.isEmpty()) {
          return null;
        }
        if (WireEnum.class.isAssignableFrom(type)) {
          return (T) WireEnum.parse((Class) type, value);
        }
        for (T constant : type.getEnumConstants()) {
          if (constant.name().equalsIgnoreCase(value)) {
            return constant;
          }
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " '" + source + "'");
      };
    }
  }
}
