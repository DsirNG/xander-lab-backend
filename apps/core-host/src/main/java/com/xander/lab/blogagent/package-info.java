/**
 * Blog content generation domain.
 *
 * <p>The module owns generation tasks, conversations, evidence, versions and
 * generation events. Publishing remains in the blog domain; scheduling will
 * live in a separate automation domain and invoke this module through its
 * application service.</p>
 */
package com.xander.lab.blogagent;
