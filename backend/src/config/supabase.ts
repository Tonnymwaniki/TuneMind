import { createClient, SupabaseClient } from '@supabase/supabase-js';

const envSupabaseUrl = (process.env.SUPABASE_URL || process.env.VITE_SUPABASE_URL || '').trim();
const envSupabaseKey = (
  process.env.SUPABASE_SERVICE_ROLE_KEY ||
  process.env.SUPABASE_ANON_KEY ||
  process.env.SUPABASE_KEY ||
  process.env.VITE_SUPABASE_ANON_KEY ||
  ''
).trim();

let runtimeSupabaseUrl = envSupabaseUrl;
let runtimeSupabaseKey = envSupabaseKey;
let cachedDynamicClient: SupabaseClient | null = null;
let cachedDynamicSignature = '';

if (!envSupabaseUrl || !envSupabaseKey) {
  console.warn('[Supabase] SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY not in .env yet — will also accept live credentials from client telemetry.');
}

export const supabase: SupabaseClient = createClient(
  envSupabaseUrl || 'https://placeholder.supabase.co',
  envSupabaseKey || 'placeholder-service-role-key',
  {
    auth: {
      persistSession: false,
      autoRefreshToken: false
    }
  }
);

export function updateRuntimeSupabaseConfig(url?: string, key?: string): void {
  const cleanUrl = (url || '').trim().replace(/\/+$/, '');
  const cleanKey = (key || '').trim();
  if (
    cleanUrl.startsWith('https://') &&
    !cleanUrl.includes('placeholder.supabase.co') &&
    cleanKey.length > 20 &&
    cleanKey !== 'placeholder-service-role-key'
  ) {
    if (!runtimeSupabaseUrl || runtimeSupabaseUrl.includes('placeholder.supabase.co')) {
      runtimeSupabaseUrl = cleanUrl;
    }
    if (!runtimeSupabaseKey || runtimeSupabaseKey === 'placeholder-service-role-key') {
      runtimeSupabaseKey = cleanKey;
    }
  }
}

export function isSupabaseLive(overrideUrl?: string, overrideKey?: string): boolean {
  updateRuntimeSupabaseConfig(overrideUrl, overrideKey);
  const activeUrl = (overrideUrl || runtimeSupabaseUrl || '').trim();
  const activeKey = (overrideKey || runtimeSupabaseKey || '').trim();
  return (
    activeUrl.startsWith('https://') &&
    !activeUrl.includes('placeholder.supabase.co') &&
    activeKey.length > 20 &&
    activeKey !== 'placeholder-service-role-key'
  );
}

export function getLiveSupabaseClient(overrideUrl?: string, overrideKey?: string): SupabaseClient | null {
  updateRuntimeSupabaseConfig(overrideUrl, overrideKey);
  const activeUrl = (overrideUrl || runtimeSupabaseUrl || '').trim().replace(/\/+$/, '');
  const activeKey = (overrideKey || runtimeSupabaseKey || '').trim();

  if (!isSupabaseLive(activeUrl, activeKey)) {
    return null;
  }

  if (activeUrl === envSupabaseUrl && activeKey === envSupabaseKey) {
    return supabase;
  }

  const sig = `${activeUrl}::${activeKey.slice(0, 16)}`;
  if (cachedDynamicClient && cachedDynamicSignature === sig) {
    return cachedDynamicClient;
  }

  cachedDynamicClient = createClient(activeUrl, activeKey, {
    auth: {
      persistSession: false,
      autoRefreshToken: false
    }
  });
  cachedDynamicSignature = sig;
  return cachedDynamicClient;
}
