package com.acme.ecommerce.product.web;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.service.SellerCatalogService;
import com.acme.ecommerce.product.service.SellerCatalogService.BulkResult;
import com.acme.ecommerce.product.service.SellerCatalogService.OfferRequest;
import com.acme.ecommerce.product.service.SellerCatalogService.OfferView;
import com.acme.ecommerce.product.service.SellerCatalogService.SellerCtx;
import com.acme.ecommerce.product.service.SellerCatalogService.SellerProductRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Seller portal catalog APIs (ROLE_SELLER; ADMIN acts as the platform seller). */
@RestController
@PreAuthorize("hasAnyRole('SELLER','ADMIN')")
public class SellerController {

  private static final long MAX_BYTES = 2L * 1024 * 1024;

  private final SellerCatalogService service;

  public SellerController(SellerCatalogService service) {
    this.service = service;
  }

  private SellerCtx ctx(Authentication auth) {
    boolean admin =
        auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    return service.resolve(((Jwt) auth.getPrincipal()).getSubject(), admin);
  }

  @GetMapping("/api/seller/offers")
  public List<OfferView> offers(Authentication auth) {
    return service.myOffers(ctx(auth));
  }

  @PostMapping("/api/seller/offers")
  @ResponseStatus(HttpStatus.CREATED)
  public OfferView createOffer(Authentication auth, @RequestBody OfferRequest request) {
    return service.createOffer(ctx(auth), request);
  }

  @PutMapping("/api/seller/offers/{id}")
  public OfferView updateOffer(
      Authentication auth, @PathVariable UUID id, @RequestBody OfferRequest request) {
    return service.updateOffer(ctx(auth), id, request);
  }

  @PostMapping("/api/seller/products")
  @ResponseStatus(HttpStatus.CREATED)
  public ProductResponse createProduct(Authentication auth, @RequestBody SellerProductRequest r) {
    return service.createProduct(ctx(auth), r);
  }

  @PostMapping(value = "/api/seller/products/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public BulkResult bulk(Authentication auth, @RequestParam("file") MultipartFile file)
      throws IOException {
    if (file.isEmpty()) {
      throw ValidationException.forField("file", "is required");
    }
    if (file.getSize() > MAX_BYTES) {
      throw ValidationException.forField("file", "must be at most 2 MB");
    }
    return service.bulk(ctx(auth), file.getBytes());
  }

  @GetMapping("/api/seller/products/bulk/template.csv")
  public ResponseEntity<byte[]> template() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"products-template.csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(service.template().getBytes(StandardCharsets.UTF_8));
  }
}
