package com.acme.ecommerce.auth.client;

import com.acme.ecommerce.notification.api.NotificationClient;
import org.springframework.cloud.openfeign.FeignClient;

/**
 * Feign binding for notification-service (resolved through Eureka by name). Timeouts live under
 * {@code feign.client.config.notification-service}; the {@code X-Internal-Token} header is added by
 * {@link NotificationFeignConfig}, which is scoped to this client only.
 */
@FeignClient(
    name = "notification-service",
    url = "${services.notification.url:}",
    configuration = NotificationFeignConfig.class)
public interface NotificationFeignClient extends NotificationClient {}
