/**
 * Blog HTML preview sandbox: short-lived preview sessions for untrusted HTML
 * served as standalone pages from an independent origin.
 *
 * <p>The module owns preview session lifecycle (creation, expiry, code storage)
 * and renders the sandbox page that the blog frontend embeds via
 * {@code <iframe src>}. Isolation comes from the page being served on its own
 * origin (a dedicated preview domain in production, a different port in local
 * development) with a restrictive CSP; the stored code is never mixed with
 * article content or user credentials. Identity limits abuse via IP-based rate
 * limiting; no user identity is required.</p>
 */
package com.xander.lab.bloghtml;