/**
 * Integration domain's deployment-facing internal contracts.
 *
 * <p>This package owns token-protected service-to-service transport only. Vendor credentials,
 * publication attempts, and external HTTP calls remain in the integration application layer;
 * content-automation may call these endpoints through platform-integration, but must not import
 * Core persistence or concrete integration services.</p>
 */
package com.xander.lab.integration.internal;
