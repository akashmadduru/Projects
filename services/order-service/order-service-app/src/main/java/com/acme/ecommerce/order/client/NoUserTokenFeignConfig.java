package com.acme.ecommerce.order.client;

import feign.Capability;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * Per-client configuration (not component-scanned) for service-to-service clients that authenticate
 * with X-Internal-Token only: removes the Authorization header the global forwarding interceptor
 * may have copied from the inbound request, so a user's bearer never reaches an internal endpoint.
 * Implemented as a Capability that wraps every interceptor of the client, so the header is gone
 * whatever order the interceptors run in.
 */
public class NoUserTokenFeignConfig {

  @Bean
  public Capability stripUserTokenCapability() {
    return new StripUserToken();
  }

  public static final class StripUserToken implements Capability {
    @Override
    public RequestInterceptor enrich(RequestInterceptor interceptor) {
      return template -> {
        interceptor.apply(template);
        template.removeHeader("Authorization");
      };
    }
  }
}
