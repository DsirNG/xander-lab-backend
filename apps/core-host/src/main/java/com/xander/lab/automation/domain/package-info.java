/**
 * Reminder task model and pure scheduling math.
 *
 * <p>Owns the durable plan/run state (an {@code EmailReminderTask}) and the
 * deterministic frequency and timezone calculations. Domain state must not
 * depend on Spring MVC, HTTP clients, or vendor SDKs.</p>
 */
package com.xander.lab.automation.domain;