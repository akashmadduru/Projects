package com.acme.ecommerce.product.web;

import com.acme.ecommerce.product.imagestore.ImageUrlResolver;
import com.acme.ecommerce.product.imagestore.ImageUrlResolver.ImageSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The image {@code parentUrl} setting, same shape as {@link ThemeController}/{@link
 * FxRateController}: public {@code GET /api/products/image-settings}, admin {@code PUT|DELETE
 * /api/admin/image-settings}. DELETE returns to the startup default (the static-image-server
 * endpoint from {@code STATIC_IMAGE_SERVER_PUBLIC_URL}).
 */
@RestController
@RequiredArgsConstructor
public class ImageSettingsController {

  private final ImageUrlResolver resolver;

  public record ImageSettingsRequest(String parentUrl) {}

  @GetMapping("/api/products/image-settings")
  public ResponseEntity<ImageSettings> get() {
    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(resolver.settings());
  }

  @PutMapping("/api/admin/image-settings")
  @PreAuthorize("hasRole('ADMIN')")
  public ImageSettings put(@RequestBody ImageSettingsRequest body) {
    return resolver.update(body == null ? null : body.parentUrl());
  }

  @DeleteMapping("/api/admin/image-settings")
  @PreAuthorize("hasRole('ADMIN')")
  public ImageSettings reset() {
    return resolver.reset();
  }
}
