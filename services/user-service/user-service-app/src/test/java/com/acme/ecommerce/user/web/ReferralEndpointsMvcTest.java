package com.acme.ecommerce.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ReferralEndpointsMvcTest extends AbstractUserIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("GET /api/referrals keeps exactly its pre-change fields")
  void responseShapeUnchanged() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/referrals")
                    .header("X-Forwarded-For", "203.0.113.50, 10.0.0.1")
                    .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode json = objectMapper.readTree(body);
    List<String> fields = new ArrayList<>();
    json.fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder("code", "link", "invited", "rewarded", "rewards");
    assertThat(body).doesNotContain("203.0.113.50");
  }
}
