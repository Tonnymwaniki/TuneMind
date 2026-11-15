import { Request, Response, NextFunction } from 'express';

/**
 * Production security headers and CORS policy middleware.
 * Protects HTTP routes against MIME sniffing, framing/clickjacking,
 * overly broad referrer leakage, and handles CORS preflight cleanly.
 */
export function securityHeaders(req: Request, res: Response, next: NextFunction): void {
  const allowedOrigin = (process.env.CORS_ALLOWED_ORIGIN || '*').trim() || '*';

  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('X-XSS-Protection', '0');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(self), geolocation=()');
  res.setHeader('Cross-Origin-Resource-Policy', 'cross-origin');

  const isHttps = req.secure || req.headers['x-forwarded-proto'] === 'https';
  if (isHttps) {
    res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  }

  res.setHeader('Access-Control-Allow-Origin', allowedOrigin);
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Requested-With');
  res.setHeader('Access-Control-Max-Age', '86400');

  if (req.method === 'OPTIONS') {
    res.status(204).end();
    return;
  }

  next();
}
