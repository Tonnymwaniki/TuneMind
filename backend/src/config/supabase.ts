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

export function updateRuntimeSupabaseConfig(_url?: string, _key?: string): void {
  // Security: never allow client payloads to override server Supabase URL or keys.
}

export function isSupabaseLive(_overrideUrl?: string, _overrideKey?: string): boolean {
  const activeUrl = (runtimeSupabaseUrl || '').trim();
  const activeKey = (runtimeSupabaseKey || '').trim();
  return (
    activeUrl.startsWith('https://') &&
    !activeUrl.includes('placeholder.supabase.co') &&
    activeKey.length > 20 &&
    activeKey !== 'placeholder-service-role-key'
  );
}

let cachedAdminClient: SupabaseClient | null = null;
let cachedAdminSignature = '';

export function isServiceRoleConfigured(): boolean {
  const serviceKey = (process.env.SUPABASE_SERVICE_ROLE_KEY || '').trim();
  const activeUrl = (process.env.SUPABASE_URL || process.env.VITE_SUPABASE_URL || runtimeSupabaseUrl || '').trim();
  return (
    activeUrl.startsWith('https://') &&
    !activeUrl.includes('placeholder.supabase.co') &&
    serviceKey.length > 20 &&
    serviceKey !== 'placeholder-service-role-key'
  );
}

export function getSupabaseAdminClient(overrideUrl?: string): SupabaseClient | null {
  const serviceKey = (process.env.SUPABASE_SERVICE_ROLE_KEY || '').trim();
  const activeUrl = (overrideUrl || process.env.SUPABASE_URL || process.env.VITE_SUPABASE_URL || runtimeSupabaseUrl || '')
    .trim()
    .replace(/\/+$/, '');

  if (
    !activeUrl.startsWith('https://') ||
    activeUrl.includes('placeholder.supabase.co') ||
    serviceKey.length <= 20 ||
    serviceKey === 'placeholder-service-role-key'
  ) {
    return null;
  }

  const sig = `${activeUrl}::${serviceKey.slice(0, 16)}`;
  if (cachedAdminClient && cachedAdminSignature === sig) {
    return cachedAdminClient;
  }

  cachedAdminClient = createClient(activeUrl, serviceKey, {
    auth: {
      persistSession: false,
      autoRefreshToken: false
    }
  });
  cachedAdminSignature = sig;
  return cachedAdminClient;
}

export function getLiveSupabaseClient(_overrideUrl?: string, _overrideKey?: string): SupabaseClient | null {
  const activeUrl = (runtimeSupabaseUrl || '').trim().replace(/\/+$/, '');
  const activeKey = (runtimeSupabaseKey || '').trim();

  if (!isSupabaseLive()) {
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

