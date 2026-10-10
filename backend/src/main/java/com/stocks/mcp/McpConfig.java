package com.stocks.mcp;

import com.stocks.config.StocksProperties;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.mcp.tools.McpToolSet;
import com.stocks.security.RequestMetas;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP server (§20.1): Spring AI 2.0.1, STATELESS Streamable HTTP at {@code POST /mcp}, SYNC
 * ({@code spring.ai.mcp.server.*} in {@code application.yml}). Behind security chain 2, so every request is already
 * authenticated (API key bearer or session + CSRF) when it reaches the transport. Verified by the Day-1 spike
 * ({@code McpServerIT}, §28 #13 and #20):
 * <ul>
 *   <li><b>One JSON mapper.</b> {@code mcpServerJsonMapper} is Boot's customized {@link JsonMapper} (§6.3), the same
 *       instance REST and STOMP use. Spring AI's {@code McpServerJsonMapperAutoConfiguration} is
 *       {@code @ConditionalOnMissingBean(name = "mcpServerJsonMapper")} and backs off. Ours is registered with
 *       {@code defaultCandidate = false}, like Spring AI's, so it neither competes for {@code JsonMapper} injection
 *       points nor counts as a {@code JsonMapper} for Boot's own {@code @ConditionalOnMissingBean}.</li>
 *   <li><b>Transport with a {@code contextExtractor}.</b> The auto-configured {@link WebMvcStatelessServerTransport}
 *       ({@code @ConditionalOnMissingBean}) is replaced by one built with Boot's mapper, the configured endpoint, a
 *       {@code contextExtractor} that puts the caller's {@link PlayerPrincipal} and a {@link RequestMeta} (channel
 *       {@code MCP_KEY}, request id, IP, user agent, MCP client name) into the {@link McpTransportContext}, and the
 *       Origin check (validated when present, against {@code stocks.public-url} only).</li>
 *   <li><b>Tools.</b> Spring AI's annotation scanner is off ({@code spring.ai.mcp.server.annotation-scanner.enabled:
 *       false}); {@link McpToolCatalog} builds the tool list from the {@link McpToolSet} beans with post-processed
 *       output schemas ({@link McpOutputSchemas}), Boot-mapper results ({@link McpToolHandler}) and the §20.3
 *       order.</li>
 *   <li><b>Instructions</b> come from {@code classpath:mcp/instructions.md} unless
 *       {@code spring.ai.mcp.server.instructions} is set.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class McpConfig {

  /** The server instructions sent on {@code initialize} (§20.1). */
  public static final String INSTRUCTIONS_RESOURCE = "mcp/instructions.md";

  /** Boot's {@code JsonMapper}, registered a second time under the name Spring AI's MCP beans inject (§6.3). */
  @Bean(name = "mcpServerJsonMapper", defaultCandidate = false)
  JsonMapper mcpServerJsonMapper(JsonMapper bootJsonMapper) {
    return bootJsonMapper;
  }

  @Bean
  WebMvcStatelessServerTransport webMvcStatelessServerTransport(
      @Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper,
      @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint,
      StocksProperties properties, McpClientNames clientNames) {
    return WebMvcStatelessServerTransport.builder()
        .jsonMapper(new JacksonMcpJsonMapper(mcpServerJsonMapper))
        .messageEndpoint(endpoint)
        .contextExtractor(contextExtractor(clientNames))
        .securityValidator(DefaultServerTransportSecurityValidator.builder()
            .allowedOrigin(origin(properties.publicUrl()))
            .build())
        .build();
  }

  /** The §20.3 tools, in order (the server collects every {@code List<SyncToolSpecification>} bean). */
  @Bean
  List<SyncToolSpecification> stocksMcpTools(List<McpToolSet> toolSets,
                                             @Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper) {
    return McpToolCatalog.specifications(toolSets, mcpServerJsonMapper);
  }

  /** Loads {@link #INSTRUCTIONS_RESOURCE} into {@link McpServerProperties} (after binding) unless already set. */
  @Bean
  static BeanPostProcessor mcpInstructionsLoader() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof McpServerProperties p && !StringUtils.hasText(p.getInstructions())) {
          p.setInstructions(instructions());
        }
        return bean;
      }
    };
  }

  /**
   * The transport's {@code contextExtractor}: runs on the servlet thread for every POST, after chain 2 authenticated
   * the request, and hands the caller to the tool (which may run elsewhere) through the {@link McpTransportContext}
   * (read with {@link CurrentPlayer#require(McpTransportContext)} and {@link CurrentPlayer#requestMeta}).
   */
  static McpTransportContextExtractor<ServerRequest> contextExtractor(McpClientNames clientNames) {
    return request -> {
      HttpServletRequest servlet = request.servletRequest();
      PlayerPrincipal principal = CurrentPlayer.current().orElse(null);
      RequestMeta base = RequestMetas.of(servlet, principal);
      RequestMeta meta = new RequestMeta(base.requestId(), base.channel(), base.ip(), base.userAgent(),
          clientNames.resolve(servlet, principal), null);
      return CurrentPlayer.mcpContext(principal, meta);
    };
  }

  /** {@code scheme://host[:port]} of a URL, the form browsers send in {@code Origin}. */
  static String origin(String url) {
    URI uri = URI.create(url.strip());
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("stocks.public-url must be an absolute URL: " + url);
    }
    return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
        + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
  }

  static String instructions() {
    try (InputStream in = new ClassPathResource(INSTRUCTIONS_RESOURCE).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read classpath:" + INSTRUCTIONS_RESOURCE, e);
    }
  }
}
