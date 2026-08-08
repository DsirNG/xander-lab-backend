/**
 * Automation domain: recurring schedules and durable runs.
 *
 * <p>The module owns scheduled email reminders: plans (frequency, timezone,
 * recurrence), persistent task state, and the poller that claims due work
 * atomically before performing SMTP I/O. It does not own mail delivery,
 * user identity, or article publication.</p>
 */
package com.xander.lab.automation;