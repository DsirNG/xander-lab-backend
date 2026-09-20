/**
 * BlogAgent read models hosted by content-automation during the Wave 2 extraction.
 *
 * <p>This package owns the task/session HTTP projection, JDBC reads, task creation, non-stream
 * execution, and local publication. Core-host remains only the stateless model-stage boundary;
 * revision and long-running stream execution remain behind compatibility until their workers and
 * long-connection drain are cut over.</p>
 */
package com.xander.lab.blogagent;
