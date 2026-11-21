package com.acme.ecommerce.cart.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.cart.support.AbstractCartIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** With no internal token configured the endpoint is closed, whatever the caller sends. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "cart.internal-token=")
class InternalCartControllerUnconfiguredTokenTest extends AbstractCartIntegrationTest {

  @Autowired private MockMvc mockMvc;

  private ResultActions call(String token) throws Exception {
    var request =
        post("/internal/carts/" + UUID.randomUUID() + "/consumed")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"lines\":[]}");
    if (token != null) {
      request = request.header("X-Internal-Token", token);
    }
    return mockMvc.perform(request);
  }

  @Test
  @DisplayName("401 when the token is not configured: no header, an empty header, any header")
  void unconfiguredTokenIs401() throws Exception {
    call(null).andExpect(status().isUnauthorized());
    call("").andExpect(status().isUnauthorized());
    call("test-internal-token").andExpect(status().isUnauthorized());
  }
}
