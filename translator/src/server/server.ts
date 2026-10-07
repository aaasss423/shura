/**
 * REST server.
 *
 * Built directly on node:http to keep the platform dependency-free and to make
 * cancellation semantics explicit: closing the socket aborts the in-flight
 * TranslationEngine request instead of leaving it hanging.
 *
 * Contract is deliberately stable so a host app (Shura) can depend on it.
 */

import * as http from 'node:http';
import type { IncomingMessage } from 'node:http';
import * as fs from 'node:fs';
import * as path from 'node:path';
import type { Translator } from '../translator/translator';
import type { ChapterTranslationResult, TranslationResult } from '../core/types';
import { CancellationToken } from '../core/cancellation';
import { toTranslationError, TranslationError } from '../core/errors';
import type { Logger } from '../core/logger';
import { silentLogger } from '../core/logger';
import { normalizeArabic } from '../arabic/arabic';
import { describeConfig } from '../config/index';
import { validateChapterInput, validateDetectInput, validateTranslateInput } from '../core/validation';
import { handlePlatformRoute } from './platformRoutes';
import type { Platform } from '../platform/index';
import type { ApiKeyRecord, User } from '../platform/auth/repository';

export interface ServerOptions {
  translator: Translator;
  host?: string;
  port?: number;
  logger?: Logger;
  /** Directory holding index.html/app.js/styles.css. */
  uiDir?: string;
  maxBodyBytes?: number;
  /**
   * Optional platform layer (knowledge, memory, glossary, research, auth).
   * Absent: the server behaves exactly as before, with no new endpoints.
   */
  platform?: Platform;
}

export interface StartedServer {
  server: http.Server;
  url: string;
  port: number;
  close: () => Promise<void>;
}

const MAX_BODY_BYTES = 2 * 1024 * 1024;

interface RequestContext {
  token: CancellationToken;
  /** Aborts when the client disconnects. */
  detached: boolean;
}

export function createApp(options: ServerOptions): http.RequestListener {
  const { translator, platform } = options;
  const logger = options.logger ?? silentLogger;
  const uiDir = options.uiDir ?? resolveUiDir();
  const maxBodyBytes = options.maxBodyBytes ?? MAX_BODY_BYTES;

  return async (req, res): Promise<void> => {
    const started = Date.now();
    const token = new CancellationToken();
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);

    // Client disconnect must cancel the translation, not just the response.
    const onClose = (): void => token.cancel({ reason: 'client disconnected' });
    req.on('aborted', onClose);
    res.on('close', () => {
      if (!res.writableEnded) {
        token.cancel({ reason: 'client disconnected' });
      }
    });

    res.setHeader('x-powered-by', 'translation-platform');

    try {
      const handled = await route(req, res, url, { translator, logger, uiDir, maxBodyBytes, token, ...(platform ? { platform } : {}) });
      if (!handled) {
        sendJson(res, 404, { error: { code: 'NOT_FOUND', message: `no route for ${req.method} ${url.pathname}` } });
      }
    } catch (error) {
      sendError(res, error, logger);
    } finally {
      req.off('aborted', onClose);
      logger.debug('request completed', {
        method: req.method,
        path: url.pathname,
        status: res.statusCode,
        elapsedMs: Date.now() - started,
        cancelled: token.isCancelled,
      });
    }
  };
}

interface RouteDeps {
  translator: Translator;
  logger: Logger;
  uiDir?: string;
  maxBodyBytes: number;
  token: CancellationToken;
  platform?: Platform;
}

async function route(
  req: http.IncomingMessage,
  res: http.ServerResponse,
  url: URL,
  deps: RouteDeps,
): Promise<boolean> {
  const { translator } = deps;
  const method = req.method ?? 'GET';
  const p = url.pathname.replace(/\/+$/, '') || '/';

  // --- Public metadata ---------------------------------------------------
  if (p === '/health' && method === 'GET') {
    sendJson(res, 200, {
      status: 'ok',
      engine: translator.engine,
      uptimeSeconds: Math.round(process.uptime()),
    });
    return true;
  }

  if (p === '/languages' && method === 'GET') {
    sendJson(res, 200, translator.getSupportedLanguages());
    return true;
  }

  if (p === '/engine/capabilities' && method === 'GET') {
    const engineId = url.searchParams.get('engine') ?? undefined;
    sendJson(res, 200, translator.getEngineCapabilities(engineId));
    return true;
  }

  if (p === '/engines' && method === 'GET') {
    // Additive: per-engine availability and limits. No credential material.
    sendJson(res, 200, { engines: translator.describeEngines(), routing: translator.describeRouting() });
    return true;
  }

  if (p === '/engine/route' && method === 'GET') {
    const sourceLanguage = url.searchParams.get('source') ?? 'auto';
    const requested = url.searchParams.get('engine') ?? undefined;
    const detected =
      sourceLanguage === 'auto' ? 'und' : sourceLanguage;
    sendJson(res, 200, translator.resolveEngineFor(detected, requested));
    return true;
  }

  if (p === '/engine/health' && method === 'GET') {
    const engineId = url.searchParams.get('engine') ?? undefined;
    const health = await translator.checkEngineHealth(engineId);
    sendJson(res, health.healthy ? 200 : 503, health);
    return true;
  }

  if (p === '/config' && method === 'GET') {
    // Credentials are never included; describeConfig redacts them.
    sendJson(res, 200, describeConfig(translator.settings));
    return true;
  }

  // --- Runtime secrets (values are never returned) ------------------------
  if (p === '/secrets' && method === 'GET') {
    sendJson(res, 200, { secrets: await translator.describeSecrets() });
    return true;
  }

  if (p === '/secrets/deepl' && method === 'PUT') {
    const body = (await readJsonBody(req, deps.maxBodyBytes)) as { apiKey?: unknown };
    if (typeof body.apiKey !== 'string' || body.apiKey.trim().length === 0) {
      sendJson(res, 400, {
        error: { code: 'VALIDATION_ERROR', message: 'apiKey must be a non-empty string' },
      });
      return true;
    }
    const status = await translator.saveSecret('deepl', body.apiKey.trim());
    // Response carries status only; the key is never echoed back.
    sendJson(res, 200, { secret: status });
    return true;
  }

  if (p === '/secrets/deepl' && method === 'DELETE') {
    sendJson(res, 200, { secret: await translator.deleteSecret('deepl') });
    return true;
  }

  if (p === '/cache' && method === 'DELETE') {
    await translator.clearCache();
    sendJson(res, 200, { cleared: true, size: await translator.cacheSize() });
    return true;
  }

  // --- Translation endpoints -------------------------------------------
  if (p === '/translate' && method === 'POST') {
    const body = await readJsonBody(req, deps.maxBodyBytes);
    const validated = validateTranslateInput({
      text: (body as { text?: unknown }).text,
      sourceLanguage: (body as { sourceLanguage?: unknown }).sourceLanguage ?? 'auto',
      targetLanguage: (body as { targetLanguage?: unknown }).targetLanguage,
    });
    const result = await translator.translate({
      text: validated.text,
      sourceLanguage: validated.sourceLanguage,
      targetLanguage: validated.targetLanguage,
      ...(readOptionalString(body, 'engine') === undefined ? {} : { engine: readOptionalString(body, 'engine')! }),
      ...(readOptionalBoolean(body, 'noCache') === undefined ? {} : { noCache: readOptionalBoolean(body, 'noCache')! }),
      ...(readOptionalBoolean(body, 'refresh') === undefined ? {} : { refresh: readOptionalBoolean(body, 'refresh')! }),
      ...(readOptionalNumber(body, 'timeoutMs') === undefined ? {} : { timeoutMs: readOptionalNumber(body, 'timeoutMs')! }),
      ...(readOptionalNumber(body, 'retries') === undefined ? {} : { retries: readOptionalNumber(body, 'retries')! }),
      ...(readOptionalNumber(body, 'deadlineMs') === undefined ? {} : { deadlineMs: readOptionalNumber(body, 'deadlineMs')! }),
      ...(readOptionalString(body, 'contextBefore') === undefined
        ? {}
        : { contextBefore: readOptionalString(body, 'contextBefore')! }),
      token: deps.token,
    });
    sendJson(res, 200, serializeTranslation(result));
    return true;
  }

  if (p === '/detect-language' && method === 'POST') {
    const body = await readJsonBody(req, deps.maxBodyBytes);
    const text = validateDetectInput({ text: (body as { text?: unknown }).text });
    const detection = translator.detectLanguage({ text });
    sendJson(res, 200, {
      language: detection.language,
      confidence: detection.confidence,
      evidence: detection.evidence,
      alternatives: detection.alternatives,
      direction: getDirection(detection.language),
    });
    return true;
  }

  if (p === '/translate/chapter' && method === 'POST') {
    const body = (await readJsonBody(req, deps.maxBodyBytes)) as Record<string, unknown>;
    const validated = validateChapterInput({
      segments: body.segments,
      sourceLanguage: body.sourceLanguage ?? 'auto',
      targetLanguage: body.targetLanguage,
    });
    const engine = readOptionalString(body, 'engine');
    const joinWith = readOptionalString(body, 'joinWith');
    const deadlineMs = readOptionalNumber(body, 'deadlineMs');
    const concurrency = readOptionalNumber(body, 'concurrency');
    const noCache = readOptionalBoolean(body, 'noCache');
    const failurePolicy = readOptionalString(body, 'failurePolicy');

    const progressEvents: unknown[] = [];
    const result = await translator.translateChapter({
      segments: validated.segments,
      sourceLanguage: validated.sourceLanguage,
      targetLanguage: validated.targetLanguage,
      ...(engine === undefined ? {} : { engine }),
      ...(joinWith === undefined ? {} : { joinWith }),
      ...(deadlineMs === undefined ? {} : { deadlineMs }),
      ...(concurrency === undefined ? {} : { concurrency }),
      ...(noCache === undefined ? {} : { noCache }),
      ...(failurePolicy === 'abort' || failurePolicy === 'partial' ? { failurePolicy } : {}),
      token: deps.token,
      onProgress: (progress) => {
        progressEvents.push(progress);
      },
    });
    sendJson(res, 200, { ...serializeChapter(result), progressEvents });
    return true;
  }

  // --- Platform endpoints (additive; skipped entirely without a platform) --
  if (deps.platform) {
    const handled = await handlePlatformRoute(
      {
        platform: deps.platform,
        readJson: (request) => readJsonBody(request, deps.maxBodyBytes),
        sendJson: (response, status, payload) => sendJson(response, status, payload),
        authenticate: (request) => {
          const resolved = authenticateRequest(request, deps.platform!);
          return resolved
            ? { userId: resolved.userId, keyId: resolved.keyId, scopes: resolved.scopes }
            : undefined;
        },
      },
      req,
      res,
      url,
    );
    if (handled) {
      return true;
    }
  }

  // --- Static UI ---------------------------------------------------------
  if (method === 'GET' || method === 'HEAD') {
    return serveStatic(req, res, p, deps.uiDir);
  }

  return false;
}

interface AuthenticatedIdentity {
  user: User;
  key: ApiKeyRecord;
  userId: string;
  keyId: number;
  scopes: string[];
}

/** Bearer key or `x-api-key` header. Returns undefined when absent or invalid. */
function authenticateRequest(req: IncomingMessage, platform: Platform): AuthenticatedIdentity | undefined {
  const header = req.headers.authorization;
  const presented = header?.startsWith('Bearer ')
    ? header.slice(7).trim()
    : (req.headers['x-api-key'] as string | undefined)?.trim();
  if (!presented) {
    return undefined;
  }
  const result = platform.auth.authenticate(presented);
  if (!result) {
    return undefined;
  }
  return {
    user: result.user,
    key: result.key,
    userId: result.user.id,
    keyId: result.key.id,
    scopes: result.key.scopes,
  };
}

function serializeTranslation(result: TranslationResult): Record<string, unknown> {
  const text = result.targetLanguage === 'ar' ? normalizeArabic(result.text) : result.text;
  return {
    text,
    rawText: result.text,
    sourceLanguage: result.sourceLanguage,
    ...(result.detectedLanguage ? { detectedLanguage: result.detectedLanguage } : {}),
    targetLanguage: result.targetLanguage,
    engine: result.engine,
    fromCache: result.fromCache,
    segments: result.segments,
    elapsedMs: result.elapsedMs,
    direction: getDirection(result.targetLanguage),
    quality: result.quality ?? null,
  };
}

function serializeChapter(result: ChapterTranslationResult): Record<string, unknown> {
  return {
    text: result.targetLanguage === 'ar' ? normalizeArabic(result.text) : result.text,
    rawText: result.text,
    targetLanguage: result.targetLanguage,
    engine: result.engine,
    segments: result.segments,
    progress: result.progress,
    elapsedMs: result.elapsedMs,
    degraded: result.degraded,
    direction: getDirection(result.targetLanguage),
  };
}

function getDirection(language: string): 'ltr' | 'rtl' {
  return language === 'ar' ? 'rtl' : 'ltr';
}

function readOptionalString(body: unknown, key: string): string | undefined {
  const value = (body as Record<string, unknown> | undefined)?.[key];
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function readOptionalBoolean(body: unknown, key: string): boolean | undefined {
  const value = (body as Record<string, unknown> | undefined)?.[key];
  return typeof value === 'boolean' ? value : undefined;
}

function readOptionalNumber(body: unknown, key: string): number | undefined {
  const value = (body as Record<string, unknown> | undefined)?.[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return undefined;
  }
  return value;
}

async function readJsonBody(req: http.IncomingMessage, maxBytes: number): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const buf = chunk as Buffer;
    size += buf.length;
    if (size > maxBytes) {
      throw new TranslationError('VALIDATION_ERROR', `request body exceeds ${maxBytes} bytes`, {
        retryable: false,
      });
    }
    chunks.push(buf);
  }
  if (size === 0) {
    return {};
  }
  const raw = Buffer.concat(chunks).toString('utf8');
  try {
    return JSON.parse(raw);
  } catch {
    throw new TranslationError('VALIDATION_ERROR', 'request body is not valid JSON', { retryable: false });
  }
}

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

function resolveUiDir(): string | undefined {
  const candidates = [
    path.join(__dirname, '..', 'ui'),
    path.join(__dirname, '..', '..', '..', 'ui'),
    path.join(process.cwd(), 'ui'),
  ];
  for (const candidate of candidates) {
    if (fs.existsSync(path.join(candidate, 'index.html'))) {
      return candidate;
    }
  }
  return undefined;
}

function serveStatic(
  req: http.IncomingMessage,
  res: http.ServerResponse,
  pathname: string,
  uiDir: string | undefined,
): boolean {
  if (!uiDir) {
    return false;
  }
  const relative = pathname === '/' ? 'index.html' : pathname.replace(/^\//, '');
  // Prevent path traversal outside the UI directory.
  const resolved = path.resolve(uiDir, relative);
  if (!resolved.startsWith(path.resolve(uiDir))) {
    sendJson(res, 403, { error: { code: 'FORBIDDEN', message: 'path traversal rejected' } });
    return true;
  }
  if (!fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) {
    return false;
  }
  const contentType = MIME[path.extname(resolved)] ?? 'application/octet-stream';
  res.writeHead(200, {
    'content-type': contentType,
    'cache-control': 'no-cache',
  });
  if (req.method === 'HEAD') {
    res.end();
    return true;
  }
  res.end(fs.readFileSync(resolved));
  return true;
}

function sendJson(res: http.ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
  });
  res.end(body);
}

function sendError(res: http.ServerResponse, error: unknown, logger: Logger): void {
  const normalized = toTranslationError(error);
  if (res.headersSent) {
    res.end();
    return;
  }
  if (normalized.status >= 500) {
    logger.error('request failed', { code: normalized.code, message: normalized.message });
  }
  sendJson(res, normalized.status, { error: normalized.toJSON() });
}

export async function startServer(options: ServerOptions): Promise<StartedServer> {
  const app = createApp(options);
  const server = http.createServer((req, res) => {
    void app(req, res);
  });

  const host = options.host ?? '127.0.0.1';
  const port = options.port ?? 8787;

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.off('error', reject);
      resolve();
    });
  });

  const address = server.address();
  const actualPort = typeof address === 'object' && address ? address.port : port;

  return {
    server,
    port: actualPort,
    url: `http://${host}:${actualPort}`,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections?.();
        server.close(() => resolve());
      }),
  };
}

export type { RequestContext };