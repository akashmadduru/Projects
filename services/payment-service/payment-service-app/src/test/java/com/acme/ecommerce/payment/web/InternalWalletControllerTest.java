package com.acme.ecommerce.payment.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.payment.service.WalletService;
import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** B-10 C8: GET /internal/wallet/credits/{key}: token guarded, only user, amount and currency. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalWalletControllerTest extends AbstractPaymentIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private WalletService wallet;

  private HttpResponse<String> get(String key, String token) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/internal/wallet/credits/" + key));
    if (token != null) {
      b.header("X-Internal-Token", token);
    }
    return HttpClient.newHttpClient().send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("a credit under the key is found with user, amount and currency only")
  void found() throws Exception {
    UUID user = UUID.randomUUID();
    String key = "return-rest-" + UUID.randomUUID();
    wallet.credit(user, new BigDecimal("40.00"), "INR", "REFUND", "ref-1", key);

    HttpResponse<String> r = get(key, "test-internal-token");

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body())
        .contains("\"found\":true")
        .contains("\"userId\":\"" + user + "\"")
        .contains("\"currency\":\"INR\"")
        .containsPattern("\"amount\":40(\\.0+)?[,}]");
    // Nothing else leaks: no balance, no transaction id or reference.
    assertThat(r.body()).doesNotContain("balance").doesNotContain("ref-1").doesNotContain("\"id\"");
  }

  @Test
  @DisplayName("an unknown key and a DEBIT key both answer 200 found=false")
  void notFound() throws Exception {
    UUID user = UUID.randomUUID();
    wallet.credit(user, new BigDecimal("50.00"), "INR", "REFUND", "r", "seed-" + user);
    String debitKey = "debit-" + user;
    wallet.debit(user, new BigDecimal("10.00"), "INR", UUID.randomUUID(), debitKey);

    for (String key : new String[] {"missing-" + UUID.randomUUID(), debitKey}) {
      HttpResponse<String> r = get(key, "test-internal-token");
      assertThat(r.statusCode()).isEqualTo(200);
      assertThat(r.body()).contains("\"found\":false").doesNotContain(user.toString());
    }
  }

  @Test
  @DisplayName("a missing or wrong X-Internal-Token is refused, even for an existing credit")
  void tokenRequired() throws Exception {
    UUID user = UUID.randomUUID();
    String key = "return-rest-" + UUID.randomUUID();
    wallet.credit(user, new BigDecimal("40.00"), "INR", "REFUND", "ref-2", key);

    for (String token : new String[] {null, "wrong-token", ""}) {
      HttpResponse<String> r = get(key, token);
      assertThat(r.statusCode()).isIn(401, 403);
      assertThat(r.body()).doesNotContain(user.toString()).doesNotContain("\"found\"");
    }
    // Positive sanity: the same request with the right token does return the credit.
    assertThat(get(key, "test-internal-token").body()).contains(user.toString());
  }
}
