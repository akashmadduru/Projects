package com.acme.ecommerce.product.imagestore;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.product.api.dto.ProductImageResponse;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.domain.ProductImage;
import com.acme.ecommerce.product.repository.ProductImageRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD for product images held on the static image server.
 *
 * <p>The file is written to the image server first; the database row (whose {@code url} is the
 * browser-facing address) is written second, so a failed upload never leaves a row pointing at
 * nothing. If the row write then fails, the new file is removed again. Files for rows that go away
 * (explicit delete, product delete, product update dropping an image) are removed best-effort.
 */
@Service
@RequiredArgsConstructor
public class ProductImageService {

  private static final Map<String, String> EXTENSIONS =
      Map.of(
          "image/jpeg", "jpg",
          "image/png", "png",
          "image/webp", "webp",
          "image/gif", "gif",
          "image/avif", "avif");

  private final ProductImageRepository images;
  private final ProductRepository products;
  private final ImageStoreClient store;

  /** Uploads {@code bytes} as a new image of the product, after any existing ones. */
  @Transactional
  public ProductImageResponse create(UUID productId, byte[] bytes, String contentType) {
    Product product = requireProduct(productId);
    String ext = extensionOf(contentType, bytes);
    String key = productId + "/" + UUID.randomUUID() + "." + ext;
    store.put(key, bytes, contentType);
    try {
      int order =
          images.findByProductIdOrderByDisplayOrderAsc(productId).stream()
                  .mapToInt(ProductImage::getDisplayOrder)
                  .max()
                  .orElse(-1)
              + 1;
      ProductImage saved =
          images.save(
              ProductImage.builder()
                  .productId(productId)
                  .url(store.publicUrlFor(key))
                  .displayOrder(order)
                  .createdAt(Instant.now())
                  .build());
      syncThumbnail(product);
      return toResponse(saved);
    } catch (RuntimeException e) {
      store.deleteQuietly(key);
      throw e;
    }
  }

  /** Replaces the bytes of an existing image; its id and (same-type) URL stay the same. */
  @Transactional
  public ProductImageResponse replace(
      UUID productId, UUID imageId, byte[] bytes, String contentType) {
    Product product = requireProduct(productId);
    ProductImage image = requireImage(productId, imageId);
    String ext = extensionOf(contentType, bytes);
    String oldKey = store.keyFor(image.getUrl()).orElse(null);
    String newKey =
        oldKey != null && oldKey.endsWith("." + ext)
            ? oldKey
            : productId + "/" + UUID.randomUUID() + "." + ext;
    store.put(newKey, bytes, contentType);
    if (!newKey.equals(oldKey)) {
      image.setUrl(store.publicUrlFor(newKey));
      if (oldKey != null) {
        store.deleteQuietly(oldKey);
      }
    }
    syncThumbnail(product);
    return toResponse(image);
  }

  @Transactional
  public void delete(UUID productId, UUID imageId) {
    Product product = requireProduct(productId);
    ProductImage image = requireImage(productId, imageId);
    String url = image.getUrl();
    images.delete(image);
    images.flush();
    syncThumbnail(product);
    store.keyFor(url).ifPresent(store::deleteQuietly);
  }

  /** Removes the files behind these image URLs (ours only; external URLs are ignored). */
  public void deleteFiles(List<String> urls) {
    if (!store.isEnabled()) {
      return;
    }
    urls.stream().flatMap(u -> store.keyFor(u).stream()).forEach(store::deleteQuietly);
  }

  /** Keeps {@code products.thumbnail_url} equal to the first image, as list cards render it. */
  private void syncThumbnail(Product product) {
    images.flush();
    String first =
        images.findByProductIdOrderByDisplayOrderAsc(product.getId()).stream()
            .min(Comparator.comparingInt(ProductImage::getDisplayOrder))
            .map(ProductImage::getUrl)
            .orElse(null);
    product.setThumbnailUrl(first);
  }

  private Product requireProduct(UUID id) {
    return products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", id));
  }

  private ProductImage requireImage(UUID productId, UUID imageId) {
    return images
        .findById(imageId)
        .filter(i -> i.getProductId().equals(productId))
        .orElseThrow(() -> new ResourceNotFoundException("Product image", imageId));
  }

  /** Extension for an allowed image type, verified against the file's magic bytes. */
  private String extensionOf(String contentType, byte[] bytes) {
    if (!store.isEnabled()) {
      throw new ValidationException("Image storage is not configured");
    }
    String ext = contentType == null ? null : EXTENSIONS.get(contentType.toLowerCase());
    if (ext == null) {
      throw ValidationException.forField(
          "file", "Unsupported image type; use JPEG, PNG, WebP, GIF or AVIF");
    }
    if (bytes == null || bytes.length == 0) {
      throw ValidationException.forField("file", "Image is empty");
    }
    if (!matchesMagic(ext, bytes)) {
      throw ValidationException.forField("file", "File content does not match its image type");
    }
    return ext;
  }

  private static boolean matchesMagic(String ext, byte[] b) {
    return switch (ext) {
      case "jpg" -> b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
      case "png" -> b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N';
      case "gif" -> b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
      case "webp" -> b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[8] == 'W' && b[9] == 'E';
      case "avif" -> b.length > 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
      default -> false;
    };
  }

  private static ProductImageResponse toResponse(ProductImage image) {
    return new ProductImageResponse(image.getId(), image.getUrl(), image.getDisplayOrder());
  }
}
