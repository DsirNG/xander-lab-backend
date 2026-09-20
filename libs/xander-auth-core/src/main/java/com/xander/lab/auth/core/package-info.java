/**
 * Shared auth core: the auth surface every service, including the WebFlux Gateway, may depend on.
 *
 * <p>{@link com.xander.lab.auth.core.UserContext} is the trusted identity model: the
 * request-scoped holder for the authenticated user id and the client IP.
 * {@link com.xander.lab.auth.core.JwtUtil} is the access-token verifier and issuer, covering
 * the access, refresh and WeChat-bind token types.</p>
 *
 * <p><b>Boundary.</b> Allowed here: JWT claim model, access token verifier, auth policy model,
 * trusted identity model. Forbidden here: {@code HandlerInterceptor},
 * {@code WebMvcConfigurer}, Gateway filter, Redis health monitoring. This module must stay
 * Servlet / Reactive neutral, so it depends only on {@code spring-context},
 * {@code jakarta.annotation-api} and jjwt.</p>
 *
 * <p><b>⚠️ Threading caveat.</b> {@code UserContext} is backed by {@code ThreadLocal}. It has
 * no framework dependency, but a {@code ThreadLocal} only carries request identity on a
 * thread-per-request (Servlet) stack. Under WebFlux the request can move between threads, so a
 * reactive consumer must not rely on it and needs the identity threaded through the Reactor
 * context instead.</p>
 */
package com.xander.lab.auth.core;
