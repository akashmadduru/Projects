package com.acme.ecommerce.notification.push;

import com.acme.ecommerce.notification.push.WebPushCrypto.KeyMaterial;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Web Push subscriptions + delivery. VAPID keys: env, else notification.settings, else generated. */
@Service
public class PushService {

  private static final Logger log = LoggerFactory.getLogger(PushService.class);

  private record Sub(UUID id, String endpoint, String p256dh, String auth) {}

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final String configuredPublic;
  private final String configuredPrivate;
  private final String subject;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private volatile KeyMaterial keys;

  public PushService(
      JdbcClient jdbc,
      ObjectMapper mapper,
      @Value("${notification.vapid.public-key:}") String configuredPublic,
      @Value("${notification.vapid.private-key:}") String configuredPrivate,
      @Value("${notification.vapid.subject:mailto:admin@acme.local}") String subject) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.configuredPublic = configuredPublic;
    this.configuredPrivate = configuredPrivate;
    this.subject = subject;
  }

  @PostConstruct
  void initKeys() {
    try {
      if (!configuredPublic.isBlank() && !configuredPrivate.isBlank()) {
        keys = new KeyMaterial(configuredPublic, configuredPrivate);
        return;
      }
      String pub = setting("vapid.public");
      String priv = setting("vapid.private");
      if (pub == null || priv == null) {
        KeyMaterial gen = WebPushCrypto.generateVapidKeys();
        // ON CONFLICT DO NOTHING: another replica may have generated first; re-read afterwards.
        saveSetting("vapid.public", gen.publicKey());
        saveSetting("vapid.private", gen.privateKey());
        pub = setting("vapid.public");
        priv = setting("vapid.private");
        log.info("Generated VAPID keys (stored in notification.settings)");
      }
      keys = new KeyMaterial(pub, priv);
    } catch (Exception e) {
      log.error("VAPID key setup failed; web push disabled: {}", e.toString());
    }
  }

  public String publicKey() {
    return keys == null ? null : keys.publicKey();
  }

  public void subscribe(UUID userId, String endpoint, String p256dh, String auth) {
    jdbc.sql(
            "INSERT INTO notification.push_subscriptions (user_id, endpoint, p256dh, auth) VALUES"
                + " (:u, :e, :k, :a) ON CONFLICT ((md5(endpoint))) DO UPDATE SET user_id ="
                + " EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth")
        .param("u", userId)
        .param("e", endpoint)
        .param("k", p256dh)
        .param("a", auth)
        .update();
  }

  public void unsubscribe(UUID userId, String endpoint) {
    jdbc.sql(
            "DELETE FROM notification.push_subscriptions WHERE user_id = :u AND md5(endpoint) ="
                + " md5(:e)")
        .param("u", userId)
        .param("e", endpoint)
        .update();
  }

  /** Best-effort push to every device of the user; dead endpoints (404/410) are pruned. */
  public int sendToUser(UUID userId, String title, String body, String link) {
    if (keys == null) {
      return 0;
    }
    List<Sub> subs =
        jdbc.sql(
                "SELECT id, endpoint, p256dh, auth FROM notification.push_subscriptions WHERE"
                    + " user_id = :u")
            .param("u", userId)
            .query(
                (rs, n) ->
                    new Sub(
                        rs.getObject("id", UUID.class),
                        rs.getString("endpoint"),
                        rs.getString("p256dh"),
                        rs.getString("auth")))
            .list();
    if (subs.isEmpty()) {
      return 0;
    }
    String json;
    try {
      json =
          mapper.writeValueAsString(
              Map.of(
                  "title", title == null ? "" : title,
                  "body", body == null ? "" : body,
                  "link", link == null ? "/" : link));
    } catch (Exception e) {
      return 0;
    }
    int ok = 0;
    for (Sub s : subs) {
      try {
        URI uri = URI.create(s.endpoint());
        String aud = uri.getScheme() + "://" + uri.getAuthority();
        byte[] bodyBytes =
            WebPushCrypto.encrypt(
                json.getBytes(StandardCharsets.UTF_8),
                WebPushCrypto.unb64(s.p256dh()),
                WebPushCrypto.unb64(s.auth()));
        String jwt = WebPushCrypto.vapidJwt(aud, subject, keys.privateKey());
        HttpRequest req =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", "86400")
                .header("Authorization", "vapid t=" + jwt + ", k=" + keys.publicKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();
        HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
        int code = res.statusCode();
        if (code >= 200 && code < 300) {
          ok++;
          jdbc.sql(
                  "UPDATE notification.push_subscriptions SET last_success_at = now() WHERE id ="
                      + " :id")
              .param("id", s.id())
              .update();
        } else if (code == 404 || code == 410) {
          jdbc.sql("DELETE FROM notification.push_subscriptions WHERE id = :id")
              .param("id", s.id())
              .update();
        } else {
          log.warn("Push to subscription {} returned HTTP {}", s.id(), code);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return ok;
      } catch (Exception e) {
        log.warn("Push to subscription {} failed: {}", s.id(), e.toString());
      }
    }
    return ok;
  }

  private String setting(String key) {
    return jdbc.sql("SELECT value FROM notification.settings WHERE key = :k")
        .param("k", key)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private void saveSetting(String key, String value) {
    jdbc.sql(
            "INSERT INTO notification.settings (key, value) VALUES (:k, :v) ON CONFLICT (key) DO"
                + " NOTHING")
        .param("k", key)
        .param("v", value)
        .update();
  }
}
