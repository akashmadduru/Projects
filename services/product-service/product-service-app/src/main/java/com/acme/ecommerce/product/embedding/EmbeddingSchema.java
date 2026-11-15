package com.acme.ecommerce.product.embedding;

/** Facts about the physical schema that Java code must agree with. */
public final class EmbeddingSchema {

  /**
   * The width of {@code product.product_embeddings.embedding} ({@code public.vector(1024)} in V10).
   * Fixed in the column type: changing it is a new table plus a cutover, never an ALTER.
   */
  public static final int DIMS = 1024;

  private EmbeddingSchema() {}
}
