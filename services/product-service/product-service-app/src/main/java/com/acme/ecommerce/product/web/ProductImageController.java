package com.acme.ecommerce.product.web;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.product.api.ProductClient;
import com.acme.ecommerce.product.api.dto.ProductImageResponse;
import com.acme.ecommerce.product.imagestore.ProductImageService;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload / replace / delete of a product's images on the static image server. Reading needs no
 * endpoint here: the image URLs on {@code ProductResponse} point straight at the image server.
 */
@RestController
@RequestMapping(ProductClient.BASE_PATH + "/{productId}/images")
@RequiredArgsConstructor
public class ProductImageController {

  private final ProductImageService service;

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public ProductImageResponse create(
      @PathVariable UUID productId, @RequestPart("file") MultipartFile file) {
    return service.create(productId, bytes(file), file.getContentType());
  }

  @PutMapping(value = "/{imageId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public ProductImageResponse replace(
      @PathVariable UUID productId,
      @PathVariable UUID imageId,
      @RequestPart("file") MultipartFile file) {
    return service.replace(productId, imageId, bytes(file), file.getContentType());
  }

  @DeleteMapping("/{imageId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public void delete(@PathVariable UUID productId, @PathVariable UUID imageId) {
    service.delete(productId, imageId);
  }

  private static byte[] bytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw ValidationException.forField("file", "Could not read the uploaded file");
    }
  }
}
