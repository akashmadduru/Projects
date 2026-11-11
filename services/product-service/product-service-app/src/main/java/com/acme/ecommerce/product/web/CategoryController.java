package com.acme.ecommerce.product.web;

import com.acme.ecommerce.product.api.dto.CategoryResponse;
import com.acme.ecommerce.product.service.CategoryService;
import com.acme.ecommerce.product.web.dto.CreateCategoryRequest;
import com.acme.ecommerce.product.web.dto.UpdateCategoryRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP edge for category management.
 *
 * <p>Thin: bind, delegate, return. {@code @PreAuthorize} on writes only (CATALOG_MANAGER/ADMIN).
 * Reads are public.
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

  private static final int DEFAULT_PAGE_SIZE = 50;

  private final CategoryService categories;

  /**
   * Paged list of all categories.
   *
   * @param pageable page number/size/sort
   * @return paged result
   */
  @GetMapping
  public PagedModel<CategoryResponse> list(
      @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "name", direction = Sort.Direction.ASC)
          Pageable pageable) {
    return new PagedModel<>(categories.list(pageable));
  }

  /**
   * One category by id.
   *
   * @param id the category identifier
   * @return the category
   */
  @GetMapping("/{id}")
  public CategoryResponse findById(@PathVariable UUID id) {
    return categories.findById(id);
  }

  /**
   * Creates a new category.
   *
   * @param request the category details
   * @return the created category
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyAuthority('CATALOG_MANAGER', 'ADMIN')")
  public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest request) {
    return categories.create(request);
  }

  /**
   * Updates a category.
   *
   * @param id the category identifier
   * @param request the new values and version
   * @return the updated category
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAnyAuthority('CATALOG_MANAGER', 'ADMIN')")
  public CategoryResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
    return categories.update(id, request);
  }

  /**
   * Deletes a category.
   *
   * @param id the category identifier
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyAuthority('CATALOG_MANAGER', 'ADMIN')")
  public void delete(@PathVariable UUID id) {
    categories.delete(id);
  }
}
