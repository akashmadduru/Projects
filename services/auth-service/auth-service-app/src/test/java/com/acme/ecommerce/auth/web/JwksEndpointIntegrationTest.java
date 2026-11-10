package com.acme.ecommerce.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.acme.ecommerce.auth.support.AbstractAuthIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The JWKS endpoint.
 *
 * <p>THE FIRST TEST HERE IS THE MOST IMPORTANT TEST IN THE SERVICE. If a private key component ever
 * appears in this document, anyone who can reach the endpoint can mint a valid token for any user
 * with any role, and every service in the platform will accept it as genuine. No other control
 * catches that - the forged tokens are, cryptographically, real.
 */
class JwksEndpointIntegrationTest extends AbstractAuthIntegrationTest {

  /**
   * RFC 7518 private RSA key members. Their presence in a JWKS document is a total compromise of
   * the platform's authentication.
   */
  private static final List<String> PRIVATE_RSA_MEMBERS =
      List.of("d", "p", "q", "dp", "dq", "qi", "oth");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("the JWKS document contains NO private key material")
  @SuppressWarnings("unchecked")
  void jwksNeverLeaksPrivateKeyMaterial() throws Exception {
    MockHttpServletResponse response =
        mockMvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    String raw = response.getContentAsString();

    Map<String, Object> body = objectMapper.readValue(raw, Map.class);
    List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");
    assertThat(keys).isNotEmpty();

    for (Map<String, Object> key : keys) {
      assertThat(key)
          .as("a public JWK must expose only kty/kid/use/alg/n/e")
          .containsKeys("kty", "kid", "n", "e");
      assertThat(key.keySet()).doesNotContainAnyElementsOf(PRIVATE_RSA_MEMBERS);
      assertThat(key.get("kty")).isEqualTo("RSA");
      assertThat(key.get("alg")).isEqualTo("RS256");
      assertThat(key.get("use")).isEqualTo("sig");
    }

    // Belt and braces against any future serialisation change: the private
    // member names must not appear anywhere in the raw body either.
    for (String member : PRIVATE_RSA_MEMBERS) {
      assertThat(raw).doesNotContain("\"" + member + "\":");
    }
  }

  @Test
  @DisplayName("every key in the keystore is published, so a rotation can overlap")
  @SuppressWarnings("unchecked")
  void publishesEveryKeyInTheKeystore() throws Exception {
    Map<String, Object> body =
        objectMapper.readValue(
            mockMvc
                .perform(get("/.well-known/jwks.json"))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            Map.class);
    List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");

    // The test keystore holds two key pairs. Publishing BOTH is what allows a
    // zero-downtime rotation: consumers accept the incoming key before it starts
    // signing, and the outgoing key after it stops.
    assertThat(keys).hasSize(2);
    assertThat(keys.stream().map(key -> key.get("kid")).distinct()).hasSize(2);
  }

  @Test
  @DisplayName("the document is cacheable, so auth-service is not on every service's hot path")
  void isCacheable() throws Exception {
    MockHttpServletResponse response =
        mockMvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse();

    assertThat(response.getHeader("Cache-Control")).contains("max-age=300");
  }

  @Test
  @DisplayName("the endpoint is public - a resource server cannot need a token to fetch keys")
  void isPublicAndUnauthenticated() throws Exception {
    // No Authorization header at all.
    assertThat(mockMvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getStatus())
        .isEqualTo(200);
  }
}
