package com.acme.ecommerce.notification.mail;

import com.acme.ecommerce.notification.config.NotificationProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/** Sends multipart (text + HTML) email through the configured SMTP relay. */
@Service
public class MailSenderService {

  private final JavaMailSender mailSender;
  private final NotificationProperties properties;

  public MailSenderService(JavaMailSender mailSender, NotificationProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
  }

  /** Sends one message; throws {@link IllegalStateException} on any delivery failure. */
  public void send(String to, String subject, String text, String html) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(properties.sender());
      helper.setTo(to);
      helper.setSubject(subject);
      helper.setText(text, html);
      mailSender.send(message);
    } catch (MessagingException | MailException e) {
      throw new IllegalStateException("Email delivery failed", e);
    }
  }
}
