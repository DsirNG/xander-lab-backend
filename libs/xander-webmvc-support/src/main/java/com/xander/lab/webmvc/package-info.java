/**
 * Servlet MVC 支撑：异常映射、HTTP 状态对齐、认证拦截与 CORS 注册。
 *
 * <p>只给 MVC 服务使用。WebFlux 服务（Gateway）不得依赖本模块 —— 它带 {@code spring-webmvc}
 * 与 {@code jakarta.servlet}，两个运行时 starter 因此声明为 {@code provided}，
 * 不会被传递依赖带过去。</p>
 */
package com.xander.lab.webmvc;
