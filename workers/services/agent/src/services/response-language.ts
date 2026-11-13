export interface AgentLanguagePreferences {
  locale?: "zh" | "en";
  replyLanguage?: "auto" | "zh" | "en";
  /** Per-request browser time zone; deliberately not saved to conversation history. */
  timeZone?: string;
}

/** Selects response language without mistaking code, quotations, or names for a user's request. */
export function responseLanguageInstruction(preferences: AgentLanguagePreferences = {}): string {
  const fallback = preferences.locale === "en" ? "English" : "Simplified Chinese";
  const preference = preferences.replyLanguage === "en" ? "English"
    : preferences.replyLanguage === "zh" ? "Simplified Chinese" : null;
  return "\nResponse language: Honor the user's explicit language or translation request first, "
    + "but only when explicitly stated in the current user message. Historical language requests do not override the current settings. "
    + (preference
      ? `Otherwise use ${preference}, as selected in the user's reply-language settings. `
        + `This is a fixed setting: write every user-facing answer, explanation and tool summary in ${preference}, `
        + "even if the user asks in another language, the interface is in another language, or earlier replies and tool results use another language. "
        + "The language of the user's question alone is not a request to switch languages. "
      : "Otherwise match the language of the current user's own question. For mixed-language questions, "
        + "use the main language of their request. Do not infer language from code, URLs, proper names, "
        + "quoted text, uploaded documents, or tool output alone. For an ambiguous short follow-up, "
        + `continue the conversational language; if there is no clear context, use ${fallback}. `)
    + "Keep code, identifiers, quotations, and requested translation targets in their appropriate languages. "
    + "Apply this to your user-facing summaries after tools or delegated work as well.";
}
