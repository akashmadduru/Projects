/**
 * Wire-level DTOs for product-service.
 *
 * <p>Records, not classes: these are immutable value carriers, and the compiler-generated {@code
 * equals}/{@code hashCode}/{@code toString} are exactly what a DTO wants. None of them carry
 * behaviour, and none of them are ever persisted - the entity is a separate type inside the
 * implementation module, so a column rename is not automatically a breaking API change.
 */
package com.acme.ecommerce.product.api.dto;
