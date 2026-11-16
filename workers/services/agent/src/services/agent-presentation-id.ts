export function normalizePresentationId(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const raw = value.trim().toLowerCase();
  const normalized = raw.replaceAll("-", "");
  if (/^[a-f0-9]{32}$/.test(normalized)) return normalized;
  const embedded = raw.match(/([a-f0-9]{32})/);
  return embedded?.[1] ?? null;
}
