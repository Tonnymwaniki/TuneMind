/**
 * Import this module before any other application module so that backend/.env
 * is applied before modules read process.env at import time.
 */
import { loadEnvironment } from './config/env';

loadEnvironment();
