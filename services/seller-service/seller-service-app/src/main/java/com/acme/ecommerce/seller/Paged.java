package com.acme.ecommerce.seller;

import java.util.List;

/** PagedModel-shaped page: {content, page:{size, number, totalElements, totalPages}}. */
public record Paged<T>(List<T> content, PageInfo page) {

  public record PageInfo(int size, int number, long totalElements, int totalPages) {}

  public static <T> Paged<T> of(List<T> content, int page, int size, long total) {
    return new Paged<>(content, new PageInfo(size, page, total, (int) Math.ceil(total / (double) size)));
  }

  public static int clampSize(int size) {
    return Math.max(1, Math.min(size, 100));
  }
}
