package com.acme.ecommerce.notification.mail;

/** A rendered email. */
public record EmailContent(String subject, String text, String html) {}
