/** The server owns the clock; clients may supply only a validated time zone. */
export function agentTimeZone(value?: string): string {
  if (value) {
    try { return new Intl.DateTimeFormat("en", { timeZone: value }).resolvedOptions().timeZone; }
    catch { /* Older clients use the application's China-time default. */ }
  }
  return "Asia/Shanghai";
}

export function currentTimeInstruction(timeZone: string, now = new Date()): string {
  const localTime = new Intl.DateTimeFormat("en-CA", {
    timeZone, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
  }).format(now);
  return `\nCurrent server time: ${now.toISOString()}. User time zone: ${timeZone}. `
    + `Current local date and time: ${localTime}. `
    + "Resolve today, tomorrow, yesterday and relative schedules in this time zone unless the user specifies another. "
    + "Use this server clock rather than dates in conversation history or model training data.";
}
