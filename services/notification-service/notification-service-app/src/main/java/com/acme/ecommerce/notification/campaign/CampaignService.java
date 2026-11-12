package com.acme.ecommerce.notification.campaign;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.notification.client.OrderFeignClient;
import com.acme.ecommerce.notification.client.UserFeignClient;
import com.acme.ecommerce.notification.client.UserFeignClient.Member;
import com.acme.ecommerce.notification.client.UserFeignClient.MemberPage;
import com.acme.ecommerce.notification.client.UserFeignClient.Preferences;
import com.acme.ecommerce.notification.inbox.InboxService;
import com.acme.ecommerce.notification.inbox.PreferenceLookup;
import com.acme.ecommerce.notification.mail.EmailContent;
import com.acme.ecommerce.notification.mail.EmailRenderer;
import com.acme.ecommerce.notification.mail.MailSenderService;
import com.acme.ecommerce.notification.push.PushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class CampaignService {

  private static final Logger log = LoggerFactory.getLogger(CampaignService.class);
  private static final int PAGE = 200;

  public record Campaign(
      UUID id,
      String name,
      String channel,
      String audience,
      String subject,
      String body,
      String link,
      String status,
      int sentCount,
      OffsetDateTime createdAt,
      OffsetDateTime sentAt) {}

  public record CampaignRequest(
      String name, String channel, String audience, String subject, String body, String link) {}

  private static final Set<String> CHANNELS = Set.of("EMAIL", "PUSH", "INBOX", "ALL");
  private static final Set<String> AUDIENCES = Set.of("ALL", "MEMBERS", "INACTIVE_30D");

  private final JdbcClient jdbc;
  private final UserFeignClient users;
  private final OrderFeignClient orders;
  private final InboxService inbox;
  private final PushService push;
  private final PreferenceLookup preferences;
  private final EmailRenderer renderer;
  private final MailSenderService mail;
  private final ObjectMapper mapper;
  private final String storefront;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public CampaignService(
      JdbcClient jdbc,
      UserFeignClient users,
      OrderFeignClient orders,
      InboxService inbox,
      PushService push,
      PreferenceLookup preferences,
      EmailRenderer renderer,
      MailSenderService mail,
      ObjectMapper mapper,
      @Value("${notification.storefront-base-url:http://localhost:3000}") String storefront) {
    this.jdbc = jdbc;
    this.users = users;
    this.orders = orders;
    this.inbox = inbox;
    this.push = push;
    this.preferences = preferences;
    this.renderer = renderer;
    this.mail = mail;
    this.mapper = mapper;
    this.storefront = storefront;
  }

  public List<Campaign> list() {
    return jdbc.sql("SELECT * FROM notification.campaigns ORDER BY created_at DESC LIMIT 200")
        .query(CampaignService::map)
        .list();
  }

  public Campaign get(UUID id) {
    return jdbc.sql("SELECT * FROM notification.campaigns WHERE id = :id")
        .param("id", id)
        .query(CampaignService::map)
        .optional()
        .orElseThrow(() -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Campaign not found"));
  }

  public Campaign create(CampaignRequest r) {
    if (r.name() == null || r.name().isBlank() || r.subject() == null || r.subject().isBlank()
        || r.body() == null || r.body().isBlank()) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "name, subject and body are required");
    }
    String channel = r.channel() == null ? "ALL" : r.channel().toUpperCase();
    String audience = r.audience() == null ? "ALL" : r.audience().toUpperCase();
    if (!CHANNELS.contains(channel) || !AUDIENCES.contains(audience)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Invalid channel or audience");
    }
    UUID id =
        jdbc.sql(
                "INSERT INTO notification.campaigns (name, channel, audience, subject, body, link)"
                    + " VALUES (:n, :c, :a, :s, :b, :l) RETURNING id")
            .param("n", r.name().trim())
            .param("c", channel)
            .param("a", audience)
            .param("s", r.subject().trim())
            .param("b", r.body())
            .param("l", r.link())
            .query(UUID.class)
            .single();
    return get(id);
  }

  /** Marks the campaign SENDING and delivers on a background virtual thread. */
  public Campaign send(UUID id) {
    int n =
        jdbc.sql(
                "UPDATE notification.campaigns SET status = 'SENDING' WHERE id = :id AND status ="
                    + " 'DRAFT'")
            .param("id", id)
            .update();
    Campaign c = get(id);
    if (n == 0) {
      throw new CodedException(ErrorCode.RESOURCE_CONFLICT, "Campaign was already sent");
    }
    executor.submit(() -> run(c));
    return c;
  }

  private void run(Campaign c) {
    int reached = 0;
    try {
      int page = 0;
      boolean last = false;
      while (!last) {
        MemberPage p = users.audience("MEMBERS".equals(c.audience()) ? "MEMBERS" : "ALL", page, PAGE);
        List<Member> members = p.content() == null ? List.of() : p.content();
        List<Member> targets =
            "INACTIVE_30D".equals(c.audience()) ? inactiveOnly(members) : members;
        for (Member m : targets) {
          try {
            if (deliver(c, m)) {
              reached++;
            }
          } catch (RuntimeException e) {
            log.warn("Campaign {} delivery to {} failed: {}", c.id(), m.userId(), e.toString());
          }
        }
        last = p.last() || members.isEmpty();
        page++;
      }
    } catch (RuntimeException e) {
      log.error("Campaign {} aborted: {}", c.id(), e.toString());
    }
    jdbc.sql(
            "UPDATE notification.campaigns SET status = 'SENT', sent_count = :n, sent_at = now()"
                + " WHERE id = :id")
        .param("n", reached)
        .param("id", c.id())
        .update();
    log.info("Campaign {} sent to {} users", c.id(), reached);
  }

  private boolean deliver(Campaign c, Member m) {
    Preferences p = preferences.get(m.userId());
    boolean all = "ALL".equals(c.channel());
    boolean any = false;
    String link = c.link() == null || c.link().isBlank() ? null : c.link();
    if (all || "INBOX".equals(c.channel())) {
      inbox.add(m.userId(), "PROMO", c.subject(), c.body(), link, null);
      any = true;
    }
    if ((all || "EMAIL".equals(c.channel())) && p.emailPromotions() && m.email() != null) {
      String cta = link == null ? null : (link.startsWith("http") ? link : storefront + link);
      EmailContent content = renderer.order(c.subject(), c.subject(), c.body(), cta == null ? null : "Open", cta);
      mail.send(m.email(), content.subject(), content.text(), content.html());
      any = true;
    }
    if ((all || "PUSH".equals(c.channel())) && p.pushPromotions()) {
      any |= push.sendToUser(m.userId(), c.subject(), c.body(), link) > 0;
    }
    return any;
  }

  /** Keeps members with no order in the last 30 days. Fails closed (nobody) if order data is unavailable. */
  private List<Member> inactiveOnly(List<Member> members) {
    if (members.isEmpty()) {
      return members;
    }
    Map<UUID, Instant> last = new HashMap<>();
    try {
      String ids = members.stream().map(m -> m.userId().toString()).collect(Collectors.joining(","));
      parseLastOrders(mapper.valueToTree(orders.lastOrderDates(ids)), last);
    } catch (RuntimeException e) {
      log.warn("last-order-dates unavailable, skipping INACTIVE_30D batch: {}", e.toString());
      return List.of();
    }
    Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
    List<Member> out = new ArrayList<>();
    for (Member m : members) {
      Instant at = last.get(m.userId());
      if (at == null || at.isBefore(cutoff)) {
        out.add(m);
      }
    }
    return out;
  }

  private static void parseLastOrders(JsonNode node, Map<UUID, Instant> out) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      node.fields()
          .forEachRemaining(
              e -> {
                Instant at = instantOf(e.getValue());
                UUID id = uuid(e.getKey());
                if (id != null && at != null) {
                  out.put(id, at);
                }
              });
    } else if (node.isArray()) {
      for (JsonNode item : node) {
        UUID id = uuid(item.path("userId").asText(null));
        Instant at = null;
        for (var it = item.fields(); it.hasNext() && at == null; ) {
          var f = it.next();
          if (!"userId".equals(f.getKey())) {
            at = instantOf(f.getValue());
          }
        }
        if (id != null && at != null) {
          out.put(id, at);
        }
      }
    }
  }

  private static UUID uuid(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Instant instantOf(JsonNode v) {
    if (v == null || v.isNull()) {
      return null;
    }
    if (v.isObject()) {
      for (var it = v.fields(); it.hasNext(); ) {
        Instant at = instantOf(it.next().getValue());
        if (at != null) {
          return at;
        }
      }
      return null;
    }
    if (v.isNumber()) {
      return Instant.ofEpochMilli(v.asLong() < 10_000_000_000L ? v.asLong() * 1000 : v.asLong());
    }
    String s = v.asText("");
    try {
      return Instant.parse(s);
    } catch (RuntimeException e) {
      // fall through
    }
    try {
      return OffsetDateTime.parse(s).toInstant();
    } catch (RuntimeException e) {
      // fall through
    }
    try {
      return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
    } catch (RuntimeException e) {
      return Instant.now(); // unparseable => treat as recently active (fail closed)
    }
  }

  private static Campaign map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Campaign(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("channel"),
        rs.getString("audience"),
        rs.getString("subject"),
        rs.getString("body"),
        rs.getString("link"),
        rs.getString("status"),
        rs.getInt("sent_count"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("sent_at", OffsetDateTime.class));
  }
}
