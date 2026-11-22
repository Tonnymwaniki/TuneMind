/**
 * Short-TTL in-memory cache for non-sensitive user profile metadata.
 * Prevents read-heavy profile/tier lookups from hammering the primary auth DB
 * while keeping OAuth secrets strictly isolated in encrypted storage.
 */

export interface CachedAccountProfile {
  profile: Record<string, unknown> | null;
  expiresAtMs: number;
}

const DEFAULT_TTL_MS = 60_000;
const MAX_ENTRIES = 5_000;

export class AccountMetadataCache {
  private static readonly cache = new Map<string, CachedAccountProfile>();

  static get(userId: string): Record<string, unknown> | null | undefined {
    const entry = this.cache.get(userId);
    if (!entry) return undefined;
    if (entry.expiresAtMs <= Date.now()) {
      this.cache.delete(userId);
      return undefined;
    }
    return entry.profile;
  }

  static set(userId: string, profile: Record<string, unknown> | null, ttlMs = DEFAULT_TTL_MS): void {
    if (this.cache.size >= MAX_ENTRIES) {
      const oldestKey = this.cache.keys().next().value;
      if (oldestKey) this.cache.delete(oldestKey);
    }
    this.cache.set(userId, {
      profile,
      expiresAtMs: Date.now() + Math.max(5_000, ttlMs)
    });
  }

  static invalidate(userId: string): void {
    this.cache.delete(userId);
  }
}
