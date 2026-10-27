import { Request, Response, NextFunction } from 'express';

/**
 * Simple request logger middleware.
 * Phase 1: Logs method, path, and response status.
 */
export function requestLogger(req: Request, res: Response, next: NextFunction): void {
  const start = Date.now();
  res.on('finish', () => {
    const ms = Date.now() - start;
    console.log(`${req.method} ${req.path} → ${res.statusCode} (${ms}ms)`);
  });
  next();
}
