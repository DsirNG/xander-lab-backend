/**
 * User identity, authentication and request authorization.
 *
 * <p>The module owns users, login sessions, JWT creation and authenticated
 * request context. It also owns the WeChat Mini Program login channel
 * (jscode2session exchange and openid-to-account binding); the openid column
 * on {@code sys_user} is identity-owned and must not be written by other
 * modules. Feature modules may read the current user or call the
 * authentication application service, but they must not parse tokens or query
 * the user table directly.</p>
 */
package com.xander.lab.identity;
