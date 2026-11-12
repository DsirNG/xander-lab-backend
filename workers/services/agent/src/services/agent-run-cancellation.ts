/** Revalidate durable execution authority before each subsequent request. */
const checks = new WeakMap<AbortSignal, () => Promise<void>>();

export function registerRunCancellation(signal: AbortSignal, check: () => Promise<void>): void {
  checks.set(signal, check);
}

export async function checkRunCancellation(signal: AbortSignal): Promise<void> {
  signal.throwIfAborted();
  await checks.get(signal)?.();
  signal.throwIfAborted();
}
