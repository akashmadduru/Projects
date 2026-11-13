package com.acme.ecommerce.support.help;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** Help-centre articles (JdbcTemplate; tiny table, cached read for the chatbot matcher). */
@Service
public class ArticleService {

  public static final Set<String> CATEGORIES =
      Set.of("ORDERS", "PAYMENTS", "RETURNS", "ACCOUNT", "SELLERS", "OTHER");

  public record Article(
      UUID id,
      String slug,
      String category,
      String title,
      String body,
      List<String> keywords,
      boolean published,
      int helpfulYes,
      int helpfulNo,
      Instant updatedAt) {}

  public record ArticleRequest(
      String slug, String category, String title, String body, List<String> keywords, Boolean published) {}

  private static final RowMapper<Article> MAPPER =
      (rs, n) ->
          new Article(
              rs.getObject("id", UUID.class),
              rs.getString("slug"),
              rs.getString("category"),
              rs.getString("title"),
              rs.getString("body"),
              keywords(rs),
              rs.getBoolean("published"),
              rs.getInt("helpful_yes"),
              rs.getInt("helpful_no"),
              rs.getTimestamp("updated_at").toInstant());

  private static List<String> keywords(ResultSet rs) throws SQLException {
    Array a = rs.getArray("keywords");
    return a == null ? List.of() : List.of((String[]) a.getArray());
  }

  private final JdbcTemplate jdbc;
  private volatile List<Article> cache = List.of();
  private volatile long cachedAt;

  public ArticleService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Article> list(String category, String q, boolean includeUnpublished) {
    StringBuilder sql = new StringBuilder("select * from faq_articles where 1=1");
    List<Object> args = new ArrayList<>();
    if (!includeUnpublished) {
      sql.append(" and published");
    }
    if (category != null && !category.isBlank()) {
      sql.append(" and category = ?");
      args.add(category.toUpperCase(Locale.ROOT));
    }
    if (q != null && !q.isBlank()) {
      String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
      sql.append(
          " and (lower(title) like ? or lower(body) like ? or lower(array_to_string(keywords, ' ')) like ?)");
      args.add(like);
      args.add(like);
      args.add(like);
    }
    sql.append(" order by helpful_yes - helpful_no desc, title limit 100");
    return jdbc.query(sql.toString(), MAPPER, args.toArray());
  }

  public Article bySlug(String slug) {
    return jdbc.query("select * from faq_articles where slug = ? and published", MAPPER, slug).stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Article", slug));
  }

  public void feedback(String slug, boolean helpful) {
    String col = helpful ? "helpful_yes" : "helpful_no";
    int n =
        jdbc.update(
            "update faq_articles set " + col + " = " + col + " + 1 where slug = ? and published", slug);
    if (n == 0) {
      throw new ResourceNotFoundException("Article", slug);
    }
  }

  /** Published articles, cached for 60 s (chatbot matcher). */
  public List<Article> published() {
    long now = System.currentTimeMillis();
    if (now - cachedAt > 60_000 || cache.isEmpty()) {
      cache = list(null, null, false);
      cachedAt = now;
    }
    return cache;
  }

  public Article create(ArticleRequest r) {
    validate(r, true);
    String slug = r.slug() == null || r.slug().isBlank() ? slugify(r.title()) : slugify(r.slug());
    try {
      UUID id =
          jdbc.queryForObject(
              "insert into faq_articles (slug, category, title, body, keywords, published)"
                  + " values (?, ?, ?, ?, ?, ?) returning id",
              UUID.class,
              slug,
              r.category().toUpperCase(Locale.ROOT),
              r.title().trim(),
              r.body(),
              keywordArray(r.keywords()),
              r.published() == null || r.published());
      cachedAt = 0;
      return get(id);
    } catch (DuplicateKeyException e) {
      throw new ResourceConflictException("An article with slug '" + slug + "' already exists");
    }
  }

  public Article update(UUID id, ArticleRequest r) {
    validate(r, false);
    Article cur = get(id);
    try {
      jdbc.update(
          "update faq_articles set slug = ?, category = ?, title = ?, body = ?, keywords = ?,"
              + " published = ?, updated_at = now() where id = ?",
          r.slug() == null || r.slug().isBlank() ? cur.slug() : slugify(r.slug()),
          r.category() == null ? cur.category() : r.category().toUpperCase(Locale.ROOT),
          r.title() == null ? cur.title() : r.title().trim(),
          r.body() == null ? cur.body() : r.body(),
          r.keywords() == null ? keywordArray(cur.keywords()) : keywordArray(r.keywords()),
          r.published() == null ? cur.published() : r.published(),
          id);
    } catch (DuplicateKeyException e) {
      throw new ResourceConflictException("An article with that slug already exists");
    }
    cachedAt = 0;
    return get(id);
  }

  public void delete(UUID id) {
    if (jdbc.update("delete from faq_articles where id = ?", id) == 0) {
      throw new ResourceNotFoundException("Article", id);
    }
    cachedAt = 0;
  }

  public Article get(UUID id) {
    return jdbc.query("select * from faq_articles where id = ?", MAPPER, id).stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("Article", id));
  }

  private static void validate(ArticleRequest r, boolean create) {
    if (create && (r.title() == null || r.title().isBlank())) {
      throw ValidationException.forField("title", "Required");
    }
    if (create && (r.body() == null || r.body().isBlank())) {
      throw ValidationException.forField("body", "Required");
    }
    if (r.title() != null && r.title().length() > 200) {
      throw ValidationException.forField("title", "At most 200 characters");
    }
    if (create && r.category() == null) {
      throw ValidationException.forField("category", "Required");
    }
    if (r.category() != null && !CATEGORIES.contains(r.category().toUpperCase(Locale.ROOT))) {
      throw ValidationException.forField("category", "Must be one of " + CATEGORIES);
    }
  }

  private static String[] keywordArray(List<String> kw) {
    return kw == null
        ? new String[0]
        : kw.stream().map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
  }

  static String slugify(String s) {
    String slug =
        s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    return slug.isEmpty() ? "article-" + UUID.randomUUID().toString().substring(0, 8) : slug;
  }
}
