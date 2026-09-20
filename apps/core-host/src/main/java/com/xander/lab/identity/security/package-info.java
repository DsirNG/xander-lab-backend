/**
 * What is left of the identity domain's Servlet-side security wiring: the BCrypt password
 * encoder bean.
 *
 * <p>The reusable pieces moved out in Wave 0B. {@code UserContext} (trusted identity model) and
 * {@code JwtUtil} (token verifier) went to {@code xander-auth-core} first, because they are shared
 * with every other domain and, later, with the WebFlux Gateway. {@code AuthInterceptor} went to
 * {@code xander-webmvc-support} afterwards, as V9 §20 lists it by name under "MVC Auth Adapter"
 * and it is a {@code HandlerInterceptor} — which V9 §20 forbids inside {@code xander-auth-core}.</p>
 *
 * <p><strong>Correction to what this file used to say.</strong> It claimed {@code AuthInterceptor}
 * was "deliberately not extractable" because it "reads the long-connection security plane". That
 * was measured and is false: {@code SecurityState*}, {@code redisTemplate} and {@code SecurityPlane}
 * have zero occurrences in the class. The real blocker was that it injected
 * {@code identity.application.AuthService}, a business-domain service — which is what the same
 * claim in {@code libs/xander-auth-core/pom.xml} was standing in for. It was resolved by narrowing
 * the interceptor to the {@code core.api.AccessTokenLookupPort} contract, the port built for the
 * WebSocket handshake path. Do not restate the "security plane" reason anywhere; it was never
 * true.</p>
 *
 * <p>{@code PasswordEncoderConfig} stays here on purpose: it wires
 * {@code spring-security-crypto}'s BCrypt bean, V9 §20 does not list it, and it is identity-domain
 * configuration rather than MVC request shaping.</p>
 */
package com.xander.lab.identity.security;
