/**
 * Identity 域的 Servlet 侧安全配置：BCrypt 密码编码器。
 *
 * <p>{@code UserContext} / {@code JwtUtil} 已归 {@code xander-auth-core}，
 * {@code AuthInterceptor} 已归 {@code xander-webmvc-support}（两者都要求与 Servlet 或 MVC 解耦）。</p>
 */
package com.xander.lab.identity.security;
