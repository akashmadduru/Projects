package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.api.dto.CouponRequest;
import com.acme.ecommerce.promotion.api.dto.CouponResponse;
import com.acme.ecommerce.promotion.service.CouponService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin coupon CRUD. */
@RestController
@RequestMapping("/api/admin/coupons")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
public class AdminCouponController {

  private final CouponService service;

  public AdminCouponController(CouponService service) {
    this.service = service;
  }

  @GetMapping
  public List<CouponResponse> list() {
    return service.list();
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public CouponResponse create(@Valid @RequestBody CouponRequest request) {
    return service.create(request);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public CouponResponse update(@PathVariable UUID id, @Valid @RequestBody CouponRequest request) {
    return service.update(id, request);
  }

  @PostMapping("/{id}/deactivate")
  @PreAuthorize("hasRole('ADMIN')")
  public CouponResponse deactivate(@PathVariable UUID id) {
    return service.deactivate(id);
  }
}
