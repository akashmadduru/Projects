package com.acme.ecommerce.notification.mail;

import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders the account emails (reset, verification, OTP) and the order-event emails into a shared
 * minimal HTML layout. Hand-built strings: no template engine dependency (tech debt).
 */
@Component
public class EmailRenderer {

  /** Renders an account email; missing data keys fall back to neutral text. */
  public EmailContent render(EmailTemplate template, Map<String, String> data) {
    Map<String, String> d = data == null ? Map.of() : data;
    String name = d.getOrDefault("name", "there");
    return switch (template) {
      case PASSWORD_RESET ->
          layout(
              "Reset your password",
              "Hi " + name + ",",
              "We received a request to reset your password. This link is valid for 30 minutes"
                  + " and can be used once. If you did not ask for this, ignore this email.",
              "Reset password",
              d.get("link"));
      case EMAIL_VERIFICATION ->
          layout(
              "Verify your email address",
              "Hi " + name + ",",
              "Confirm this is your email address to finish setting up your account. This link is"
                  + " valid for 24 hours.",
              "Verify email",
              d.get("link"));
      case OTP ->
          layout(
              "Your verification code",
              "Your code: " + d.getOrDefault("code", ""),
              "Use this code "
                  + d.getOrDefault("purpose", "to continue")
                  + ". It expires in "
                  + d.getOrDefault("minutes", "5")
                  + " minutes. Never share it with anyone.",
              null,
              null);
      case ALERT_PRICE_DROP ->
          layout(
              "Price drop: " + d.getOrDefault("productName", "an item you are watching"),
              "Hi " + name + ",",
              d.getOrDefault("productName", "An item you are watching")
                  + " is now "
                  + d.getOrDefault("currency", "")
                  + " "
                  + d.getOrDefault("price", "")
                  + (d.get("oldPrice") == null ? "" : " (was " + d.get("oldPrice") + ")")
                  + ". Grab it before the price changes again.",
              "View product",
              d.get("link"));
      case ALERT_BACK_IN_STOCK ->
          layout(
              "Back in stock: " + d.getOrDefault("productName", "an item you wanted"),
              "Hi " + name + ",",
              d.getOrDefault("productName", "An item you wanted")
                  + " is back in stock. Stock may be limited, so order soon.",
              "View product",
              d.get("link"));
      case GIFT_CARD ->
          layout(
              "You received a gift card",
              (d.get("from") == null ? "Hi " + name + "," : d.get("from") + " sent you a gift card"),
              "Gift card value: "
                  + d.getOrDefault("currency", "")
                  + " "
                  + d.getOrDefault("amount", "")
                  + "\nYour code: "
                  + d.getOrDefault("code", "")
                  + (d.get("message") == null ? "" : "\n\n\"" + d.get("message") + "\"")
                  + "\n\nRedeem it in your account under Wallet. It is valid for one year.",
              "Redeem gift card",
              d.get("link"));
      case CART_REMINDER ->
          layout(
              "You left something in your cart",
              "Hi " + name + ",",
              "These items are still waiting for you:\n"
                  + d.getOrDefault("items", "")
                  + "\n\nStock and prices can change, so check out soon.",
              "Return to cart",
              d.get("link"));
      case SUBSCRIPTION_ORDER_PLACED ->
          layout(
              "Your subscription order was placed",
              "Hi " + name + ",",
              "We placed your scheduled order for "
                  + d.getOrDefault("productName", "your subscribed item")
                  + ". Manage, skip or pause it any time from your account.",
              "Manage subscriptions",
              d.get("link"));
      case SUBSCRIPTION_FAILED ->
          layout(
              "We could not place your subscription order",
              "Hi " + name + ",",
              "Your scheduled order for "
                  + d.getOrDefault("productName", "your subscribed item")
                  + " failed"
                  + (d.get("reason") == null ? "" : " (" + d.get("reason") + ")")
                  + ". We will retry tomorrow; the subscription pauses after repeated failures.",
              "Manage subscriptions",
              d.get("link"));
      case MEMBERSHIP_WELCOME ->
          layout(
              "Welcome to Plus",
              "Hi " + name + ",",
              "Your "
                  + d.getOrDefault("plan", "Plus")
                  + " membership is active until "
                  + d.getOrDefault("endsAt", "")
                  + ". Enjoy free shipping, 2x coins and early access to deals.",
              "View membership",
              d.get("link"));
      case MEMBERSHIP_EXPIRED ->
          layout(
              "Your Plus membership has ended",
              "Hi " + name + ",",
              "Your membership has expired. Renew to keep free shipping, 2x coins and early deal"
                  + " access.",
              "Renew membership",
              d.get("link"));
      case REFERRAL_REWARD ->
          layout(
              "You earned referral coins",
              "Hi " + name + ",",
              "A friend you invited completed their first order. We added "
                  + d.getOrDefault("coins", "")
                  + " coins to your account.",
              "View coins",
              d.get("link"));
      case SELLER_APPROVED ->
          layout(
              "Your seller account is approved",
              "Hi " + name + ",",
              "Congratulations - "
                  + d.getOrDefault("storeName", "your store")
                  + " has been approved. You can now list products and receive orders.",
              "Open seller dashboard",
              d.get("link"));
      case SELLER_REJECTED ->
          layout(
              "Your seller application was not approved",
              "Hi " + name + ",",
              "We could not approve the application for "
                  + d.getOrDefault("storeName", "your store")
                  + (d.get("reason") == null ? "." : ": " + d.get("reason") + ".")
                  + " You can fix the details and apply again.",
              "Update application",
              d.get("link"));
    };
  }

  /** Renders an order-lifecycle (or plain) email. {@code ctaUrl} may be null. */
  public EmailContent order(
      String subject, String headline, String body, String ctaLabel, String ctaUrl) {
    return layout(subject, headline, body, ctaLabel, ctaUrl);
  }

  private static EmailContent layout(
      String subject, String headline, String body, String ctaLabel, String ctaUrl) {
    StringBuilder text = new StringBuilder();
    text.append(headline).append("\n\n").append(body).append('\n');
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><body style=\"margin:0;padding:24px;background:#f5f5f4;")
        .append("font-family:Arial,Helvetica,sans-serif;color:#111\">")
        .append("<table role=\"presentation\" width=\"100%\" style=\"max-width:520px;margin:0 auto;")
        .append("background:#fff;border:2px solid #111;border-radius:12px\"><tr><td")
        .append(" style=\"padding:28px\">")
        .append("<h1 style=\"font-size:20px;margin:0 0 12px\">")
        .append(HtmlUtils.htmlEscape(headline))
        .append("</h1><p style=\"font-size:15px;line-height:1.5;margin:0 0 20px\">")
        .append(HtmlUtils.htmlEscape(body).replace("\n", "<br>"))
        .append("</p>");
    if (ctaLabel != null && ctaUrl != null && !ctaUrl.isBlank()) {
      text.append('\n').append(ctaLabel).append(": ").append(ctaUrl).append('\n');
      html.append("<a href=\"")
          .append(HtmlUtils.htmlEscape(ctaUrl))
          .append("\" style=\"display:inline-block;padding:10px 20px;background:#111;color:#fff;")
          .append("text-decoration:none;border-radius:999px;font-weight:bold\">")
          .append(HtmlUtils.htmlEscape(ctaLabel))
          .append("</a><p style=\"font-size:12px;color:#666;margin:20px 0 0\">If the button does")
          .append(" not work, copy this link:<br>")
          .append(HtmlUtils.htmlEscape(ctaUrl))
          .append("</p>");
    }
    html.append("</td></tr></table></body></html>");
    return new EmailContent(subject, text.toString(), html.toString());
  }
}
