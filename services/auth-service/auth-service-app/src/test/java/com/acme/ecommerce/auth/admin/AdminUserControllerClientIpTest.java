package com.acme.ecommerce.auth.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.auth.admin.AdminUserDtos.AdminUserResponse;
import com.acme.ecommerce.auth.audit.AuthAuditPublisher;
import com.acme.ecommerce.common.core.net.ClientAddress;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

class AdminUserControllerClientIpTest {

  @Test
  @DisplayName("the audit IP is the gateway header, not the socket peer")
  void auditIpIsHeader() {
    AdminUserService service = mock(AdminUserService.class);
    AuthAuditPublisher audit = mock(AuthAuditPublisher.class);
    AdminUserResponse resp = mock(AdminUserResponse.class);
    when(resp.status()).thenReturn("ACTIVE");
    when(service.get(any())).thenReturn(resp);
    when(service.changeStatus(any(), any(), any())).thenReturn(resp);
    Jwt jwt =
        Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString()).build();
    MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/admin/users/x/enable");
    http.setRemoteAddr("10.0.0.9");
    http.addHeader(ClientAddress.HEADER, "203.0.113.7");

    new AdminUserController(service, audit).enable(jwt, http, UUID.randomUUID());

    verify(audit).publish(any(), eq("203.0.113.7"), eq("USER_ENABLE"), any(), any(), any(), any());
  }
}
