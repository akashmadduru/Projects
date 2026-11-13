package com.acme.ecommerce.support.audit;

import com.acme.ecommerce.support.Paged;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** audit_log writer (tolerant parser of the audit.events payload) and reader. */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  public record Entry(
      UUID id,
      UUID actorUserId,
      String actorRoles,
      String action,
      String resourceType,
      String resourceId,
      Object before,
      Object after,
      String ip,
      Instant at) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** Stores one raw event; malformed events are logged and dropped (never retried). */
  public void ingest(String json) {
    try {
      JsonNode n = mapper.readTree(json);
      if (n == null || !n.hasNonNull("action")) {
        log.warn("Skipping audit event without action");
        return;
      }
      UUID id = uuid(text(n, "eventId"));
      if (id == null) {
        id = UUID.randomUUID();
      }
      jdbc.update(
          "insert into audit_log (id, actor_user_id, actor_roles, action, resource_type, resource_id,"
              + " before_json, after_json, ip, at) values (?,?,?,?,?,?,?,?,?,?) on conflict (id) do nothing",
          id,
          uuid(text(n, "actorUserId")),
          roles(n.get("actorRoles")),
          cut(text(n, "action"), 80),
          cut(text(n, "resourceType"), 80),
          cut(text(n, "resourceId"), 80),
          n.hasNonNull("before") ? n.get("before").toString() : null,
          n.hasNonNull("after") ? n.get("after").toString() : null,
          cut(text(n, "ip"), 64),
          Timestamp.from(parseAt(n.get("at"))));
    } catch (Exception e) {
      log.warn("Could not store audit event: {}", e.toString());
    }
  }

  public Paged<Entry> search(
      String actor, String resourceType, String resourceId, String action, Instant from, Instant to,
      int page, int size) {
    int sz = Paged.clampSize(size);
    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> args = new ArrayList<>();
    if (actor != null && !actor.isBlank()) {
      UUID a = uuid(actor);
      if (a == null) {
        return Paged.of(List.of(), 0, sz, 0);
      }
      where.append(" and actor_user_id = ?");
      args.add(a);
    }
    if (resourceType != null && !resourceType.isBlank()) {
      where.append(" and resource_type = ?");
      args.add(resourceType);
    }
    if (resourceId != null && !resourceId.isBlank()) {
      where.append(" and resource_id = ?");
      args.add(resourceId);
    }
    if (action != null && !action.isBlank()) {
      where.append(" and action = ?");
      args.add(action);
    }
    if (from != null) {
      where.append(" and at >= ?");
      args.add(Timestamp.from(from));
    }
    if (to != null) {
      where.append(" and at <= ?");
      args.add(Timestamp.from(to));
    }
    Long total = jdbc.queryForObject("select count(*) from audit_log" + where, Long.class, args.toArray());
    args.add(sz);
    args.add((long) Math.max(0, page) * sz);
    List<Entry> rows =
        jdbc.query(
            "select * from audit_log" + where + " order by at desc, received_at desc limit ? offset ?",
            (rs, i) ->
                new Entry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("actor_user_id", UUID.class),
                    rs.getString("actor_roles"),
                    rs.getString("action"),
                    rs.getString("resource_type"),
                    rs.getString("resource_id"),
                    json(rs.getString("before_json")),
                    json(rs.getString("after_json")),
                    rs.getString("ip"),
                    rs.getTimestamp("at").toInstant()),
            args.toArray());
    return Paged.of(rows, Math.max(0, page), sz, total == null ? 0 : total);
  }

  private Object json(String s) {
    try {
      return s == null ? null : mapper.readValue(s, Object.class);
    } catch (Exception e) {
      return null;
    }
  }

  private static String text(JsonNode n, String f) {
    return n.hasNonNull(f) ? n.get(f).asText() : null;
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  private static UUID uuid(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String roles(JsonNode n) {
    if (n == null || n.isNull()) {
      return null;
    }
    if (n.isArray()) {
      List<String> r = new ArrayList<>();
      n.forEach(x -> r.add(x.asText()));
      return cut(String.join(",", r), 200);
    }
    return cut(n.asText(), 200);
  }

  private static Instant parseAt(JsonNode n) {
    try {
      if (n == null || n.isNull()) {
        return Instant.now();
      }
      if (n.isNumber()) {
        double v = n.asDouble();
        return v > 1e11 ? Instant.ofEpochMilli((long) v) : Instant.ofEpochMilli((long) (v * 1000));
      }
      return Instant.parse(n.asText());
    } catch (Exception e) {
      return Instant.now();
    }
  }
}
