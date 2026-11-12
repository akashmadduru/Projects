package com.acme.ecommerce.cart.service;

import com.acme.ecommerce.cart.api.dto.WishlistItemRequest;
import com.acme.ecommerce.cart.api.dto.WishlistItemResponse;
import com.acme.ecommerce.cart.api.dto.WishlistMergeItem;
import com.acme.ecommerce.cart.repository.WishlistRepository;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Wishlist use cases, always scoped to the JWT subject. */
@Service
public class WishlistService {

  static final int MAX_ITEMS = 500;

  private final WishlistRepository wishlist;

  public WishlistService(WishlistRepository wishlist) {
    this.wishlist = wishlist;
  }

  @Transactional(readOnly = true)
  public List<WishlistItemResponse> list(UUID userId) {
    return wishlist.findAll(userId);
  }

  @Transactional
  public WishlistItemResponse put(UUID userId, UUID productId, WishlistItemRequest r) {
    wishlist.lockUser(userId);
    ensureRoom(userId, productId);
    wishlist.upsert(userId, productId, r.name(), r.price(), r.currency(), r.imageUrl());
    return wishlist.findAll(userId).stream()
        .filter(i -> i.productId().equals(productId))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public void remove(UUID userId, UUID productId) {
    wishlist.delete(userId, productId);
  }

  @Transactional
  public List<WishlistItemResponse> merge(UUID userId, List<WishlistMergeItem> items) {
    wishlist.lockUser(userId);
    for (WishlistMergeItem i : items) {
      ensureRoom(userId, i.productId());
      wishlist.insertIfAbsent(
          userId, i.productId(), i.name(), i.price(), i.currency(), i.imageUrl());
    }
    return wishlist.findAll(userId);
  }

  private void ensureRoom(UUID userId, UUID productId) {
    if (!wishlist.exists(userId, productId) && wishlist.count(userId) >= MAX_ITEMS) {
      throw new CodedException(
          ErrorCode.WISHLIST_LIMIT, "Wishlist is limited to " + MAX_ITEMS + " items");
    }
  }
}
