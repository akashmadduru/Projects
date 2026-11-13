package com.acme.ecommerce.notification.web;

import com.acme.ecommerce.notification.api.NotificationClient;
import com.acme.ecommerce.notification.api.dto.EmailRequest;
import com.acme.ecommerce.notification.api.dto.SmsRequest;
import com.acme.ecommerce.notification.config.NotificationProperties;
import com.acme.ecommerce.notification.inbox.NotificationDispatcher;
import com.acme.ecommerce.notification.mail.EmailContent;
import com.acme.ecommerce.notification.mail.EmailRenderer;
import com.acme.ecommerce.notification.mail.MailSenderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal send endpoints. Token-guarded by {@code InternalTokenFilter}; never routed by the
 * gateway. Email bodies are never logged (they contain secrets).
 */
@RestController
@RequestMapping(NotificationClient.BASE_PATH)
public class InternalNotificationController {

  private static final Logger log = LoggerFactory.getLogger(InternalNotificationController.class);

  private final EmailRenderer renderer;
  private final MailSenderService mail;
  private final NotificationProperties properties;
  private final NotificationDispatcher dispatcher;

  public InternalNotificationController(
      EmailRenderer renderer,
      MailSenderService mail,
      NotificationProperties properties,
      NotificationDispatcher dispatcher) {
    this.renderer = renderer;
    this.mail = mail;
    this.properties = properties;
    this.dispatcher = dispatcher;
  }

  public record InboxRequest(
      @NotNull UUID userId,
      @NotBlank String type,
      @NotBlank String title,
      String body,
      String link) {}

  /** Inbox entry (+ preference-gated web push) for other services: alerts, wallet, membership. */
  @PostMapping("/inbox")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void inbox(@Valid @RequestBody InboxRequest request) {
    dispatcher.notifyUser(
        request.userId(), request.type(), request.title(), request.body(), request.link(), null);
  }

  @PostMapping("/email")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void email(@Valid @RequestBody EmailRequest request) {
    EmailContent content = renderer.render(request.template(), request.data());
    mail.send(request.to(), content.subject(), content.text(), content.html());
    log.info("Sent {} email", request.template());
  }

  /**
   * Dev SMS: there is no SMS provider. The text is logged and also emailed to {@code
   * sms+<digits>@domain} so it is visible in Mailpit. Tech debt: replace with a real provider.
   */
  @PostMapping("/sms")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void sms(@Valid @RequestBody SmsRequest request) {
    String digits = request.to().replaceAll("[^0-9]", "");
    // The text of an SMS is usually a one-time code. It is NEVER logged, and the number is masked,
    // so reading logs cannot be used to take over an account.
    log.info(
        "SMS requested for ***{} ({} chars)",
        digits.length() > 2 ? digits.substring(digits.length() - 2) : "",
        request.text() == null ? 0 : request.text().length());
    String sink = properties.smsMailboxDomain();
    if (sink == null || sink.isBlank()) {
      // Production: no SMS provider is wired and the dev "email the text to a sink mailbox" trick
      // would send one-time codes to an arbitrary domain. Drop the message instead.
      log.warn("No SMS provider is configured; the message was not delivered");
      return;
    }
    EmailContent content =
        renderer.order("SMS to " + request.to(), "SMS", request.text(), null, null);
    mail.send(
        "sms+" + digits + "@" + properties.smsMailboxDomain(),
        content.subject(),
        content.text(),
        content.html());
  }
}
