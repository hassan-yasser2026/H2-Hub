export const DEFAULT_VISION_MODEL = "gemini-2.5-flash";
export const FALLBACK_VISION_MODEL = "gemini-3.8-flash";

export function getVisionModel(configuredModel: string | undefined): string {
  return configuredModel?.trim() || DEFAULT_VISION_MODEL;
}

export function shouldRetryUnavailableVisionModel(
  error: unknown,
  currentModel: string
): boolean {
  if (currentModel === FALLBACK_VISION_MODEL) return false;
  const message = error instanceof Error ? error.message : String(error);
  return /(?:\b404\b|NOT_FOUND|not found|no longer available|not available to new users)/iu.test(message);
}
