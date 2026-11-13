package com.acme.ecommerce.support.chat;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.support.client.OrderFeignClient;
import com.acme.ecommerce.support.client.OrderFeignClient.OrderBrief;
import com.acme.ecommerce.support.help.ArticleService;
import com.acme.ecommerce.support.help.ArticleService.Article;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Rule-based assistant: intent keywords, FAQ keyword scoring, signed-in order lookup, hand-off to a
 * ticket on request or after two low-confidence answers. No LLM (tech debt).
 */
@Service
public class ChatService {

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{8,64}");
  private static final Set<String> STOP =
      Set.of("the", "and", "for", "how", "can", "what", "does", "this", "that", "with", "you", "your",
          "are", "was", "has", "have", "not", "get", "did", "when", "where", "why", "who",
          "please", "need", "want", "know", "about", "any", "its", "from", "will", "would", "could");
  private static final int MIN_SCORE = 5;

  public record ChatRequest(String sessionId, String message) {}

  public record ArticleRef(String slug, String title) {}

  public record Action(String type, UUID orderId, String subject, String transcript) {}

  public record ChatResponse(
      String reply, List<String> suggestions, List<ArticleRef> articles, Action action) {}

  private final JdbcTemplate jdbc;
  private final ArticleService articles;
  private final OrderFeignClient orders;

  public ChatService(JdbcTemplate jdbc, ArticleService articles, OrderFeignClient orders) {
    this.jdbc = jdbc;
    this.articles = articles;
    this.orders = orders;
  }

  public ChatResponse chat(UUID userId, ChatRequest r) {
    if (r.sessionId() == null || !SESSION.matcher(r.sessionId()).matches()) {
      throw ValidationException.forField("sessionId", "8-64 characters: letters, digits, - or _");
    }
    if (r.message() == null || r.message().isBlank()) {
      throw ValidationException.forField("message", "Required");
    }
    String msg = r.message().strip();
    if (msg.length() > 500) {
      msg = msg.substring(0, 500);
    }
    jdbc.update(
        "insert into chat_sessions (session_id, user_id) values (?, ?)"
            + " on conflict (session_id) do update set user_id = coalesce(chat_sessions.user_id, excluded.user_id),"
            + " updated_at = now()",
        r.sessionId(),
        userId);
    save(r.sessionId(), "USER", msg);
    ChatResponse resp = answer(userId, r.sessionId(), msg.toLowerCase(Locale.ROOT));
    save(r.sessionId(), "BOT", resp.reply());
    return resp;
  }

  private ChatResponse answer(UUID userId, String session, String m) {
    if (has(m, "human", "agent", "real person", "representative", "talk to someone", "speak to",
        "talk to a", "customer care", "complaint", "support team")) {
      return handoff(session, "Sure - I will pass this to our support team. Tap below to open a ticket;"
          + " the chat so far is attached so you do not need to repeat yourself.");
    }
    if (has(m, "hello", "hi", "hey", "good morning", "good evening") && m.length() < 20) {
      reset(session);
      return plain("Hi! I can track your order, explain returns and refunds, payments, coupons and"
          + " more. What do you need help with?", defaultSuggestions());
    }
    if (has(m, "thank", "thanks", "bye")) {
      reset(session);
      return plain("You are welcome! Anything else I can help with?", List.of("Track my order"));
    }
    if (has(m, "track", "where is my order", "where's my order", "order status", "delivery status",
        "when will my order")) {
      reset(session);
      return trackOrder(userId);
    }
    if (has(m, "cancel")) {
      reset(session);
      return withArticle("cancel-an-order",
          "You can cancel an order until it ships (Pending payment, Placed, Confirmed or Packed)."
              + " Open it from My orders and choose Cancel order.",
          List.of("Track my order", "Talk to a human"), latestOrderAction(userId, "TRACK_ORDER", false));
    }
    if (has(m, "payment failed", "money deducted", "amount deducted", "payment declined", "charged twice",
        "transaction failed", "payment not")) {
      reset(session);
      return withArticle("payment-failed-or-money-deducted",
          "If a payment failed no order is confirmed and any held amount returns to your bank in 5-7"
              + " business days. If money was deducted but the order is cancelled it is refunded to"
              + " your original payment method.",
          List.of("Talk to a human", "Refund status"), null);
    }
    if (has(m, "return", "replace", "send back", "damaged", "wrong item", "defective")) {
      reset(session);
      return withArticle("return-policy",
          "You can return delivered items within 7 days of delivery. Open the delivered order and"
              + " choose Return items.",
          List.of("When will I get my refund?", "Talk to a human"),
          latestOrderAction(userId, "START_RETURN", true));
    }
    if (has(m, "refund", "money back")) {
      reset(session);
      return withArticle("refund-timeline-and-method",
          "Refunds go to your original payment method (or your wallet for wallet/COD orders). Card"
              + " refunds usually show in 5-7 business days.",
          List.of("Track my order", "Talk to a human"), null);
    }
    if (has(m, "coupon", "promo", "voucher", "discount code")) {
      reset(session);
      return withArticle("using-coupons",
          "Enter the code in the Coupon box at checkout. A code can have a minimum order value, an"
              + " expiry or a usage limit - the message shown tells you which.",
          List.of("Talk to a human"), null);
    }
    if (has(m, "membership", "plus member", "plus plan")) {
      reset(session);
      return withArticle("plus-membership",
          "Plus gives free shipping on every order, 2x reward coins and early access to deals.",
          List.of("How do reward coins work?"), null);
    }

    // FAQ match.
    List<Scored> hits = match(m);
    if (!hits.isEmpty() && hits.get(0).score >= MIN_SCORE) {
      reset(session);
      Article top = hits.get(0).article;
      List<ArticleRef> refs = hits.stream().limit(3).map(s -> ref(s.article)).toList();
      return new ChatResponse(snippet(top), List.of("Talk to a human"), refs, null);
    }
    int misses =
        jdbc.queryForObject(
            "update chat_sessions set low_conf = low_conf + 1 where session_id = ? returning low_conf",
            Integer.class,
            session);
    if (misses >= 2) {
      return handoff(session, "I am still not sure I understand. Let me connect you with our support team"
          + " - open a ticket below and the conversation will be attached.");
    }
    return plain("Sorry, I did not quite get that. Could you rephrase, or pick one of these?",
        defaultSuggestions());
  }

  private ChatResponse trackOrder(UUID userId) {
    if (userId == null) {
      return plain("Sign in and I can look up your latest orders. You can also open My orders at any time.",
          List.of("How do I track my order?", "Talk to a human"));
    }
    List<OrderBrief> list = recentOrders(userId);
    if (list == null) {
      return plain("I could not reach the order system just now. Please try again in a moment or open"
          + " My orders.", List.of("Talk to a human"));
    }
    if (list.isEmpty()) {
      return plain("I do not see any orders on your account yet.", List.of("Shipping charges and delivery time"));
    }
    OrderBrief o = list.get(0);
    StringBuilder sb = new StringBuilder("Your latest order #")
        .append(o.id().toString(), 0, 8).append(" is ").append(pretty(o.status())).append('.');
    if (o.trackingNumber() != null) {
      sb.append(" Tracking ").append(o.carrier() == null ? "" : o.carrier() + " ").append(o.trackingNumber()).append('.');
    }
    if (o.estimatedDeliveryDate() != null && !"DELIVERED".equals(o.status()) && !"CANCELLED".equals(o.status())) {
      sb.append(" Estimated delivery: ").append(o.estimatedDeliveryDate()).append('.');
    }
    if (list.size() > 1) {
      sb.append(" Your previous orders: ");
      for (int i = 1; i < list.size() && i < 4; i++) {
        sb.append('#').append(list.get(i).id().toString(), 0, 8).append(" (")
            .append(pretty(list.get(i).status())).append(i < Math.min(list.size(), 4) - 1 ? "), " : ")");
      }
      sb.append('.');
    }
    return new ChatResponse(sb.toString(), List.of("Cancel my order", "Return an item", "Talk to a human"),
        List.of(), new Action("TRACK_ORDER", o.id(), null, null));
  }

  private Action latestOrderAction(UUID userId, String type, boolean deliveredOnly) {
    if (userId == null) {
      return null;
    }
    List<OrderBrief> list = recentOrders(userId);
    if (list == null) {
      return null;
    }
    Optional<OrderBrief> o =
        list.stream().filter(x -> !deliveredOnly || "DELIVERED".equals(x.status())).findFirst();
    return o.map(x -> new Action(type, x.id(), null, null)).orElse(null);
  }

  private List<OrderBrief> recentOrders(UUID userId) {
    try {
      return orders.byUser(userId, 5);
    } catch (RuntimeException e) {
      log.warn("Order lookup failed for chat: {}", e.toString());
      return null;
    }
  }

  private ChatResponse handoff(String session, String reply) {
    reset(session);
    String transcript = transcript(session);
    return new ChatResponse(reply, List.of(), List.of(),
        new Action("CREATE_TICKET", null, "Chat hand-off", transcript));
  }

  private String transcript(String session) {
    List<String> lines =
        jdbc.query(
            "select role, text from (select id, role, text from chat_messages where session_id = ?"
                + " order by id desc limit 20) t order by id",
            (rs, n) -> ("USER".equals(rs.getString("role")) ? "Customer: " : "Assistant: ") + rs.getString("text"),
            session);
    return String.join("\n", lines);
  }

  private ChatResponse withArticle(String slug, String reply, List<String> suggestions, Action action) {
    List<ArticleRef> refs = new ArrayList<>();
    articles.published().stream().filter(a -> a.slug().equals(slug)).findFirst().ifPresent(a -> refs.add(ref(a)));
    return new ChatResponse(reply, suggestions, refs, action);
  }

  private static ChatResponse plain(String reply, List<String> suggestions) {
    return new ChatResponse(reply, suggestions, List.of(), null);
  }

  private static List<String> defaultSuggestions() {
    return List.of("Track my order", "Return policy", "Payment failed", "Use a coupon", "Talk to a human");
  }

  private void reset(String session) {
    jdbc.update("update chat_sessions set low_conf = 0 where session_id = ?", session);
  }

  private void save(String session, String role, String text) {
    jdbc.update("insert into chat_messages (session_id, role, text) values (?,?,?)", session, role,
        text.length() > 2000 ? text.substring(0, 2000) : text);
  }

  private static boolean has(String m, String... needles) {
    for (String n : needles) {
      if (n.length() <= 3 ? Pattern.compile("\\b" + Pattern.quote(n) + "\\b").matcher(m).find() : m.contains(n)) {
        return true;
      }
    }
    return false;
  }

  private static ArticleRef ref(Article a) {
    return new ArticleRef(a.slug(), a.title());
  }

  private static String pretty(String s) {
    return s == null ? "unknown" : s.toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static String snippet(Article a) {
    String plain = a.body().replace("**", "").replaceAll("\\s+", " ").trim();
    return plain.length() > 320 ? plain.substring(0, 320).replaceAll("\\s\\S*$", "") + "..." : plain;
  }

  private record Scored(Article article, int score) {}

  private List<Scored> match(String m) {
    Set<String> tokens = tokens(m);
    if (tokens.isEmpty()) {
      return List.of();
    }
    List<Scored> out = new ArrayList<>();
    for (Article a : articles.published()) {
      int score = 0;
      Set<String> title = tokens(a.title().toLowerCase(Locale.ROOT));
      for (String t : tokens) {
        if (title.contains(t)) {
          score += 3;
        }
      }
      for (String k : a.keywords()) {
        String kw = k.toLowerCase(Locale.ROOT);
        if (kw.contains(" ") && m.contains(kw)) {
          score += 6;
        } else if (tokens(kw).stream().anyMatch(tokens::contains)) {
          score += 4;
        }
      }
      Set<String> body = tokens(a.body().toLowerCase(Locale.ROOT));
      for (String t : tokens) {
        if (body.contains(t)) {
          score += 1;
        }
      }
      if (score > 0) {
        out.add(new Scored(a, score));
      }
    }
    out.sort((x, y) -> Integer.compare(y.score, x.score));
    return out;
  }

  private static Set<String> tokens(String s) {
    Set<String> out = new HashSet<>();
    for (String w : s.split("[^a-z0-9]+")) {
      if (w.length() < 3 || STOP.contains(w)) {
        continue;
      }
      out.add(stem(w));
    }
    return out;
  }

  private static String stem(String w) {
    for (String suf : new String[] {"ing", "ed", "es", "s"}) {
      if (w.length() > suf.length() + 3 && w.endsWith(suf)) {
        return w.substring(0, w.length() - suf.length());
      }
    }
    return w;
  }

}
