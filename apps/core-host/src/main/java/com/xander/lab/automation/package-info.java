/**
 * Automation domain: recurring schedules and durable runs.
 *
 * <p>The module owns scheduled email reminders (plans, recurrence, task state,
 * the poller that claims due work atomically before SMTP I/O) and the
 * daily content plans: {@code blog_content_plan}, durable {@code blog_plan_run}
 * rows, and the user-facing {@code blog_notification} list. It coordinates
 * headless agent generation and multi-platform publication through application
 * ports, but does not own mail delivery, user identity, generation tasks,
 * article contents, or platform credentials.</p>
 */
package com.xander.lab.automation;