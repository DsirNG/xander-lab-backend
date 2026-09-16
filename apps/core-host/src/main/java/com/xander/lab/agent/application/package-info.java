/**
 * Application services for the agent conversation module.
 *
 * <p>Owns the agent loop (model decides, tools execute, observations feed
 * back), the tool registry, tool implementations, and the durable event
 * stream. Coordinates use-case transactions and invokes the blog generation
 * module through its application service only.</p>
 */
package com.xander.lab.agent.application;