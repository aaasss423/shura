/**
 * Platform REST endpoints (requirement 26).
 *
 * Additive only: every pre-existing endpoint keeps its path, method and response
 * shape. New capability lives under new paths, and auth is enforced only when
 * ENABLE_AUTH is on.
 *
 * Groups: /auth, /users, /knowledge, /glossary, /memory, /research, /plans,
 * /usage, /jobs, /admin, /metrics, /flags, /prewarm
 */

import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Platform } from '../platform/index';
import { AuthRepository } from '../platform/auth/repository';
import { hashApiKey } from '../platform/auth/repository';
import { FeatureDisabledError } from '../platform/flags';
import { JobQueue, QueueSaturatedError } from '../platform/jobs/queue';
import { RateLimitError, ConcurrencyLimitError } from '../platform/metrics';
import { Prewarmer } from '../platform/prewarm';
import { isTranslationError } from '../core/errors';

export interface PlatformRouteDeps {
  platform: Platform;
  readJson: (req: IncomingMessage) => Promise<unknown>;
  sendJson: (res: ServerResponse, status: number, payload: unknown) => void;
  /** Resolves the API key from the Authorization header, when auth is enabled. */
  authenticate?: (req: IncomingMessage) => { userId: string; keyId: number; scopes: string[] } | undefined;
}

type Handler = (deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage) => Promise<boolean>;

/** Path prefix → handler. Tried in order; the first match wins. */
const ROUTES: Array<{ prefix: string; handler: Handler }> = [
  { prefix: '/flags', handler: flagsHandler },
  { prefix: '/metrics', handler: metricsHandler },
  { prefix: '/plans', handler: plansHandler },
  { prefix: '/usage', handler: usageHandler },
  { prefix: '/auth/keys', handler: authKeysHandler },
  { prefix: '/auth', handler: authHandler },
  { prefix: '/knowledge', handler: knowledgeHandler },
  { prefix: '/glossary', handler: glossaryHandler },
  { prefix: '/memory', handler: memoryHandler },
  { prefix: '/research', handler: researchHandler },
  { prefix: '/prewarm', handler: prewarmHandler },
  { prefix: '/jobs', handler: jobsHandler },
  { prefix: '/admin', handler: adminHandler },
];

/** Returns true when the request was handled. */
export async function handlePlatformRoute(
  deps: PlatformRouteDeps,
  req: IncomingMessage,
  res: ServerResponse,
  url: URL,
): Promise<boolean> {
  const path = url.pathname.replace(/\/+$/, '') || '/';

  for (const route of ROUTES) {
    if (path === route.prefix || path.startsWith(`${route.prefix}/`)) {
      try {
        const handled = await route.handler(deps, res, url, req);
        if (handled) {
          return true;
        }
      } catch (error) {
        sendError(deps, res, error);
        return true;
      }
    }
  }
  return false;
}

function sendError(deps: PlatformRouteDeps, res: ServerResponse, error: unknown): void {
  if (res.headersSent) {
    res.end();
    return;
  }
  if (error instanceof FeatureDisabledError) {
    deps.sendJson(res, error.status, { error: { code: error.code, flag: error.flag, message: error.message } });
    return;
  }
  if (error instanceof RateLimitError || error instanceof ConcurrencyLimitError || error instanceof QueueSaturatedError) {
    deps.sendJson(res, error.status, { error: { code: error.code, message: error.message } });
    return;
  }
  if (isTranslationError(error)) {
    deps.sendJson(res, error.status, { error: error.toJSON() });
    return;
  }
  const message = error instanceof Error ? error.message : String(error);
  deps.sendJson(res, 400, { error: { code: 'BAD_REQUEST', message } });
}

function requireUser(
  deps: PlatformRouteDeps,
  req: IncomingMessage,
  scope: string,
): { userId: string; keyId: number } {
  if (!deps.platform.flags.isEnabled('ENABLE_AUTH')) {
    // Auth disabled: the caller is trusted (single-tenant / internal deployment).
    return { userId: 'anonymous', keyId: 0 };
  }
  const identity = deps.authenticate?.(req);
  if (!identity) {
    throw new HttpError(401, 'UNAUTHORIZED', 'a valid API key is required');
  }
  if (identity.scopes.length > 0 && !identity.scopes.includes(scope) && !identity.scopes.includes('admin')) {
    throw new HttpError(403, 'FORBIDDEN', `key lacks the "${scope}" scope`);
  }
  return { userId: identity.userId, keyId: identity.keyId };
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'HttpError';
  }
}

// ---------------------------------------------------------------------------

async function flagsHandler(deps: PlatformRouteDeps, res: ServerResponse): Promise<boolean> {
  deps.sendJson(res, 200, { flags: deps.platform.flags.all() });
  return true;
}

async function metricsHandler(deps: PlatformRouteDeps, res: ServerResponse): Promise<boolean> {
  deps.sendJson(res, 200, {
    ...deps.platform.metrics.snapshot(),
    research: deps.platform.research.stats(),
    jobs: deps.platform.jobs.stats(),
    inFlight: deps.platform.concurrency.total(),
  });
  return true;
}

async function plansHandler(deps: PlatformRouteDeps, res: ServerResponse): Promise<boolean> {
  deps.sendJson(res, 200, { plans: deps.platform.auth.listPlans() });
  return true;
}

async function usageHandler(deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage): Promise<boolean> {
  const identity = requireUser(deps, req, 'usage');
  const day = url.searchParams.get('day') ?? AuthRepository.dayOf();
  deps.sendJson(res, 200, {
    userId: identity.userId,
    day,
    usage: deps.platform.auth.getUsage(identity.userId, day),
  });
  return true;
}

async function authHandler(deps: PlatformRouteDeps, res: ServerResponse, _url: URL, req: IncomingMessage): Promise<boolean> {
  if ((req.method ?? 'GET').toUpperCase() !== 'POST') {
    return false;
  }
  const body = (await deps.readJson(req)) as { userId?: unknown; email?: unknown; planId?: unknown };
  if (typeof body.userId !== 'string' || body.userId.trim().length === 0) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'userId is required');
  }
  const planId = typeof body.planId === 'string' ? body.planId : 'FREE';
  deps.platform.auth.createUser({
    id: body.userId,
    planId,
    ...(typeof body.email === 'string' ? { email: body.email } : {}),
  });
  deps.platform.auth.audit({ actor: 'system', action: 'user.create', subjectType: 'user', subjectId: body.userId });
  const user = deps.platform.auth.requireUser(body.userId);
  deps.sendJson(res, 201, { user });
  return true;
}

async function authKeysHandler(deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage): Promise<boolean> {
  const method = (req.method ?? 'GET').toUpperCase();
  const identity = requireUser(deps, req, 'keys');
  const auth = deps.platform.auth;

  if (method === 'GET') {
    deps.sendJson(res, 200, { keys: auth.listApiKeys(identity.userId) });
    return true;
  }

  if (method === 'POST') {
    const body = (await deps.readJson(req)) as {
      name?: unknown;
      scopes?: unknown;
      dailyLimit?: unknown;
      expiresAt?: unknown;
    };
    if (typeof body.name !== 'string' || body.name.trim().length === 0) {
      throw new HttpError(400, 'VALIDATION_ERROR', 'name is required');
    }
    const created = auth.createApiKey({
      userId: identity.userId,
      name: body.name,
      ...(Array.isArray(body.scopes) ? { scopes: body.scopes.map(String) } : {}),
      ...(typeof body.dailyLimit === 'number' ? { dailyLimit: body.dailyLimit } : {}),
      ...(typeof body.expiresAt === 'string' ? { expiresAt: body.expiresAt } : {}),
    });
    auth.audit({
      actor: identity.userId,
      action: 'apikey.create',
      subjectType: 'api_key',
      subjectId: String(created.record.id),
    });
    // The only time the plaintext is ever returned.
    deps.sendJson(res, 201, { key: created.record, plaintext: created.plaintext });
    return true;
  }

  const rotateMatch = url.pathname.match(/\/auth\/keys\/(\d+)\/rotate$/);
  if (method === 'POST' && rotateMatch) {
    const id = Number(rotateMatch[1]);
    const created = auth.rotateApiKey(id);
    auth.audit({ actor: identity.userId, action: 'apikey.rotate', subjectType: 'api_key', subjectId: String(id) });
    deps.sendJson(res, 200, { key: created.record, plaintext: created.plaintext });
    return true;
  }

  const idMatch = url.pathname.match(/\/auth\/keys\/(\d+)$/);
  if (idMatch) {
    const id = Number(idMatch[1]);
    if (method === 'DELETE') {
      auth.setKeyStatus(id, 'revoked');
      auth.audit({ actor: identity.userId, action: 'apikey.revoke', subjectType: 'api_key', subjectId: String(id) });
      deps.sendJson(res, 200, { key: auth.getKey(id) });
      return true;
    }
    if (method === 'PATCH') {
      const body = (await deps.readJson(req)) as { status?: unknown };
      const status = body.status === 'disabled' ? 'disabled' : 'active';
      auth.setKeyStatus(id, status);
      deps.sendJson(res, 200, { key: auth.getKey(id) });
      return true;
    }
  }

  return false;
}

async function knowledgeHandler(deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage): Promise<boolean> {
  const method = (req.method ?? 'GET').toUpperCase();
  deps.platform.flags.require('ENABLE_KNOWLEDGE');
  const kb = deps.platform.knowledge;

  if (method === 'GET' && url.pathname === '/knowledge') {
    deps.sendJson(res, 200, {
      entries: kb.list({
        ...(url.searchParams.get('sourceLanguage')
          ? { sourceLanguage: url.searchParams.get('sourceLanguage')! }
          : {}),
        ...(url.searchParams.get('targetLanguage')
          ? { targetLanguage: url.searchParams.get('targetLanguage')! }
          : {}),
        ...(url.searchParams.get('category')
          ? { category: url.searchParams.get('category') as never }
          : {}),
        limit: Number(url.searchParams.get('limit') ?? 100),
      }),
      total: kb.count(),
    });
    return true;
  }

  if (method === 'GET') {
    const term = url.searchParams.get('term');
    if (!term) {
      throw new HttpError(400, 'VALIDATION_ERROR', 'term is required');
    }
    const entry = kb.resolve(
      url.searchParams.get('sourceLanguage') ?? 'ja',
      url.searchParams.get('targetLanguage') ?? 'ar',
      term,
      {
        ...(url.searchParams.get('category') ? { category: url.searchParams.get('category') as never } : {}),
        ...(url.searchParams.get('seriesId') ? { seriesId: url.searchParams.get('seriesId')! } : {}),
      },
    );
    deps.sendJson(res, 200, { entry: entry ?? null });
    return true;
  }

  if (method === 'POST') {
    requireUser(deps, req, 'knowledge');
    const body = (await deps.readJson(req)) as Record<string, unknown>;
    const entry = kb.upsert({
      sourceLanguage: String(body.sourceLanguage ?? ''),
      targetLanguage: String(body.targetLanguage ?? ''),
      category: (body.category ?? 'term') as never,
      term: String(body.term ?? ''),
      translation: String(body.translation ?? ''),
      ...(body.meaning ? { meaning: String(body.meaning) } : {}),
      ...(body.context ? { context: String(body.context) } : {}),
      ...(body.seriesId ? { seriesId: String(body.seriesId) } : {}),
      ...(typeof body.confidence === 'number' ? { confidence: body.confidence } : {}),
      ...(body.source ? { source: String(body.source) } : {}),
      ...(body.sourceType ? { sourceType: body.sourceType as never } : {}),
      ...(body.verificationState ? { verificationState: body.verificationState as never } : {}),
    });
    deps.sendJson(res, 201, { entry });
    return true;
  }

  const verifyMatch = url.pathname.match(/^\/knowledge\/(\d+)\/verify$/);
  if (method === 'POST' && verifyMatch) {
    requireUser(deps, req, 'knowledge');
    const entry = kb.setVerificationState(Number(verifyMatch[1]), 'verified');
    deps.sendJson(res, 200, { entry });
    return true;
  }

  return false;
}

async function glossaryHandler(deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage): Promise<boolean> {
  const method = (req.method ?? 'GET').toUpperCase();
  deps.platform.flags.require('ENABLE_GLOSSARY');

  if (method === 'GET') {
    deps.sendJson(res, 200, {
      entries: deps.platform.glossary.list(
        url.searchParams.get('sourceLanguage') ?? 'ja',
        url.searchParams.get('targetLanguage') ?? 'ar',
        {
          ...(url.searchParams.get('seriesId') ? { seriesId: url.searchParams.get('seriesId')! } : {}),
          ...(url.searchParams.get('genre') ? { genre: url.searchParams.get('genre')! } : {}),
        },
      ),
      version: deps.platform.glossary.currentVersion(),
    });
    return true;
  }

  if (method === 'POST') {
    requireUser(deps, req, 'glossary');
    const body = (await deps.readJson(req)) as Record<string, unknown>;
    const entry = deps.platform.glossary.upsert({
      sourceLanguage: String(body.sourceLanguage ?? ''),
      targetLanguage: String(body.targetLanguage ?? ''),
      term: String(body.term ?? ''),
      ...(body.defaultTranslation ? { defaultTranslation: String(body.defaultTranslation) } : {}),
      ...(body.forbidden ? { forbidden: String(body.forbidden) } : {}),
      ...(body.mode ? { mode: body.mode as never } : {}),
      ...(body.category ? { category: String(body.category) } : {}),
      ...(Array.isArray(body.aliases) ? { aliases: body.aliases.map(String) } : {}),
      ...(body.context ? { context: String(body.context) } : {}),
      ...(body.seriesId ? { seriesId: String(body.seriesId) } : {}),
      ...(body.genre ? { genre: String(body.genre) } : {}),
      ...(typeof body.priority === 'number' ? { priority: body.priority } : {}),
    });
    deps.sendJson(res, 201, { entry });
    return true;
  }

  return false;
}

async function memoryHandler(deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage): Promise<boolean> {
  const method = (req.method ?? 'GET').toUpperCase();
  deps.platform.flags.require('ENABLE_TRANSLATION_MEMORY');

  if (method === 'GET') {
    if (url.searchParams.get('sourceText')) {
      const hit = deps.platform.memory.lookup(
        url.searchParams.get('sourceLanguage') ?? 'ja',
        url.searchParams.get('targetLanguage') ?? 'ar',
        url.searchParams.get('sourceText')!,
        { ...(url.searchParams.get('seriesId') ? { seriesId: url.searchParams.get('seriesId')! } : {}) },
      );
      deps.sendJson(res, 200, { entry: hit ?? null });
      return true;
    }
    deps.sendJson(res, 200, {
      entries: deps.platform.memory.list({
        ...(url.searchParams.get('sourceLanguage')
          ? { sourceLanguage: url.searchParams.get('sourceLanguage')! }
          : {}),
        limit: Number(url.searchParams.get('limit') ?? 100),
      }),
      total: deps.platform.memory.count(),
    });
    return true;
  }

  if (method === 'POST') {
    requireUser(deps, req, 'memory');
    const body = (await deps.readJson(req)) as Record<string, unknown>;
    const entry = deps.platform.memory.store({
      sourceLanguage: String(body.sourceLanguage ?? ''),
      targetLanguage: String(body.targetLanguage ?? ''),
      sourceText: String(body.sourceText ?? ''),
      targetText: String(body.targetText ?? ''),
      ...(body.context ? { context: String(body.context) } : {}),
      ...(body.seriesId ? { seriesId: String(body.seriesId) } : {}),
      ...(typeof body.confidence === 'number' ? { confidence: body.confidence } : {}),
      ...(body.provenance ? { provenance: String(body.provenance) } : {}),
    });
    deps.sendJson(res, 201, { entry });
    return true;
  }

  return false;
}

async function researchHandler(deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage): Promise<boolean> {
  const method = (req.method ?? 'GET').toUpperCase();
  deps.platform.flags.require('ENABLE_RESEARCH_AGENT');

  if (method === 'GET' && url.pathname === '/research') {
    deps.sendJson(res, 200, {
      jobs: deps.platform.research.list({
        ...(url.searchParams.get('status') ? { status: url.searchParams.get('status') as never } : {}),
        limit: Number(url.searchParams.get('limit') ?? 50),
      }),
      stats: deps.platform.research.stats(),
    });
    return true;
  }

  if (method === 'GET') {
    const match = url.pathname.match(/^\/research\/(\d+)$/);
    if (match) {
      const id = Number(match[1]);
      const job = deps.platform.research.get(id);
      deps.sendJson(res, job ? 200 : 404, {
        job: job ?? null,
        ...(job ? { sources: deps.platform.research.sources(id) } : {}),
      });
      return true;
    }
  }

  if (method === 'POST' && url.pathname === '/research') {
    requireUser(deps, req, 'research');
    const body = (await deps.readJson(req)) as Record<string, unknown>;
    const result = deps.platform.research.enqueue({
      sourceLanguage: String(body.sourceLanguage ?? ''),
      targetLanguage: String(body.targetLanguage ?? ''),
      term: String(body.term ?? ''),
      ...(body.categoryHint ? { categoryHint: String(body.categoryHint) } : {}),
      ...(body.context ? { context: String(body.context) } : {}),
      ...(typeof body.priority === 'number' ? { priority: body.priority } : {}),
    });
    deps.sendJson(res, result.created ? 201 : 200, result);
    return true;
  }

  return false;
}

async function prewarmHandler(deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage): Promise<boolean> {
  const method = (req.method ?? 'GET').toUpperCase();
  deps.platform.flags.require('ENABLE_PREWARM');
  const prewarmer = new Prewarmer(deps.platform.db, deps.platform.research);

  if (method === 'GET') {
    deps.sendJson(res, 200, { batches: prewarmer.batches() });
    return true;
  }

  if (method === 'POST' && url.pathname === '/prewarm') {
    requireUser(deps, req, 'prewarm');
    const body = (await deps.readJson(req)) as Record<string, unknown>;
    const batch = prewarmer.createBatch({
      sourceLanguage: String(body.sourceLanguage ?? 'ja'),
      targetLanguage: String(body.targetLanguage ?? 'ar'),
      sources: Array.isArray(body.sources)
        ? (body.sources as Array<{ category: never; terms: string[] }>)
        : Prewarmer.starterCorpus().filter((s) => s.sourceLanguage === (body.sourceLanguage ?? 'ja')),
    });
    deps.sendJson(res, 201, batch);
    return true;
  }

  const refreshMatch = url.pathname.match(/^\/prewarm\/([^/]+)\/refresh$/);
  if (method === 'POST' && refreshMatch) {
    deps.sendJson(res, 200, prewarmer.refresh(refreshMatch[1]!));
    return true;
  }

  return false;
}

async function jobsHandler(deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage): Promise<boolean> {
  const method = (req.method ?? 'GET').toUpperCase();
  deps.platform.flags.require('ENABLE_ASYNC_TRANSLATION');
  const jobs: JobQueue = deps.platform.jobs;

  if (method === 'GET' && url.pathname === '/jobs') {
    const identity = requireUser(deps, req, 'jobs');
    deps.sendJson(res, 200, {
      jobs: jobs.list({ userId: identity.userId, limit: Number(url.searchParams.get('limit') ?? 50) }),
      stats: jobs.stats(),
    });
    return true;
  }

  const cancelMatch = url.pathname.match(/^\/jobs\/([^/]+)\/cancel$/);
  if (method === 'POST' && cancelMatch) {
    requireUser(deps, req, 'jobs');
    deps.sendJson(res, 200, { job: jobs.cancel(cancelMatch[1]!) });
    return true;
  }

  const idMatch = url.pathname.match(/^\/jobs\/([^/]+)$/);
  if (idMatch && method === 'GET') {
    requireUser(deps, req, 'jobs');
    const job = jobs.get(idMatch[1]!);
    deps.sendJson(res, job ? 200 : 404, { job: job ?? null });
    return true;
  }

  return false;
}

async function adminHandler(deps: PlatformRouteDeps, res: ServerResponse, url: URL, req: IncomingMessage): Promise<boolean> {
  const method = (req.method ?? 'GET').toUpperCase();
  const identity = requireUser(deps, req, 'admin');

  if (method === 'GET' && url.pathname === '/admin/overview') {
    deps.sendJson(res, 200, {
      users: deps.platform.auth.listUsers(50),
      plans: deps.platform.auth.listPlans(),
      knowledge: deps.platform.knowledge.count(),
      translationMemory: deps.platform.memory.count(),
      glossary: deps.platform.glossary.count(),
      research: deps.platform.research.stats(),
      jobs: deps.platform.jobs.stats(),
      inFlight: deps.platform.concurrency.total(),
      metrics: deps.platform.metrics.snapshot(),
    });
    return true;
  }

  const userMatch = url.pathname.match(/^\/admin\/users\/([^/]+)$/);
  if (userMatch) {
    if (method === 'PATCH') {
      const body = (await deps.readJson(req)) as Record<string, unknown>;
      const userId = userMatch[1]!;
      if (body.status === 'active' || body.status === 'disabled') {
        deps.platform.auth.setUserStatus(userId, body.status, typeof body.reason === 'string' ? body.reason : undefined);
      }
      if (
        body.daily !== undefined ||
        body.monthly !== undefined ||
        body.maxParallel !== undefined
      ) {
        deps.platform.auth.setUserLimits(userId, {
          ...(body.daily === undefined ? {} : { daily: body.daily as number | null }),
          ...(body.monthly === undefined ? {} : { monthly: body.monthly as number | null }),
          ...(body.maxParallel === undefined ? {} : { maxParallel: body.maxParallel as number }),
        });
      }
      deps.platform.auth.audit({
        actor: identity.userId,
        action: 'admin.user.update',
        subjectType: 'user',
        subjectId: userId,
      });
      deps.sendJson(res, 200, { user: deps.platform.auth.requireUser(userId) });
      return true;
    }
    if (method === 'GET') {
      deps.sendJson(res, 200, {
        user: deps.platform.auth.getUser(userMatch[1]!),
        keys: deps.platform.auth.listApiKeys(userMatch[1]!),
        usage: deps.platform.auth.getUsage(userMatch[1]!),
        audit: deps.platform.auth.auditTrail({ subjectType: 'user', subjectId: userMatch[1]! }),
      });
      return true;
    }
  }

  if (method === 'GET' && url.pathname === '/admin/audit') {
    deps.sendJson(res, 200, { entries: deps.platform.auth.auditTrail({ limit: 100 }) });
    return true;
  }

  return false;
}

/** Exposed for tests: resolve a presented key without touching HTTP. */
export function resolveKey(platform: Platform, presented: string) {
  return platform.auth.authenticate(presented);
}

export { hashApiKey };