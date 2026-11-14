export function normalizePresentationId(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim().toLowerCase().replaceAll("-", "");
  return /^[a-f0-9]{32}$/.test(normalized) ? normalized : null;
}
