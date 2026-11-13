package com.acme.ecommerce.inventory.service;

import com.acme.ecommerce.common.core.error.ForbiddenException;
import com.acme.ecommerce.common.core.error.UpstreamUnavailableException;
import com.acme.ecommerce.inventory.config.InventoryProperties;
import com.acme.ecommerce.inventory.domain.Platform;
import java.util.UUID;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** Resolves the calling user's seller id (seller-service) and offer ownership (product-service). */
@Component
public class SellerDirectory {

  public record SellerRef(UUID sellerId, String status) {}

  public record OfferRef(UUID offerId, UUID productId, UUID variantId, UUID sellerId) {}

  private final RestTemplate http;
  private final InventoryProperties props;

  public SellerDirectory(RestTemplate jwksRestTemplate, InventoryProperties props) {
    this.http = jwksRestTemplate;
    this.props = props;
  }

  private HttpEntity<Void> auth() {
    HttpHeaders h = new HttpHeaders();
    h.set("X-Internal-Token", props.getInternal().getToken());
    return new HttpEntity<>(h);
  }

  /** The seller acting for this user; admins without a seller act as the platform. */
  public UUID resolveSeller(String userId, boolean admin) {
    SellerRef ref = null;
    try {
      ref =
          http.exchange(
                  "http://seller-service/internal/sellers/by-user/" + userId,
                  HttpMethod.GET,
                  auth(),
                  SellerRef.class)
              .getBody();
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() != 404) {
        throw new UpstreamUnavailableException("seller-service", e);
      }
    } catch (RuntimeException e) {
      if (!admin) {
        throw new UpstreamUnavailableException("seller-service", e);
      }
    }
    if (ref != null && ref.sellerId() != null) {
      if (!"APPROVED".equalsIgnoreCase(ref.status()) && !admin) {
        throw new ForbiddenException("Seller account is not approved");
      }
      return ref.sellerId();
    }
    if (admin) {
      return Platform.SELLER_ID;
    }
    throw new ForbiddenException("Not a seller");
  }

  /** Best-effort, async: product-service recomputes the buy box and the list-row price. */
  public void syncPricesAsync(UUID productId) {
    java.util.concurrent.CompletableFuture.runAsync(
        () -> {
          try {
            http.exchange(
                "http://product-service/internal/products/" + productId + "/sync-prices",
                HttpMethod.POST,
                auth(),
                Void.class);
          } catch (RuntimeException e) {
            // fail-open: the price is re-synced on the next offer change
          }
        });
  }

  public OfferRef offer(UUID offerId) {
    try {
      return http.exchange(
              "http://product-service/internal/offers/" + offerId,
              HttpMethod.GET,
              auth(),
              OfferRef.class)
          .getBody();
    } catch (HttpClientErrorException e) {
      HttpStatusCode code = e.getStatusCode();
      if (code.value() == 404) {
        return null;
      }
      throw new UpstreamUnavailableException("product-service", e);
    } catch (RuntimeException e) {
      throw new UpstreamUnavailableException("product-service", e);
    }
  }
}
