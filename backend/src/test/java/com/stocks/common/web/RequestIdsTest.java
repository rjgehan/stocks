package com.stocks.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RequestIdsTest {

  @RestController
  static class EchoController {
    @GetMapping("/echo")
    String echo() {
      return RequestIds.current();
    }
  }

  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EchoController())
      .addFilters(new RequestIds(new PublicId(new MutableTimeSource(Instant.parse("2026-10-09T14:00:00Z")))))
      .build();

  @Test
  void acceptsAValidClientIdAndEchoesIt() throws Exception {
    MvcResult r = mvc.perform(get("/echo").header(RequestIds.HEADER, "bot-run:42.a_b"))
        .andExpect(status().isOk())
        .andExpect(header().string(RequestIds.HEADER, "bot-run:42.a_b"))
        .andReturn();
    assertThat(r.getResponse().getContentAsString()).isEqualTo("bot-run:42.a_b");
  }

  @Test
  void replacesMissingTooLongOrBadIdsWithAServerId() throws Exception {
    for (String bad : new String[] {null, "", "x".repeat(65), "has space", "semi;colon", "x".repeat(10_000)}) {
      var req = get("/echo");
      if (bad != null) {
        req.header(RequestIds.HEADER, bad);
      }
      MvcResult r = mvc.perform(req).andExpect(status().isOk()).andReturn();
      String id = r.getResponse().getHeader(RequestIds.HEADER);
      assertThat(PublicId.isValid(id, Prefix.REQUEST)).as("for %s", bad).isTrue();
      assertThat(r.getResponse().getContentAsString()).isEqualTo(id);
    }
    assertThat(RequestIds.isAcceptable("x".repeat(64))).isTrue();
  }
}
