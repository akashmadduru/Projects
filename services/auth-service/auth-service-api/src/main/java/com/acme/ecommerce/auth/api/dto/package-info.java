/**
 * Request and response payloads exchanged with auth-service over HTTP.
 *
 * <p>All types here are immutable records. Jackson binds records natively, so no
 * {@code @JsonCreator}/{@code @JsonProperty} plumbing is needed and the wire names are exactly the
 * component names.
 */
package com.acme.ecommerce.auth.api.dto;
