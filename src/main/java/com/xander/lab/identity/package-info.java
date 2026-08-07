/**
 * User identity, authentication and request authorization.
 *
 * <p>The module owns users, login sessions, JWT creation and authenticated
 * request context. Feature modules may read the current user or call the
 * authentication application service, but they must not parse tokens or query
 * the user table directly.</p>
 */
package com.xander.lab.identity;
