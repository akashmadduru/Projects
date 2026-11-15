package com.acme.ecommerce.product.admin;

import com.acme.ecommerce.product.api.ProductClient;
import com.acme.ecommerce.product.embedding.EmbeddingAdminService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin control of the semantic-search index.
 *
 * <p>NOT public, unlike the catalog reads around it: {@code SecurityConfig} permits every {@code
 * GET /api/products/**}, so the {@code @PreAuthorize} on each method is the ONLY thing guarding
 * these. An anonymous caller is turned away with 401, a customer with 403.
 */
@RestController
@RequestMapping(ProductClient.BASE_PATH + "/admin/embeddings")
@RequiredArgsConstructor
public class EmbeddingAdminController {

  /** Reindex acknowledgement; the work happens on the reconciler's next run. */
  public record ReindexResponse(long queued) {}

  private final EmbeddingAdminService embeddings;

  /** Embedding coverage of the active catalog. */
  @GetMapping("/status")
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public EmbeddingAdminService.Status status() {
    return embeddings.status();
  }

  /**
   * Queues a re-embed of one product, or of the whole catalog when {@code productId} is absent.
   * Existing vectors stay searchable until they are replaced.
   */
  @PostMapping("/reindex")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public ReindexResponse reindex(
      @RequestParam(name = "productId", required = false) UUID productId) {
    return new ReindexResponse(embeddings.reindex(productId));
  }
}
