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
 *
 * <p>When another domain genuinely needs a user attribute, add a method to a
 * contract port rather than handing out {@code UserMapper} or {@code User}.
 * {@code com.xander.lab.core.api.UserLookupPort} is the worked example: it
 * replaced two direct mapper injections with a single method that answers a
 * display name, and it returns {@code null} instead of a fallback string
 * because the fallback is the caller's display policy, not identity's data.
 * Do not widen that port — every method added is another way around the
 * boundary, and widening breaks nothing, so a test pins its width.</p>
 */
package com.xander.lab.identity;
