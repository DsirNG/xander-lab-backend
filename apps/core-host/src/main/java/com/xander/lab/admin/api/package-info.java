/**
 * HTTP transport and validation for admin capabilities.
 *
 * <p>Controllers only map requests, delegate to application services, and
 * build Result payloads. Authorization for the whole /api/admin prefix is
 * enforced once in the identity AuthInterceptor; the handlers never parse
 * JWT claims themselves.</p>
 */
package com.xander.lab.admin.api;