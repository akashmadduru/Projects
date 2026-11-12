package com.acme.ecommerce.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * notification-service settings.
 *
 * @param internalToken shared secret required on /internal/** (blank rejects every call)
 * @param sender From address
 * @param storefrontBaseUrl base for links in emails
 * @param smsMailboxDomain dev SMS sink: texts are also emailed to {@code sms+<digits>@domain}
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
    @DefaultValue("") String internalToken,
    @DefaultValue("no-reply@acme.local") String sender,
    @DefaultValue("http://localhost:3000") String storefrontBaseUrl,
    @DefaultValue("acme.local") String smsMailboxDomain) {}
