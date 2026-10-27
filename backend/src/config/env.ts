import fs from 'fs';
import path from 'path';

let loaded = false;

/**
 * Loads backend/.env into process.env once.
 *
 * Works for both startup modes because it runs inside the app:
 *   - npm run dev  (ts-node src/index.ts)
 *   - npm start    (node dist/index.js)
 *
 * Uses Node's built-in process.loadEnvFile when available (Node >= 20.12).
 * Falls back to a minimal KEY=VALUE parser on older Node 20 releases.
 * Existing environment variables take precedence and are never overwritten.
 *
 * Values are never logged.
 */
export function loadEnvironment(): void {
    if (loaded) return;
    loaded = true;

    const envPath = process.env['TUNEMIND_ENV_FILE']?.trim()
        || path.resolve(__dirname, '..', '..', '.env');

    if (!fs.existsSync(envPath)) return;

    try {
        const maybeLoadEnvFile = (process as unknown as {
            loadEnvFile?: (file: string) => void;
        }).loadEnvFile;

        if (typeof maybeLoadEnvFile === 'function') {
            maybeLoadEnvFile.call(process, envPath);
            return;
        }

        applyMinimalEnvFile(envPath);
    } catch (error) {
        // Only the file path and message are logged — never file contents.
        console.warn(`[env] Could not load ${envPath}: ${(error as Error).message}`);
    }
}

/** Minimal dotenv-compatible parser used only when process.loadEnvFile is unavailable. */
function applyMinimalEnvFile(envPath: string): void {
    const contents = fs.readFileSync(envPath, 'utf8');
    for (const rawLine of contents.split(/\r?\n/)) {
        const line = rawLine.trim();
        if (line.length === 0 || line.startsWith('#')) continue;

        const separator = line.indexOf('=');
        if (separator <= 0) continue;

        const key = line.slice(0, separator).trim();
        if (key.length === 0 || process.env[key] !== undefined) continue;

        let value = line.slice(separator + 1).trim();
        if (
            value.length >= 2
            && ((value.startsWith('"') && value.endsWith('"'))
                || (value.startsWith("'") && value.endsWith("'")))
        ) {
            value = value.slice(1, -1);
        }
        process.env[key] = value;
    }
}
