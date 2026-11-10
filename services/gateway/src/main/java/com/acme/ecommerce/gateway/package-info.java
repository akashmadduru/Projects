/**
 * The platform's single public edge (Phase 1D).
 *
 * <p>Everything a browser or external client can reach comes through this module. Nothing else in
 * the platform is published; every other service listens only on the internal network. That makes
 * this the one place where three responsibilities have to be got exactly right:
 *
 * <ol>
 *   <li><b>Authentication offload.</b> Bearer tokens are validated here, locally, against a cached
 *       JWKS set - zero network hops per request in steady state. Downstream services still
 *       validate the token themselves (defence in depth); the edge is not a substitute for that, it
 *       is a filter that stops garbage before it costs anyone a thread.
 *   <li><b>Identity propagation.</b> Downstream services receive {@code X-User-*} headers derived
 *       <em>only</em> from validated token claims. The inverse rule matters just as much: any
 *       client-supplied header in that namespace is destroyed on the way in, before anything can
 *       read it. See {@link com.acme.ecommerce.gateway.filter}.
 *   <li><b>Blast-radius control.</b> A per-route circuit breaker turns "a downstream is sick" into
 *       a fast, clean 503 instead of a pile of gateway threads waiting on a socket.
 * </ol>
 *
 * <p>Deliberately NOT here: business logic, persistence, request-body inspection, response
 * rewriting. A gateway that understands payloads is a gateway that has to be redeployed every time
 * a downstream contract changes.
 */
package com.acme.ecommerce.gateway;
