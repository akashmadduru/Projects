package com.acme.ecommerce.order.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.order.client.SellerInternalFeignClient.SellerRef;
import com.acme.ecommerce.order.support.AbstractCancelIT;
import com.acme.ecommerce.order.support.CancelFixture.Built;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** F-14 A8 (AC-6): the 401/403/404/409 matrix of the two cancel endpoints, through the filters. */
@AutoConfigureMockMvc
class ShipmentCancelAuthzIT extends AbstractCancelIT {

  @MockitoBean private JwtDecoder jwtDecoder;
  @Autowired private MockMvc mvc;

  private static final String BODY = "{\"reason\":\"out of stock\"}";

  private String bearer(String role, UUID user) {
    String token = "t-" + UUID.randomUUID();
    Instant now = Instant.now();
    when(jwtDecoder.decode(token))
        .thenReturn(
            Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(user.toString())
                .claim("roles", List.of(role))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build());
    return "Bearer " + token;
  }

  private org.springframework.test.web.servlet.ResultActions sellerCancel(
      String auth, UUID shipment) throws Exception {
    var req =
        post("/api/seller/shipments/{id}/cancel", shipment)
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY);
    return mvc.perform(auth == null ? req : req.header("Authorization", auth));
  }

  private org.springframework.test.web.servlet.ResultActions adminCancel(
      String auth, UUID order, UUID shipment) throws Exception {
    var req =
        post("/api/admin/orders/{o}/shipments/{s}/cancel", order, shipment)
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY);
    return mvc.perform(auth == null ? req : req.header("Authorization", auth));
  }

  @Test
  @DisplayName("seller: the owner gets 200, another seller 404, an unapproved seller 403")
  void sellerMatrix() throws Exception {
    Built b = workedExample();
    UUID owner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID pending = UUID.randomUUID();
    when(sellers.byUser(owner)).thenReturn(new SellerRef(b.sellerIds().get(0), "APPROVED"));
    when(sellers.byUser(other)).thenReturn(new SellerRef(UUID.randomUUID(), "APPROVED"));
    when(sellers.byUser(pending)).thenReturn(new SellerRef(UUID.randomUUID(), "PENDING"));
    UUID s1 = b.shipmentIds().get(0);

    sellerCancel(bearer("SELLER", other), s1).andExpect(status().isNotFound());
    sellerCancel(bearer("SELLER", pending), s1).andExpect(status().isForbidden());
    sellerCancel(null, s1).andExpect(status().isUnauthorized());
    sellerCancel(bearer("USER", owner), s1).andExpect(status().isForbidden());
    // positive sanity: the same request by the owner works, so the 404 above is the ownership rule
    sellerCancel(bearer("SELLER", owner), s1).andExpect(status().isOk());
    // and a replay is still a 200
    sellerCancel(bearer("SELLER", owner), s1).andExpect(status().isOk());
  }

  @Test
  @DisplayName("admin: right order 200, shipment of another order 404, customer 403, anonymous 401")
  void adminMatrix() throws Exception {
    Built b = workedExample();
    Built other = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    UUID admin = UUID.randomUUID();

    adminCancel(bearer("ADMIN", admin), other.orderId(), s1).andExpect(status().isNotFound());
    adminCancel(bearer("USER", admin), b.orderId(), s1).andExpect(status().isForbidden());
    adminCancel(bearer("SUPPORT", admin), b.orderId(), s1).andExpect(status().isForbidden());
    adminCancel(null, b.orderId(), s1).andExpect(status().isUnauthorized());
    adminCancel(bearer("ADMIN", admin), b.orderId(), s1).andExpect(status().isOk());
  }

  @Test
  @DisplayName("a shipment that already shipped is a 409, and /status CANCELLED stays a 409")
  void conflicts() throws Exception {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    UUID admin = UUID.randomUUID();
    String token = bearer("ADMIN", admin);

    mvc.perform(
            post("/api/admin/orders/{o}/shipments/{s}/status", b.orderId(), s1)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isConflict());
    // the status route moved no money and cancelled nothing
    org.assertj.core.api.Assertions.assertThat(shipmentStatus(s1)).isEqualTo("PLACED");

    jdbc.update("UPDATE orders.order_shipments SET status = 'SHIPPED' WHERE id = ?", s1);
    adminCancel(token, b.orderId(), s1).andExpect(status().isConflict());
  }
}
