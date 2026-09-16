/**
 * Servlet-side authentication wiring for the identity domain: the MVC interceptor that
 * enforces access-token validity on {@code /api/**}, and the BCrypt password encoder bean.
 *
 * <p>The reusable pieces moved out in Wave 0B: {@code UserContext} (trusted identity model)
 * and {@code JwtUtil} (token verifier) now live in {@code xander-auth-core}, because they are
 * shared with every other domain and, later, with the WebFlux Gateway. What is left here is
 * deliberately not extractable: {@link com.xander.lab.identity.security.AuthInterceptor} is a
 * {@code HandlerInterceptor} and reads the long-connection security plane, both of which V9
 * §20 forbids inside {@code xander-auth-core}.</p>
 */
package com.xander.lab.identity.security;
