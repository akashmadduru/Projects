package com.acme.ecommerce.support.ticket;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.UpstreamUnavailableException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.support.Paged;
import com.acme.ecommerce.support.client.NotificationFeignClient;
import com.acme.ecommerce.support.client.NotificationFeignClient.EmailBody;
import com.acme.ecommerce.support.client.OrderFeignClient;
import com.acme.ecommerce.support.client.OrderFeignClient.OrderBrief;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tickets, messages, CSAT, agent queue, SLA/auto-priority and email notifications. */
@Service
public class TicketService {

  private static final Logger log = LoggerFactory.getLogger(TicketService.class);
  static final Set<String> CATEGORIES =
      Set.of("ORDERS", "PAYMENTS", "RETURNS", "ACCOUNT", "SELLERS", "OTHER");
  static final Set<String> STATUSES =
      Set.of("OPEN", "PENDING_CUSTOMER", "PENDING_AGENT", "RESOLVED", "CLOSED");
  static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
  private static final BigDecimal HIGH_VALUE_INR = new BigDecimal("10000");

  public record CreateRequest(
      UUID orderId, UUID shipmentId, String category, String subject, String body) {}

  public record MessageRequest(String body, Boolean internal) {}

  public record AssignRequest(String userId, Boolean me) {}

  public record StatusRequest(String status, String priority) {}

  public record CsatRequest(int score) {}

  public record Message(
      UUID id, String authorType, UUID authorId, String body, boolean internal, Instant createdAt) {}

  public record Ticket(
      UUID id,
      String ref,
      UUID userId,
      String email,
      UUID orderId,
      UUID shipmentId,
      String category,
      String subject,
      String status,
      String priority,
      UUID assigneeUserId,
      Instant createdAt,
      Instant updatedAt,
      Instant firstResponseAt,
      Instant resolvedAt,
      Instant firstResponseDueAt,
      Instant resolutionDueAt,
      boolean slaBreached,
      Integer csat,
      List<Message> messages) {}

  private final JdbcTemplate jdbc;
  private final OrderFeignClient orders;
  private final NotificationFeignClient notifications;
  private final String storefrontUrl;

  public TicketService(
      JdbcTemplate jdbc,
      OrderFeignClient orders,
      NotificationFeignClient notifications,
      @Value("${support.storefront-url}") String storefrontUrl) {
    this.jdbc = jdbc;
    this.orders = orders;
    this.notifications = notifications;
    this.storefrontUrl = storefrontUrl;
  }

  // ---------------------------------------------------------------- customer

  @Transactional
  public Ticket create(UUID userId, String email, CreateRequest r) {
    if (r.subject() == null || r.subject().isBlank()) {
      throw ValidationException.forField("subject", "Required");
    }
    if (r.subject().length() > 200) {
      throw ValidationException.forField("subject", "At most 200 characters");
    }
    String body = requireBody(r.body());
    String category = r.category() == null ? "OTHER" : r.category().toUpperCase(Locale.ROOT);
    if (!CATEGORIES.contains(category)) {
      throw ValidationException.forField("category", "Must be one of " + CATEGORIES);
    }
    if (email == null || email.isBlank()) {
      throw ValidationException.forField("email", "Your account has no email address");
    }
    BigDecimal orderTotal = null;
    String orderCurrency = null;
    if (r.orderId() != null) {
      OrderBrief o = lookupOrder(r.orderId());
      if (!userId.equals(o.userId())) {
        // Foreign orders look exactly like missing ones.
        throw new ResourceNotFoundException("Order", r.orderId());
      }
      orderTotal = o.totalAmount();
      orderCurrency = o.currency();
    }
    String priority = autoPriority(category, orderTotal, orderCurrency);
    Instant now = Instant.now();
    Duration[] sla = sla(priority);
    UUID id =
        jdbc.queryForObject(
            "insert into tickets (user_id, email, order_id, shipment_id, category, subject, status,"
                + " priority, first_response_due_at, resolution_due_at, created_at, updated_at)"
                + " values (?,?,?,?,?,?,'OPEN',?,?,?,?,?) returning id",
            UUID.class,
            userId,
            email,
            r.orderId(),
            r.shipmentId(),
            category,
            r.subject().trim(),
            priority,
            Timestamp.from(now.plus(sla[0])),
            Timestamp.from(now.plus(sla[1])),
            Timestamp.from(now),
            Timestamp.from(now));
    addMessage(id, "CUSTOMER", userId, body, false);
    return withMessages(get(id), false);
  }

  public Paged<Ticket> listMine(UUID userId, String status, int page, int size) {
    int sz = Paged.clampSize(size);
    List<Object> args = new ArrayList<>();
    String where = " where user_id = ?";
    args.add(userId);
    if (status != null && !status.isBlank()) {
      where += " and status = ?";
      args.add(status.toUpperCase(Locale.ROOT));
    }
    return page(where, args, "created_at desc", page, sz);
  }

  public Ticket getMine(UUID userId, UUID id) {
    Ticket t = get(id);
    if (!t.userId().equals(userId)) {
      throw new ResourceNotFoundException("Ticket", id);
    }
    return withMessages(t, false);
  }

  @Transactional
  public Ticket customerMessage(UUID userId, UUID id, String body) {
    Ticket t = getMine(userId, id);
    if ("CLOSED".equals(t.status())) {
      throw new ResourceConflictException("This ticket is closed; please open a new one");
    }
    addMessage(id, "CUSTOMER", userId, requireBody(body), false);
    // A customer reply always puts the ball back in the agents' court (reopens RESOLVED).
    jdbc.update(
        "update tickets set status = 'PENDING_AGENT', resolved_at = null, updated_at = now() where id = ?",
        id);
    return getMine(userId, id);
  }

  @Transactional
  public Ticket close(UUID userId, UUID id) {
    Ticket t = getMine(userId, id);
    if (!"CLOSED".equals(t.status())) {
      jdbc.update(
          "update tickets set status = 'CLOSED', resolved_at = coalesce(resolved_at, now()),"
              + " updated_at = now() where id = ?",
          id);
      addMessage(id, "SYSTEM", null, "Ticket closed by the customer.", false);
    }
    return getMine(userId, id);
  }

  @Transactional
  public Ticket csat(UUID userId, UUID id, int score) {
    Ticket t = getMine(userId, id);
    if (score < 1 || score > 5) {
      throw ValidationException.forField("score", "Must be 1-5");
    }
    if (!"RESOLVED".equals(t.status()) && !"CLOSED".equals(t.status())) {
      throw new ResourceConflictException("You can rate support once the ticket is resolved");
    }
    if (t.csat() != null) {
      throw new ResourceConflictException("This ticket was already rated");
    }
    jdbc.update("update tickets set csat = ?, updated_at = now() where id = ?", score, id);
    return getMine(userId, id);
  }

  // ------------------------------------------------------------------- agent

  public Paged<Ticket> queue(
      String status, String priority, String assignee, String q, UUID callerId, int page, int size) {
    int sz = Paged.clampSize(size);
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      args.add(status.toUpperCase(Locale.ROOT));
    }
    if (priority != null && !priority.isBlank()) {
      where.append(" and priority = ?");
      args.add(priority.toUpperCase(Locale.ROOT));
    }
    if (assignee != null && !assignee.isBlank()) {
      if ("unassigned".equalsIgnoreCase(assignee)) {
        where.append(" and assignee_user_id is null");
      } else {
        where.append(" and assignee_user_id = ?");
        args.add("me".equalsIgnoreCase(assignee) ? callerId : parseUuid(assignee, "assignee"));
      }
    }
    if (q != null && !q.isBlank()) {
      String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "") + "%";
      where.append(" and (lower(subject) like ? or lower(email) like ? or id::text like ?)");
      args.add(like);
      args.add(like);
      args.add(like);
    }
    // Open work first, then by urgency, then oldest.
    String order =
        "(case when status in ('RESOLVED','CLOSED') then 1 else 0 end),"
            + " (case priority when 'URGENT' then 0 when 'HIGH' then 1 when 'NORMAL' then 2 else 3 end),"
            + " created_at";
    return page(where.toString(), args, order, page, sz);
  }

  public Ticket agentGet(UUID id) {
    return withMessages(get(id), true);
  }

  @Transactional
  public Ticket assign(UUID id, String userIdOrMe, UUID callerId) {
    get(id);
    UUID assignee =
        userIdOrMe == null || userIdOrMe.isBlank() || "me".equalsIgnoreCase(userIdOrMe)
            ? callerId
            : parseUuid(userIdOrMe, "userId");
    jdbc.update("update tickets set assignee_user_id = ?, updated_at = now() where id = ?", assignee, id);
    addMessage(id, "SYSTEM", callerId, "Ticket assigned to agent " + assignee + ".", true);
    return agentGet(id);
  }

  @Transactional
  public Ticket agentMessage(UUID id, UUID agentId, String body, boolean internal) {
    Ticket t = get(id);
    addMessage(id, "AGENT", agentId, requireBody(body), internal);
    if (internal) {
      jdbc.update("update tickets set updated_at = now() where id = ?", id);
      return agentGet(id);
    }
    jdbc.update(
        "update tickets set status = case when status in ('CLOSED') then status else 'PENDING_CUSTOMER' end,"
            + " first_response_at = coalesce(first_response_at, now()),"
            + " assignee_user_id = coalesce(assignee_user_id, ?), updated_at = now() where id = ?",
        agentId,
        id);
    notify(
        t,
        "SUPPORT_TICKET_REPLY",
        Map.of("message", body.length() > 1500 ? body.substring(0, 1500) + "..." : body));
    return agentGet(id);
  }

  @Transactional
  public Ticket setStatus(UUID id, UUID agentId, StatusRequest r) {
    Ticket t = get(id);
    if (r.priority() != null) {
      String p = r.priority().toUpperCase(Locale.ROOT);
      if (!PRIORITIES.contains(p)) {
        throw ValidationException.forField("priority", "Must be one of " + PRIORITIES);
      }
      jdbc.update("update tickets set priority = ?, updated_at = now() where id = ?", p, id);
    }
    if (r.status() != null) {
      String s = r.status().toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(s)) {
        throw ValidationException.forField("status", "Must be one of " + STATUSES);
      }
      if (!s.equals(t.status())) {
        jdbc.update(
            "update tickets set status = ?, updated_at = now(),"
                + " resolved_at = case when ? in ('RESOLVED','CLOSED') then coalesce(resolved_at, now()) else null end"
                + " where id = ?",
            s,
            s,
            id);
        addMessage(id, "SYSTEM", agentId, "Status changed from " + t.status() + " to " + s + ".", true);
        notify(t, "SUPPORT_STATUS", Map.of("status", pretty(s)));
      }
    }
    return agentGet(id);
  }

  // ----------------------------------------------------------------- shared

  /** Used by the chatbot hand-off: creates a ticket whose first message is the transcript. */
  public String ref(UUID id) {
    return "T-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  Ticket get(UUID id) {
    return jdbc.query("select * from tickets where id = ?", TICKET, id).stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Ticket", id));
  }

  private Ticket withMessages(Ticket t, boolean includeInternal) {
    List<Message> msgs =
        jdbc.query(
            "select * from ticket_messages where ticket_id = ?"
                + (includeInternal ? "" : " and not internal")
                + " order by created_at, id",
            (rs, n) ->
                new Message(
                    rs.getObject("id", UUID.class),
                    rs.getString("author_type"),
                    rs.getObject("author_id", UUID.class),
                    rs.getString("body"),
                    rs.getBoolean("internal"),
                    rs.getTimestamp("created_at").toInstant()),
            t.id());
    return new Ticket(
        t.id(), t.ref(), t.userId(), t.email(), t.orderId(), t.shipmentId(), t.category(),
        t.subject(), t.status(), t.priority(), t.assigneeUserId(), t.createdAt(), t.updatedAt(),
        t.firstResponseAt(), t.resolvedAt(), t.firstResponseDueAt(), t.resolutionDueAt(),
        t.slaBreached(), t.csat(), msgs);
  }

  private final RowMapper<Ticket> TICKET =
      (rs, n) -> {
        Instant frDue = rs.getTimestamp("first_response_due_at").toInstant();
        Instant resDue = rs.getTimestamp("resolution_due_at").toInstant();
        Timestamp fr = rs.getTimestamp("first_response_at");
        Timestamp res = rs.getTimestamp("resolved_at");
        String status = rs.getString("status");
        Instant now = Instant.now();
        boolean open = !"RESOLVED".equals(status) && !"CLOSED".equals(status);
        boolean breached =
            (fr == null && open && now.isAfter(frDue)) || (res == null && open && now.isAfter(resDue));
        UUID id = rs.getObject("id", UUID.class);
        Number csat = (Number) rs.getObject("csat");
        return new Ticket(
            id,
            ref(id),
            rs.getObject("user_id", UUID.class),
            rs.getString("email"),
            rs.getObject("order_id", UUID.class),
            rs.getObject("shipment_id", UUID.class),
            rs.getString("category"),
            rs.getString("subject"),
            status,
            rs.getString("priority"),
            rs.getObject("assignee_user_id", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            fr == null ? null : fr.toInstant(),
            res == null ? null : res.toInstant(),
            frDue,
            resDue,
            breached,
            csat == null ? null : csat.intValue(),
            null);
      };

  private Paged<Ticket> page(String where, List<Object> args, String order, int page, int size) {
    Long total = jdbc.queryForObject("select count(*) from tickets" + where, Long.class, args.toArray());
    List<Object> a = new ArrayList<>(args);
    a.add(size);
    a.add((long) Math.max(0, page) * size);
    List<Ticket> rows =
        jdbc.query(
            "select * from tickets" + where + " order by " + order + " limit ? offset ?",
            TICKET,
            a.toArray());
    return Paged.of(rows, Math.max(0, page), size, total == null ? 0 : total);
  }

  private void addMessage(UUID ticketId, String type, UUID authorId, String body, boolean internal) {
    jdbc.update(
        "insert into ticket_messages (ticket_id, author_type, author_id, body, internal) values (?,?,?,?,?)",
        ticketId,
        type,
        authorId,
        body,
        internal);
  }

  private OrderBrief lookupOrder(UUID orderId) {
    try {
      return orders.brief(orderId);
    } catch (feign.FeignException.NotFound e) {
      throw new ResourceNotFoundException("Order", orderId);
    } catch (feign.FeignException e) {
      throw new UpstreamUnavailableException("order-service", e);
    }
  }

  static String autoPriority(String category, BigDecimal total, String currency) {
    if ("PAYMENTS".equals(category)) {
      return "HIGH";
    }
    if (total != null && "INR".equalsIgnoreCase(currency) && total.compareTo(HIGH_VALUE_INR) > 0) {
      return "HIGH";
    }
    return "NORMAL";
  }

  /** [first response, resolution] targets. */
  static Duration[] sla(String priority) {
    return switch (priority) {
      case "URGENT" -> new Duration[] {Duration.ofHours(1), Duration.ofHours(4)};
      case "HIGH" -> new Duration[] {Duration.ofHours(4), Duration.ofHours(24)};
      case "LOW" -> new Duration[] {Duration.ofHours(48), Duration.ofHours(120)};
      default -> new Duration[] {Duration.ofHours(24), Duration.ofHours(72)};
    };
  }

  private void notify(Ticket t, String template, Map<String, String> extra) {
    try {
      Map<String, String> data = new java.util.HashMap<>(extra);
      data.put("name", t.email().contains("@") ? t.email().substring(0, t.email().indexOf('@')) : "there");
      data.put("ticketRef", t.ref());
      data.put("subject", t.subject());
      data.put("link", storefrontUrl + "/account/support/" + t.id());
      notifications.sendEmail(new EmailBody(t.email(), template, data));
    } catch (RuntimeException e) {
      // Fail-open: a mail problem must never fail the agent's action.
      log.warn("Could not send {} email for ticket {}: {}", template, t.ref(), e.toString());
    }
  }

  private static String pretty(String s) {
    return s.replace('_', ' ').toLowerCase(Locale.ROOT);
  }

  private static String requireBody(String body) {
    if (body == null || body.isBlank()) {
      throw ValidationException.forField("body", "Required");
    }
    if (body.length() > 5000) {
      throw ValidationException.forField("body", "At most 5000 characters");
    }
    return body.strip();
  }

  private static UUID parseUuid(String s, String field) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw ValidationException.forField(field, "Not a valid id");
    }
  }
}
