/**
 * Orchestration agent conversations and the tool loop that drives them.
 *
 * <p>The module owns conversation state, messages, events, and the agent
 * loop runtime plus the registered tools (e.g. blog generation). It must not
 * own publishing, schedules, or platform credentials.</p>
 */
package com.xander.lab.agent;