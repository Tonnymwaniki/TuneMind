/**
 * Executes an async operation with exponential backoff and full jitter.
 * Retries on HTTP 429 (rate limit), 5xx transient server errors, and network/timeout errors.
 */
export interface RetryOptions {
  maxAttempts?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
  shouldRetry?: (err: any) => boolean;
}

function defaultShouldRetry(err: any): boolean {
  const status = Number(err?.status ?? err?.statusCode ?? 0);
  if (status === 429 || (status >= 500 && status <= 599)) return true;
  if (status >= 400 && status < 500) return false;

  const msg = String(err?.message || err || '').toLowerCase();
  if (
    msg.includes('authentication_error') ||
    msg.includes('invalid x-api-key') ||
    msg.includes('credit balance is too low')
  ) {
    return false;
  }
  return (
    msg.includes('429') ||
    msg.includes('rate_limit') ||
    msg.includes('overloaded') ||
    msg.includes('timeout') ||
    msg.includes('econnreset') ||
    msg.includes('etimedout') ||
    msg.includes('fetch failed') ||
    status === 0
  );
}

export async function retryWithBackoff<T>(
  fn: () => Promise<T>,
  options: RetryOptions = {}
): Promise<T> {
  const maxAttempts = Math.max(1, options.maxAttempts ?? 3);
  const baseDelayMs = Math.max(50, options.baseDelayMs ?? 250);
  const maxDelayMs = Math.max(baseDelayMs, options.maxDelayMs ?? 5_000);
  const shouldRetry = options.shouldRetry ?? defaultShouldRetry;

  let lastError: any;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      return await fn();
    } catch (err: any) {
      lastError = err;
      if (attempt >= maxAttempts || !shouldRetry(err)) {
        throw err;
      }
      const expCap = Math.min(maxDelayMs, baseDelayMs * Math.pow(2, attempt - 1));
      const jitterDelayMs = Math.floor(Math.random() * expCap) + Math.floor(baseDelayMs * 0.25);
      await new Promise((resolve) => setTimeout(resolve, jitterDelayMs));
    }
  }
  throw lastError;
}
