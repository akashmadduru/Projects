package com.acme.ecommerce.rating.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.PurchaseStatusResponse;
import com.acme.ecommerce.rating.client.AuthAccountFeignClient;
import com.acme.ecommerce.rating.client.OrderPurchaseFeignClient;
import com.acme.ecommerce.rating.client.UserProfileFeignClient;
import com.acme.ecommerce.rating.repository.RatingRepository;
import com.acme.ecommerce.user.api.dto.UserProfileResponse;
import feign.FeignException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The remote half of review rules: "did this caller buy it" (order-service) and "what is their
 * display name" (user-service). Deliberately NOT transactional - it makes network calls, which must
 * never run while a database connection is held; {@link RatingService} owns the transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewEligibilityService {

  /** Shown when the author's profile has no usable display name or user-service is down. */
  static final String FALLBACK_DISPLAY_NAME = "Verified buyer";

  private final RatingRepository ratings;
  private final OrderPurchaseFeignClient orders;
  private final UserProfileFeignClient users;
  private final AuthAccountFeignClient accounts;

  /**
   * Gate for creating a review; returns the display name to store on it.
   *
   * @throws CodedException REVIEW_ALREADY_EXISTS (409) or REVIEW_NOT_ELIGIBLE (403)
   */
  public String requireEligibleAuthor(UUID productId, UUID userId) {
    if (ratings.existsByProductIdAndUserId(productId, userId)) {
      throw new CodedException(
          ErrorCode.REVIEW_ALREADY_EXISTS, "You have already reviewed this product");
    }
    if (!hasPurchased(productId)) {
      throw new CodedException(
          ErrorCode.REVIEW_NOT_ELIGIBLE, "Only customers who bought this product can review it");
    }
    return displayName();
  }

  /** Whether the caller (the relayed token's user) has a non-cancelled order with the product. */
  public boolean hasPurchased(UUID productId) {
    try {
      PurchaseStatusResponse status = orders.purchaseStatus(productId);
      return status != null && status.purchased();
    } catch (FeignException e) {
      log.warn("order-service purchase check failed productId={}: {}", productId, e.status());
      throw new CodedException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "Could not verify your purchase right now", e);
    }
  }

  /**
   * Display name for the caller: the user-service profile name if they set one, else the name they
   * registered with (auth-service), else a generic label. Never from the request, never fatal.
   */
  public String displayName() {
    String name = profileName();
    if (name == null) {
      name = accountName();
    }
    return name == null ? FALLBACK_DISPLAY_NAME : name;
  }

  private String profileName() {
    try {
      UserProfileResponse profile = users.me();
      return profile == null ? null : blankToNull(profile.displayName());
    } catch (FeignException e) {
      log.warn("user-service profile lookup failed: {}", e.status());
      return null;
    }
  }

  private String accountName() {
    try {
      AuthAccountFeignClient.Account account = accounts.me();
      return account == null ? null : blankToNull(account.displayName());
    } catch (FeignException e) {
      log.warn("auth-service account lookup failed: {}", e.status());
      return null;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
