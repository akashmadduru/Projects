package com.acme.ecommerce.product.service;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.product.api.dto.CategoryResponse;
import com.acme.ecommerce.product.domain.Category;
import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.web.dto.CreateCategoryRequest;
import com.acme.ecommerce.product.web.dto.UpdateCategoryRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for product categories.
 *
 * <p>Enforces maximum 2-level nesting (root categories and their children only, no grandchildren).
 *
 * <p>{@code readOnly = true} AT THE CLASS LEVEL lets Hibernate skip dirty-checking on reads. Each
 * write method opens its own {@code @Transactional}, overriding this default.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CategoryService {

  private static final int MAX_DEPTH = 1;

  private final CategoryRepository categories;
  private final TimeProvider timeProvider;

  /**
   * Paged list of all categories.
   *
   * @param pageable page number/size/sort
   * @return paged result
   */
  public Page<CategoryResponse> list(Pageable pageable) {
    return categories.findAll(pageable).map(this::toResponse);
  }

  /**
   * Gets a single category by id.
   *
   * @param id the category identifier
   * @return the category
   * @throws ResourceNotFoundException when the category does not exist
   */
  public CategoryResponse findById(UUID id) {
    Category category =
        categories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", id));
    return toResponse(category);
  }

  /**
   * Creates a new category.
   *
   * @param request the category details
   * @return the created category
   * @throws ValidationException if the parent category is too deeply nested
   */
  @Transactional
  public CategoryResponse create(CreateCategoryRequest request) {
    Instant now = timeProvider.now();

    // Validate depth: if parentCategoryId is set, the parent must have depth 0
    int depthLevel = 0;
    if (request.getParentCategoryId() != null) {
      Category parent =
          categories
              .findById(request.getParentCategoryId())
              .orElseThrow(
                  () ->
                      ValidationException.forField(
                          "parentCategoryId", "parent category does not exist"));
      if (parent.getDepthLevel() >= MAX_DEPTH) {
        throw ValidationException.forField(
            "parentCategoryId",
            "cannot create nested subcategories beyond " + MAX_DEPTH + " levels");
      }
      depthLevel = parent.getDepthLevel() + 1;
    }

    Category category =
        Category.builder()
            .name(request.getName())
            .description(request.getDescription())
            .parentCategoryId(request.getParentCategoryId())
            .depthLevel(depthLevel)
            .isActive(request.getIsActive() != null ? request.getIsActive() : true)
            .createdAt(now)
            .updatedAt(now)
            .build();

    Category saved = categories.save(category);
    log.info("Created category categoryId={} name={}", saved.getId(), saved.getName());

    return toResponse(saved);
  }

  /**
   * Updates an existing category.
   *
   * @param id the category identifier
   * @param request the new field values and the version the caller last read
   * @return the updated category
   * @throws ResourceNotFoundException when no such category exists
   * @throws ResourceConflictException when {@code request.version()} no longer matches
   */
  @Transactional
  public CategoryResponse update(UUID id, UpdateCategoryRequest request) {

    Category category =
        categories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", id));

    if (!Objects.equals(category.getVersion(), request.getVersion())) {
      throw new ResourceConflictException(
          "Category "
              + id
              + " has version "
              + category.getVersion()
              + ", not "
              + request.getVersion());
    }

    Instant now = timeProvider.now();

    category.setName(request.getName());
    category.setDescription(request.getDescription());
    category.setIsActive(request.isActive());
    category.setUpdatedAt(now);

    Category saved = categories.saveAndFlush(category);
    log.info("Updated category categoryId={} name={}", id, saved.getName());

    return toResponse(saved);
  }

  /**
   * Deletes a category.
   *
   * @param id the category identifier
   * @throws ResourceNotFoundException when no such category exists
   */
  @Transactional
  public void delete(UUID id) {
    Category category =
        categories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", id));
    // Both are FKs; without these checks the delete fails at flush as a raw integrity error.
    if (categories.existsByParentCategoryId(id)) {
      throw new ResourceConflictException(
          "Category has subcategories; delete or move them first, or deactivate it instead.");
    }
    long products = categories.countProductsInCategory(id);
    if (products > 0) {
      throw new ResourceConflictException(
          "Category is used by " + products + " product(s); deactivate it instead.");
    }
    categories.delete(category);
    log.info("Deleted category categoryId={}", id);
  }

  private CategoryResponse toResponse(Category category) {
    return new CategoryResponse(
        category.getId(),
        category.getName(),
        category.getDescription(),
        category.getParentCategoryId(),
        category.getDepthLevel(),
        category.isActive(),
        category.getVersion());
  }
}
