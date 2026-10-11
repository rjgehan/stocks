package com.stocks.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stocks.common.domain.Side;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.web.RequestIds;
import com.stocks.config.JacksonConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The §6.5 problem shape for every kind of failure. */
class ProblemDetailsAdviceTest {

  record Body(@NotBlank String symbol, Side side, BigDecimal qty) {}

  @RestController
  static class FailingController {
    @GetMapping("/api-exception")
    String apiException() {
      throw ApiException.of(ErrorCode.INSUFFICIENT_BUYING_POWER, "Order needs $231.96; buying power is $198.10.")
          .withHint("Try a notional (dollar) market order of \"198.10\" during regular hours.")
          .withField("qty", ErrorCode.INSUFFICIENT_BUYING_POWER, "Exceeds buying power");
    }

    @GetMapping("/rate-limited")
    String rateLimited() {
      throw ApiException.of(ErrorCode.RATE_LIMITED, "20 writes per minute").withRetryAfter(12);
    }

    @GetMapping("/stub")
    String stub() {
      throw ApiException.notImplemented("OrderService.place");
    }

    @GetMapping("/client-gone")
    String clientGone() throws Exception {
      throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
          "Response not usable after response errors.");
    }

    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("secret internals: password=hunter2");
    }

    @PostMapping("/body")
    String body(@Valid @RequestBody Body body) {
      return "ok";
    }

    @GetMapping("/bars")
    String bars(@RequestParam Timeframe timeframe) {
      return timeframe.wireValue();
    }
  }

  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
      .setControllerAdvice(new ProblemDetailsAdvice())
      .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(),
          new JacksonJsonHttpMessageConverter(JacksonConfig.newMapper()))
      .setValidator(validator())
      .setConversionService(conversionService())
      .addFilters(new RequestIds(new PublicId(new MutableTimeSource(Instant.parse("2026-10-09T14:00:00Z")))))
      .build();

  private static LocalValidatorFactoryBean validator() {
    LocalValidatorFactoryBean v = new LocalValidatorFactoryBean();
    v.afterPropertiesSet();
    return v;
  }

  private static org.springframework.format.support.FormattingConversionService conversionService() {
    var cs = new org.springframework.format.support.DefaultFormattingConversionService();
    new com.stocks.config.WebConfig().addFormatters(cs);
    return cs;
  }

  @Test
  void apiExceptionRendersTheFullShape() throws Exception {
    mvc.perform(get("/api-exception").header(RequestIds.HEADER, "req-abc"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(RequestIds.HEADER, "req-abc"))
        .andExpect(jsonPath("$.type").value("/errors/INSUFFICIENT_BUYING_POWER"))
        .andExpect(jsonPath("$.title").value("Insufficient buying power"))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_BUYING_POWER"))
        .andExpect(jsonPath("$.detail").value("Order needs $231.96; buying power is $198.10."))
        .andExpect(jsonPath("$.hint").value(startsWith("Try a notional")))
        .andExpect(jsonPath("$.fields", hasSize(1)))
        .andExpect(jsonPath("$.fields[0].field").value("qty"))
        .andExpect(jsonPath("$.fields[0].code").value("INSUFFICIENT_BUYING_POWER"))
        .andExpect(jsonPath("$.retryAfterS").value(nullValue()))
        .andExpect(jsonPath("$.requestId").value("req-abc"));
  }

  @Test
  void aClientThatWentAwayGetsNothingWrittenAndNoErrorLogged() throws Exception {
    // e.g. an SSE stream (§19.1) whose client disconnected: no 500 problem, no body
    var result = mvc.perform(get("/client-gone")).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentAsString()).isEmpty();
  }

  @Test
  void rateLimitedSetsRetryAfter() throws Exception {
    mvc.perform(get("/rate-limited"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "12"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.retryAfterS").value(12));
  }

  @Test
  void stubsAre501() throws Exception {
    mvc.perform(get("/stub"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
        .andExpect(jsonPath("$.detail").value(containsString("OrderService.place")));
  }

  @Test
  void internalErrorsHideEverythingButTheRequestId() throws Exception {
    String body = mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.detail").value(nullValue()))
        .andExpect(jsonPath("$.requestId").value(startsWith("req_")))
        .andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("hunter2").doesNotContain("IllegalStateException").doesNotContain("at com.");
  }

  @Test
  void malformedJsonWrongTypesAndBadEnumsAreValidationFailed() throws Exception {
    mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\"AAPL\",\"side\":\"HOLD\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fields[0].field").value("side"))
        .andExpect(jsonPath("$.fields[0].message").value(containsString("BUY")));
    mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\"AAPL\",\"qty\":\"lots\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields[0].field").value("qty"));
  }

  @Test
  void beanValidationListsTheFields() throws Exception {
    mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\" \",\"side\":\"buy\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fields[0].field").value("symbol"));
  }

  @Test
  void queryParametersBindEnumsCaseInsensitivelyByWireValue() throws Exception {
    mvc.perform(get("/bars").param("timeframe", "1min"))
        .andExpect(status().isOk())
        .andExpect(content().string("1Min"));
    mvc.perform(get("/bars").param("timeframe", "2min"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fields[0].field").value("timeframe"))
        .andExpect(jsonPath("$.fields[0].message").value(containsString("1Min")));
    mvc.perform(get("/bars"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields[0].field").value("timeframe"));
  }

  @Test
  void springMvcErrorsKeepTheirStatus() throws Exception {
    mvc.perform(post("/api-exception"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().exists("Allow"))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.status").value(405));
    mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.status").value(415));
  }
}
