/**
 * Platform-integration deployment boundary.
 *
 * <p>The package owns public compatibility forwarding and the token-protected bridge consumed
 * by content-automation. It does not expose Core credentials or accept end-user identity in a
 * thread-local; internal requests carry an explicit owner id and use separate service tokens.</p>
 */
package com.xander.lab.integration.host;
