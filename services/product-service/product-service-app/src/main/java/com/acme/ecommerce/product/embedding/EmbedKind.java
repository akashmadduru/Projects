package com.acme.ecommerce.product.embedding;

/** What a text is being embedded FOR; some providers embed queries and documents differently. */
public enum EmbedKind {
  /** A short, user-typed search query. */
  QUERY,
  /** A catalog product's text. */
  DOCUMENT
}
