package com.acme.ecommerce.gateway.security;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import reactor.core.publisher.Mono;

/**
 * Resolves the JWKS URL through Eureka + client-side load balancing.
 *
 * <p>Uses the load balancer rather than {@code DiscoveryClient} directly so that a multi-instance
 * auth-service is actually spread across, and so that instance health/hints are honoured - the same
 * machinery {@code lb://AUTH-SERVICE} routes go through, rather than a second, subtly different
 * resolution path.
 *
 * <p>BLOCKING, DELIBERATELY. Nimbus' {@code JWKSetSource} contract is synchronous, and every call
 * into it is made either from a {@code boundedElastic} worker (see {@link
 * JwksReactiveJwtDecoderFactory}) or from Nimbus' own refresh-ahead scheduler thread. Neither is an
 * event loop. The {@link #RESOLVE_TIMEOUT} is what stops a stalled registry from parking that
 * worker indefinitely.
 */
public class DiscoveryJwkSetUriResolver implements JwkSetUriResolver {

  /**
   * The load balancer normally answers from its cached instance list, so this timeout only ever
   * bites during the very first resolution or a registry stall.
   */
  private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(3);

  private final ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;
  private final String serviceId;
  private final String path;

  public DiscoveryJwkSetUriResolver(
      ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory,
      String serviceId,
      String path) {
    this.loadBalancerFactory = Objects.requireNonNull(loadBalancerFactory, "loadBalancerFactory");
    this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
    this.path = Objects.requireNonNull(path, "path");
  }

  @Override
  public URI resolve() {
    ReactiveLoadBalancer<ServiceInstance> loadBalancer = loadBalancerFactory.getInstance(serviceId);
    if (loadBalancer == null) {
      throw new IllegalStateException(
          "No load balancer configured for service '" + serviceId + "'");
    }

    Response<ServiceInstance> response =
        Mono.from(loadBalancer.choose()).blockOptional(RESOLVE_TIMEOUT).orElse(null);

    if (response == null || !response.hasServer()) {
      // Not logged as an error here: the caller (Nimbus' outage-tolerant source) decides whether
      // this is survivable, and it usually is - the cached key set is still good.
      throw new IllegalStateException(
          "No instance of '" + serviceId + "' is registered; cannot fetch JWKS");
    }

    ServiceInstance instance = response.getServer();
    return URI.create(instance.getUri().toString() + path);
  }
}
