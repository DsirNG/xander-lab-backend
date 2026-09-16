/**
 * 管理后台：用户封禁、模型供应商与功能模型配置。
 *
 * <p>Owns the model provider registry, per-feature model assignments with
 * fallback, and the admin HTTP surface. It must not own users or login
 * sessions (identity does); admin user operations call identity's
 * application services. Model clients in agent / blogagent / studio consume
 * the resolved endpoints only through the {@code core.api.ModelGateway}
 * contract (implemented here by {@code ModelGatewayService}) and never
 * through admin mappers.</p>
 */
package com.xander.lab.admin;