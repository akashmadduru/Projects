package com.acme.ecommerce.gateway.support;

import java.util.List;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

/**
 * A fixed instance list, replacing Eureka in the routing tests.
 *
 * <p>The tests still go through the real {@code lb://} path - {@code
 * ReactiveLoadBalancerClientFilter}, instance selection, URI rewriting - because that filter is
 * where a surprising amount of gateway behaviour lives (including the 503 raised when a service id
 * has no instances, which the circuit-breaker fallback test depends on). Only the source of the
 * instance list is swapped.
 *
 * @param serviceId the service id this supplier answers for
 * @param instances the instances to return; an EMPTY list is a deliberate scenario - it is what
 *     "product-service has not been deployed yet" looks like to the gateway
 */
public record StubServiceInstances(String serviceId, List<ServiceInstance> instances)
    implements ServiceInstanceListSupplier {

  /** A supplier resolving to a single loopback instance on {@code port}. */
  public static StubServiceInstances at(String serviceId, int port) {
    return new StubServiceInstances(
        serviceId,
        List.of(new DefaultServiceInstance(serviceId + "-1", serviceId, "127.0.0.1", port, false)));
  }

  /** A supplier with nothing registered. */
  public static StubServiceInstances none(String serviceId) {
    return new StubServiceInstances(serviceId, List.of());
  }

  @Override
  public String getServiceId() {
    return serviceId;
  }

  @Override
  public Flux<List<ServiceInstance>> get() {
    return Flux.just(instances);
  }
}
