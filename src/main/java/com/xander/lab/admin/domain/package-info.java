/**
 * Durable admin state: model providers and per-feature model configs.
 *
 * <p>Domain entities must not depend on Spring MVC, Redis, or HTTP clients;
 * API keys are stored encrypted by the infrastructure layer and never leave
 * the domain as plaintext.</p>
 */
package com.xander.lab.admin.domain;