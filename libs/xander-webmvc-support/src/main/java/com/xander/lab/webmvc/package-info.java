/**
 * Servlet MVC support (V9 §20): the request / response shaping every MVC service needs, and
 * nothing else.
 *
 * <p>Contents: {@link com.xander.lab.webmvc.GlobalExceptionHandler} (every exception to the
 * shared {@code Result} envelope), {@link com.xander.lab.webmvc.ResultStatusResponseBodyAdvice}
 * (HTTP status kept aligned with {@code Result.code}), {@link com.xander.lab.webmvc.AuthInterceptor}
 * (the MVC auth adapter) and {@link com.xander.lab.webmvc.WebConfig} (the
 * {@code WebMvcConfigurer}: interceptor + CORS registration).</p>
 *
 * <p><b>Boundary (V9 §20).</b> Audience is 「仅 MVC 服务使用」. The WebFlux Gateway must
 * <b>not</b> depend on this module: it carries {@code spring-webmvc} and {@code jakarta.servlet},
 * which is the same accident as putting {@code spring-boot-starter-web} on a WebFlux classpath.
 * Both runtime starters are declared {@code provided} so the compile surface stays honest and
 * the accident is not reachable transitively.</p>
 *
 * <p><b>Why the package is {@code webmvc} and not {@code identity.security}.</b>
 * {@code AuthInterceptor} used to live in {@code com.xander.lab.identity.security}. A library
 * must not ship a package inside a business domain's namespace: {@code identity} is a
 * {@code BUSINESS_DOMAINS} entry in the migration scanner, so the module would be classified as
 * part of that domain and could no longer be "support every MVC service uses". This is the same
 * call {@code xander-auth-core} made when {@code UserContext} / {@code JwtUtil} moved from
 * {@code identity.security} to {@code auth.core}. The move also removed the
 * {@code config -> identity} shared-kernel reverse dependency that {@code WebConfig}'s old
 * {@code import com.xander.lab.identity.security.AuthInterceptor} line was creating.</p>
 *
 * <p><b>⚠️ These four classes have zero Java consumers by construction</b> — Spring discovers
 * them (component scan, {@code @RestControllerAdvice}, {@code WebMvcConfigurer}). So a mistake
 * here does not fail to compile: it compiles, the tests stay green, and exception mapping or
 * interceptor registration silently stops happening at runtime. Any change to this package
 * needs a wiring test, not just a green build.</p>
 *
 * <p><b>⚠️ The library depends on one business contract, {@code core-api}, and that is
 * deliberate.</b> {@code GlobalExceptionHandler} maps
 * {@code core.api.FeatureModelNotConfiguredException} to 400, and
 * {@code AuthInterceptor} resolves identity through {@code core.api.AccessTokenLookupPort}
 * instead of injecting identity's {@code AuthService}. V9 §20 lists {@code GlobalExceptionHandler}
 * whole, so splitting it across two advices — which would put Spring's "most specific handler wins
 * across advices" ordering on the critical path of every error response — was rejected. No other
 * library carries a contract dependency; this one is recorded in its POM rather than left
 * implicit.</p>
 */
package com.xander.lab.webmvc;
