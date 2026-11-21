package com.acme.ecommerce.cart.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.cart.api.dto.CartItemRequest;
import com.acme.ecommerce.cart.api.dto.CartItemResponse;
import com.acme.ecommerce.cart.service.CartService;
import com.acme.ecommerce.cart.support.AbstractCartIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The internal consumed-lines endpoint: token, validation and scoping (CTO condition 3). */
@AutoConfigureMockMvc
@TestPropertySource(properties = "cart.internal-token=test-internal-token")
class InternalCartControllerTest extends AbstractCartIntegrationTest {

  static final String TOKEN = "test-internal-token";

  @Autowired private MockMvc mockMvc;
  @Autowired private CartService carts;

  private static CartItemRequest item(UUID productId, int qty) {
    return new CartItemRequest(
        productId, qty, "Item", new BigDecimal("10.00"), "INR", null, null, null, null, null);
  }

  private static String line(CartItemResponse i) {
    return line(i.productId(), i.quantity(), i.addedAt().toString());
  }

  private static String line(UUID productId, int qty, String addedAt) {
    return "{\"productId\":\"%s\",\"quantity\":%d,\"addedAt\":\"%s\"}"
        .formatted(productId, qty, addedAt);
  }

  private static String body(String... lines) {
    return "{\"orderId\":\"%s\",\"lines\":[%s]}"
        .formatted(UUID.randomUUID(), String.join(",", lines));
  }

  private ResultActions call(String userId, String token, String json) throws Exception {
    var request =
        post("/internal/carts/" + userId + "/consumed")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json);
    if (token != null) {
      request = request.header("X-Internal-Token", token);
    }
    return mockMvc.perform(request);
  }

  @Test
  @DisplayName("401 without a token, and no line is removed")
  void noTokenIs401() throws Exception {
    UUID user = UUID.randomUUID();
    carts.addItem(user, item(UUID.randomUUID(), 1));
    String json = body(line(carts.get(user).items().get(0)));

    call(user.toString(), null, json).andExpect(status().isUnauthorized());

    assertThat(carts.get(user).items()).hasSize(1);
  }

  @Test
  @DisplayName("401 with a wrong token, and no line is removed")
  void wrongTokenIs401() throws Exception {
    UUID user = UUID.randomUUID();
    carts.addItem(user, item(UUID.randomUUID(), 1));
    String json = body(line(carts.get(user).items().get(0)));

    call(user.toString(), "not-the-token", json).andExpect(status().isUnauthorized());

    assertThat(carts.get(user).items()).hasSize(1);
  }

  @Test
  @DisplayName("200 with the right token removes the line and reports the count")
  void validTokenRemoves() throws Exception {
    UUID user = UUID.randomUUID();
    carts.addItem(user, item(UUID.randomUUID(), 1));
    String json = body(line(carts.get(user).items().get(0)));

    call(user.toString(), TOKEN, json)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.removed").value(1));
    call(user.toString(), TOKEN, json)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.removed").value(0));

    assertThat(carts.get(user).items()).isEmpty();
  }

  @Test
  @DisplayName("400 for a non-UUID user id")
  void nonUuidUserIs400() throws Exception {
    call("not-a-uuid", TOKEN, body()).andExpect(status().isBadRequest());
    // Positive sanity: the same request with a UUID is accepted.
    call(UUID.randomUUID().toString(), TOKEN, body()).andExpect(status().isOk());
  }

  @Test
  @DisplayName("400 for 51 lines, and 50 lines are accepted")
  void lineCap() throws Exception {
    UUID user = UUID.randomUUID();
    java.util.function.IntFunction<String> many =
        n ->
            IntStream.range(0, n)
                .mapToObj(i -> line(UUID.randomUUID(), 1, "2026-11-20T10:00:00Z"))
                .collect(Collectors.joining(","));

    call(user.toString(), TOKEN, body(many.apply(51))).andExpect(status().isBadRequest());
    call(user.toString(), TOKEN, body(many.apply(50))).andExpect(status().isOk());
  }

  @Test
  @DisplayName("400 for quantity 0 and 100, 200 for 1 and 99; a missing addedAt is 400")
  void quantityBounds() throws Exception {
    UUID user = UUID.randomUUID();
    String at = "2026-11-20T10:00:00Z";
    UUID p = UUID.randomUUID();

    call(user.toString(), TOKEN, body(line(p, 0, at))).andExpect(status().isBadRequest());
    call(user.toString(), TOKEN, body(line(p, 100, at))).andExpect(status().isBadRequest());
    call(user.toString(), TOKEN, body(line(p, 1, at))).andExpect(status().isOk());
    call(user.toString(), TOKEN, body(line(p, 99, at))).andExpect(status().isOk());
    call(user.toString(), TOKEN, body("{\"productId\":\"%s\",\"quantity\":1}".formatted(p)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("AC-18 through the endpoint: a changed and a later line survive")
  void laterLinesSurvive() throws Exception {
    UUID user = UUID.randomUUID();
    UUID consumed = UUID.randomUUID();
    UUID changed = UUID.randomUUID();
    UUID later = UUID.randomUUID();
    carts.addItem(user, item(consumed, 1));
    carts.addItem(user, item(changed, 1));
    String json =
        body(
            carts.get(user).items().stream()
                .map(InternalCartControllerTest::line)
                .toArray(String[]::new));
    carts.addItem(user, item(changed, 1));
    carts.addItem(user, item(later, 1));

    call(user.toString(), TOKEN, json)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.removed").value(1));

    assertThat(carts.get(user).items())
        .extracting(CartItemResponse::productId)
        .containsExactlyInAnyOrder(changed, later);
  }

  @Test
  @DisplayName("AC-19 through the endpoint: another user's cart is untouched")
  void otherUserUntouched() throws Exception {
    UUID user = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID p = UUID.randomUUID();
    carts.addItem(user, item(p, 2));
    carts.addItem(other, item(p, 2));
    String mine = body(line(carts.get(user).items().get(0)));
    String theirs = body(line(carts.get(other).items().get(0)));

    call(user.toString(), TOKEN, mine).andExpect(jsonPath("$.removed").value(1));

    assertThat(carts.get(other).items()).hasSize(1);
    // Positive sanity: B's own request does remove B's line.
    call(other.toString(), TOKEN, theirs).andExpect(jsonPath("$.removed").value(1));
    assertThat(carts.get(other).items()).isEmpty();
  }
}
