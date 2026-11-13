package com.acme.ecommerce.support.help;

import com.acme.ecommerce.support.help.ArticleService.Article;
import com.acme.ecommerce.support.help.ArticleService.ArticleRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Help centre: public reads + feedback, agent/admin CRUD. */
@RestController
public class ArticleController {

  public record FeedbackRequest(boolean helpful) {}

  private final ArticleService articles;

  public ArticleController(ArticleService articles) {
    this.articles = articles;
  }

  @GetMapping("/api/support/articles")
  public List<Article> list(
      @RequestParam(required = false) String category, @RequestParam(required = false) String q) {
    return articles.list(category, q, false);
  }

  @GetMapping("/api/support/articles/{slug}")
  public Article detail(@PathVariable String slug) {
    return articles.bySlug(slug);
  }

  @PostMapping("/api/support/articles/{slug}/feedback")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void feedback(@PathVariable String slug, @RequestBody FeedbackRequest request) {
    articles.feedback(slug, request.helpful());
  }

  @GetMapping("/api/admin/support/articles")
  @PreAuthorize("hasAnyRole('SUPPORT','ADMIN')")
  public List<Article> adminList(
      @RequestParam(required = false) String category, @RequestParam(required = false) String q) {
    return articles.list(category, q, true);
  }

  @PostMapping("/api/admin/support/articles")
  @PreAuthorize("hasAnyRole('SUPPORT','ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public Article create(@RequestBody ArticleRequest request) {
    return articles.create(request);
  }

  @PutMapping("/api/admin/support/articles/{id}")
  @PreAuthorize("hasAnyRole('SUPPORT','ADMIN')")
  public Article update(@PathVariable UUID id, @RequestBody ArticleRequest request) {
    return articles.update(id, request);
  }

  @DeleteMapping("/api/admin/support/articles/{id}")
  @PreAuthorize("hasAnyRole('SUPPORT','ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    articles.delete(id);
  }
}
