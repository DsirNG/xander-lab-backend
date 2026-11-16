import type { PresentationDeck } from "../interfaces/agent-presentation.js";
import { parsePresentationDeck } from "./agent-presentation-validation.js";

/** Preserve the Agent's plan. Technical validation never rewrites content or composition. */
export function preparePresentationDeck(value: unknown): { deck: PresentationDeck; adjustedSlides: number } {
  return { deck: parsePresentationDeck(value), adjustedSlides: 0 };
}
