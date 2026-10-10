package com.stocks.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.config.JacksonConfig;
import com.stocks.config.stubs.ContractStub;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.dto.QuoteDto;
import com.stocks.contract.trading.OrderQueries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole application boots on the {@code test} profile with the Day-0 contract beans (§27.1): every contract
 * service resolves to exactly one bean (a stub, or a workstream's real bean once it lands), a stub surfaces as a 501
 * {@code NOT_IMPLEMENTED} problem through the real MVC stack, and Boot's {@code JsonMapper} writes DTOs exactly like
 * the §6.3 reference mapper.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractStubsIT {

  @TestConfiguration
  static class StubProbe {
    @Bean
    StubProbeController stubProbeController(OrderQueries orderQueries) {
      return new StubProbeController(orderQueries);
    }
  }

  /** A throwaway endpoint that calls a contract service (test sources only). */
  @RestController
  static class StubProbeController {
    private final OrderQueries orderQueries;

    StubProbeController(OrderQueries orderQueries) {
      this.orderQueries = orderQueries;
    }

    @GetMapping("/api/v1/__contract-probe")
    Object probe() {
      return orderQueries.openOrdersBySymbol("AAPL");
    }
  }

  @Autowired ApplicationContext context;
  @Autowired JsonMapper jsonMapper;
  @Autowired MockMvc mvc;
  @Autowired MarketDataService marketData;
  @Autowired OrderQueries orderQueries;

  @Test
  void everyContractServiceResolvesToExactlyOneBean() {
    for (Class<?> contract : ContractServices.ALL) {
      assertThat(context.getBeansOfType(contract)).as(contract.getSimpleName()).hasSize(1);
    }
  }

  @Test
  void noDayZeroStubIsLeft() {                    // M4: every contract has its real bean (§27.4)
    assertThat(context.getBeansOfType(ContractStub.class)).as("Day-0 stub beans").isEmpty();
  }

  @Test
  void theMarketDataDefaultAnswersWithQualityNone() {
    if (marketData instanceof ContractStub) {
      assertThat(marketData.quote("AAPL").quality()).isEqualTo(DataQuality.NONE);
    }
  }

  @Test
  void aStubIsA501ProblemThroughTheMvcStack() throws Exception {
    if (!(orderQueries instanceof ContractStub)) {
      return;                                     // WS3 landed the real bean
    }
    assertThatThrownBy(() -> orderQueries.openOrdersBySymbol("AAPL"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_IMPLEMENTED));
    mvc.perform(get("/api/v1/__contract-probe").with(user("alice")))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
        .andExpect(jsonPath("$.status").value(501))
        .andExpect(jsonPath("$.type").value("/errors/NOT_IMPLEMENTED"));
  }

  @Test
  void bootJsonMapperWritesDtosLikeTheReferenceMapper() throws Exception {
    JsonMapper reference = JacksonConfig.newMapper();
    Object[] samples = {
        ContractGoldenJsonTest.example18_4(),
        DtoSamples.sample(QuoteDto.class),
        DtoSamples.allNull(QuoteDto.class),
        DtoSamples.sample(com.stocks.contract.trading.dto.ActivityDto.class),
        DtoSamples.sample(com.stocks.contract.admin.dto.SettingsDto.class),
        DtoSamples.sample(com.stocks.contract.rules.dto.RulesDto.class)
    };
    for (Object s : samples) {
      assertThat(jsonMapper.writeValueAsString(s)).isEqualTo(reference.writeValueAsString(s));
    }
    assertThat(jsonMapper.readTree(jsonMapper.writeValueAsString(ContractGoldenJsonTest.example18_4())))
        .isEqualTo(reference.readTree(ContractGoldenJsonTest.PLACE_ORDER_RESPONSE_18_4));
  }
}
