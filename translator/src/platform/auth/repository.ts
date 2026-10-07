/**
 * API keys, users, plans and entitlements.
 *
 * Keys are stored as a hash plus a display prefix. The plaintext exists exactly
 * once: in the response to `create`, and never again — not in the database, not
 * in a log, not in an audit record. That is what "we cannot show it to you
 * again" has to mean to be true.
 */

import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import type { Database } from '../db/database';

export type UserKind = 'ADMIN' | 'SHURA' | 'PREMIUM' | 'FREE' | 'API_USER' | 'BYOK';
export type KeyStatus = 'active' | 'disabled' | 'revoked' | 'expired';

export interface PlanDefinition {
  id: string;
  name: string;
  /** null means no product quota. Not "no infrastructure protection". */
  dailyRequests: number | null;
  monthlyRequests: number | null;
  maxParallel: number;
  maxCharsPerRequest: number;
  priority: number;
  allowsByok: boolean;
  unlimitedProduct: boolean;
  notes?: string;
}

/**
 * Plans are configuration, not code (requirement 18). The values here are
 * defaults; a deployment seeds its own rows.
 */
export const DEFAULT_PLANS: PlanDefinition[] = [
  {
    id: 'ADMIN',
    name: 'Administrator',
    dailyRequests: null,
    monthlyRequests: null,
    maxParallel: 8,
    maxCharsPerRequest: 20_000,
    priority: 1,
    allowsByok: true,
    unlimitedProduct: true,
    notes: 'No product quota. Infrastructure limits still apply.',
  },
  {
    id: 'SHURA',
    name: 'Shura reader',
    dailyRequests: null,
    monthlyRequests: null,
    maxParallel: 6,
    maxCharsPerRequest: 10_000,
    priority: 2,
    allowsByok: true,
    unlimitedProduct: true,
    notes: 'First-party reader integration.',
  },
  {
    id: 'PREMIUM',
    name: 'Premium',
    // "No normal daily translation quota" — abuse limits still apply.
    dailyRequests: 200_000,
    monthlyRequests: 4_000_000,
    maxParallel: 4,
    maxCharsPerRequest: 10_000,
    priority: 3,
    allowsByok: true,
    unlimitedProduct: false,
  },
  {
    id: 'FREE',
    name: 'Free',
    dailyRequests: 200,
    monthlyRequests: 5_000,
    maxParallel: 1,
    maxCharsPerRequest: 2_000,
    priority: 8,
    allowsByok: false,
    unlimitedProduct: false,
  },
  {
    id: 'API_USER',
    name: 'API user',
    dailyRequests: 10_000,
    monthlyRequests: 200_000,
    maxParallel: 3,
    maxCharsPerRequest: 8_000,
    priority: 5,
    allowsByok: false,
    unlimitedProduct: false,
    notes: 'Daily limit is admin-configurable per user.',
  },
  {
    id: 'BYOK',
    name: 'Bring your own key',
    dailyRequests: null,
    monthlyRequests: null,
    maxParallel: 2,
    maxCharsPerRequest: 5_000,
    priority: 6,
    allowsByok: true,
    unlimitedProduct: true,
    notes: 'Product quota is the user\'s own provider quota.',
  },
];

export interface User {
  id: string;
  email?: string;
  displayName?: string;
  planId: string;
  kind: UserKind;
  status: string;
  dailyOverride?: number;
  monthlyOverride?: number;
  maxParallelOverride?: number;
  createdAt: string;
}

export interface ApiKeyRecord {
  id: number;
  userId: string;
  name: string;
  prefix: string;
  scopes: string[];
  status: KeyStatus;
  dailyLimit?: number;
  usageCount: number;
  lastUsedAt?: string;
  expiresAt?: string;
  createdAt: string;
}

export interface CreatedApiKey {
  record: ApiKeyRecord;
  /** Shown exactly once. Never stored. */
  plaintext: string;
}

export interface EntitlementDecision {
  allowed: boolean;
  reason?: string;
  limit?: number;
  used?: number;
  remaining?: number;
  maxParallel: number;
  maxCharsPerRequest: number;
  priority: number;
}

/** Fast, non-reversible hash. Keys are high-entropy, so a slow KDF buys nothing. */
export function hashApiKey(plaintext: string): string {
  return createHash('sha256').update(`tp-apikey:${plaintext}`).digest('hex');
}

export function generateApiKey(prefix = 'tp'): { plaintext: string; prefix: string } {
  const body = randomBytes(24).toString('base64url');
  return { plaintext: `${prefix}_${body}`, prefix: `${prefix}_${body.slice(0, 8)}` };
}

/** Constant-time comparison; never a plain `===` on secrets. */
export function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  if (left.length !== right.length) {
    return false;
  }
  return timingSafeEqual(left, right);
}

export class AuthRepository {
  constructor(private readonly db: Database) {}

  /** Seeds the default plans. Idempotent. */
  seedPlans(plans: PlanDefinition[] = DEFAULT_PLANS): number {
    let count = 0;
    this.db.transaction(() => {
      for (const plan of plans) {
        const result = this.db.run(
          `INSERT INTO plans (id, name, daily_requests, monthly_requests, max_parallel,
                              max_chars_per_request, priority, allows_byok, unlimited_product, notes)
           VALUES (?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT (id) DO UPDATE SET
             name = excluded.name,
             daily_requests = excluded.daily_requests,
             monthly_requests = excluded.monthly_requests,
             max_parallel = excluded.max_parallel,
             max_chars_per_request = excluded.max_chars_per_request,
             priority = excluded.priority,
             allows_byok = excluded.allows_byok,
             unlimited_product = excluded.unlimited_product,
             notes = excluded.notes,
             updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')`,
          [
            plan.id,
            plan.name,
            plan.dailyRequests,
            plan.monthlyRequests,
            plan.maxParallel,
            plan.maxCharsPerRequest,
            plan.priority,
            plan.allowsByok ? 1 : 0,
            plan.unlimitedProduct ? 1 : 0,
            plan.notes ?? null,
          ],
        );
        if (result.changes > 0) {
          count += 1;
        }
      }
    });
    return count;
  }

  createUser(input: {
    id: string;
    email?: string;
    displayName?: string;
    planId?: string;
    kind?: UserKind;
  }): User {
    const planId = input.planId ?? input.kind ?? 'FREE';
    this.db.run(
      `INSERT INTO users (id, email, display_name, plan_id, kind, status) VALUES (?,?,?,?,?,'active')`,
      [input.id, input.email ?? null, input.displayName ?? null, planId, input.kind ?? planId],
    );
    return this.requireUser(input.id);
  }

  requireUser(id: string): User {
    const row = this.db.get<Record<string, unknown>>('SELECT * FROM users WHERE id = ?', [id]);
    if (!row) {
      throw new Error(`user ${id} not found`);
    }
    return userRow(row);
  }

  getUser(id: string): User | undefined {
    const row = this.db.get<Record<string, unknown>>('SELECT * FROM users WHERE id = ?', [id]);
    return row ? userRow(row) : undefined;
  }

  listUsers(limit = 200): User[] {
    return this.db
      .all<Record<string, unknown>>('SELECT * FROM users ORDER BY created_at DESC LIMIT ?', [limit])
      .map(userRow);
  }

  /** Admin action: disable or re-enable a user with a recorded reason. */
  setUserStatus(id: string, status: 'active' | 'disabled', reason?: string): User {
    this.db.run(
      `UPDATE users SET status = ?,
              disabled_at = CASE WHEN ? = 'disabled' THEN strftime('%Y-%m-%dT%H:%M:%fZ','now') ELSE NULL END,
              disabled_reason = ?,
              updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
        WHERE id = ?`,
      [status, status, reason ?? null, id],
    );
    return this.requireUser(id);
  }

  setUserLimits(id: string, limits: { daily?: number | null; monthly?: number | null; maxParallel?: number }): User {
    const current = this.requireUser(id);
    this.db.run(
      `UPDATE users SET daily_override = ?, monthly_override = ?, max_parallel_override = ?,
              updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = ?`,
      [
        limits.daily === undefined ? current.dailyOverride ?? null : limits.daily,
        limits.monthly === undefined ? current.monthlyOverride ?? null : limits.monthly,
        limits.maxParallel === undefined ? current.maxParallelOverride ?? null : limits.maxParallel,
        id,
      ],
    );
    return this.requireUser(id);
  }

  plan(id: string): PlanDefinition | undefined {
    const row = this.db.get<Record<string, unknown>>('SELECT * FROM plans WHERE id = ?', [id]);
    return row ? planRow(row) : undefined;
  }

  listPlans(): PlanDefinition[] {
    return this.db.all<Record<string, unknown>>('SELECT * FROM plans ORDER BY priority ASC').map(planRow);
  }

  /** Creates a key. The plaintext is returned once and never persisted. */
  createApiKey(input: {
    userId: string;
    name: string;
    scopes?: string[];
    dailyLimit?: number;
    expiresAt?: string;
  }): CreatedApiKey {
    const { plaintext, prefix } = generateApiKey();
    const hash = hashApiKey(plaintext);
    const result = this.db.run(
      `INSERT INTO api_keys (user_id, name, prefix, key_hash, scopes, daily_limit, expires_at)
       VALUES (?,?,?,?,?,?,?)`,
      [
        input.userId,
        input.name,
        prefix,
        hash,
        JSON.stringify(input.scopes ?? ['translate']),
        input.dailyLimit ?? null,
        input.expiresAt ?? null,
      ],
    );
    const id = Number(this.db.get<{ id: number }>('SELECT last_insert_rowid() AS id')?.id ?? result.changes);
    return { record: this.requireKey(id), plaintext };
  }

  /** Resolves a presented key. Returns undefined for unknown or unusable keys. */
  authenticate(presented: string, now = new Date()): { user: User; key: ApiKeyRecord } | undefined {
    if (!presented || presented.length < 12) {
      return undefined;
    }
    const row = this.db.get<Record<string, unknown>>(
      'SELECT * FROM api_keys WHERE key_hash = ?',
      [hashApiKey(presented)],
    );
    if (!row) {
      return undefined;
    }
    const record = keyRow(row);
    if (record.status !== 'active') {
      return undefined;
    }
    if (record.expiresAt && new Date(record.expiresAt).getTime() <= now.getTime()) {
      this.db.run(
        `UPDATE api_keys SET status = 'expired' WHERE id = ? AND status = 'active'`,
        [record.id],
      );
      return undefined;
    }
    const user = this.getUser(record.userId);
    if (!user || user.status !== 'active') {
      return undefined;
    }
    this.db.run(
      `UPDATE api_keys SET last_used_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'), usage_count = usage_count + 1
        WHERE id = ?`,
      [record.id],
    );
    return { user, key: { ...record, usageCount: record.usageCount + 1 } };
  }

  listApiKeys(userId: string): ApiKeyRecord[] {
    return this.db
      .all<Record<string, unknown>>(
        'SELECT * FROM api_keys WHERE user_id = ? ORDER BY created_at DESC',
        [userId],
      )
      .map(keyRow);
  }

  setKeyStatus(id: number, status: KeyStatus): ApiKeyRecord {
    this.db.run(
      `UPDATE api_keys SET status = ?, revoked_at = CASE WHEN ? = 'revoked' THEN strftime('%Y-%m-%dT%H:%M:%fZ','now') ELSE revoked_at END
        WHERE id = ?`,
      [status, status, id],
    );
    return this.requireKey(id);
  }

  /**
   * Rotation: the old key is revoked in the same transaction as the new key's
   * creation, so a key is never briefly duplicated.
   */
  rotateApiKey(id: number): CreatedApiKey {
    return this.db.transaction(() => {
      const previous = this.requireKey(id);
      if (previous.status === 'revoked') {
        throw new Error('cannot rotate a revoked key');
      }
      const created = this.createApiKey({
        userId: previous.userId,
        name: previous.name,
        scopes: previous.scopes,
        ...(previous.dailyLimit === undefined ? {} : { dailyLimit: previous.dailyLimit }),
        ...(previous.expiresAt === undefined ? {} : { expiresAt: previous.expiresAt }),
      });
      this.db.run(
        `UPDATE api_keys SET status = 'revoked', revoked_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
          WHERE id = ?`,
        [id],
      );
      this.db.run('UPDATE api_keys SET rotated_from = ? WHERE id = ?', [id, created.record.id]);
      return created;
    });
  }

  getKey(id: number): ApiKeyRecord | undefined {
    const row = this.db.get<Record<string, unknown>>('SELECT * FROM api_keys WHERE id = ?', [id]);
    return row ? keyRow(row) : undefined;
  }

  private requireKey(id: number): ApiKeyRecord {
    const record = this.getKey(id);
    if (!record) {
      throw new Error(`api key ${id} not found`);
    }
    return record;
  }

  // --- usage -----------------------------------------------------------

  /** Current UTC day, the bucket key for quota accounting. */
  static dayOf(now = new Date()): string {
    return now.toISOString().slice(0, 10);
  }

  getUsage(userId: string, day = AuthRepository.dayOf()): { requests: number; characters: number; errors: number; cacheHits: number } {
    const row = this.db.get<Record<string, unknown>>(
      'SELECT requests, characters, errors, cache_hits FROM usage WHERE user_id = ? AND day = ?',
      [userId, day],
    );
    return {
      requests: Number(row?.requests ?? 0),
      characters: Number(row?.characters ?? 0),
      errors: Number(row?.errors ?? 0),
      cacheHits: Number(row?.cache_hits ?? 0),
    };
  }

  recordUsage(input: {
    userId: string;
    requests?: number;
    characters?: number;
    errors?: number;
    cacheHits?: number;
    day?: string;
  }): void {
    const day = input.day ?? AuthRepository.dayOf();
    this.db.run(
      `INSERT INTO usage (user_id, day, requests, characters, errors, cache_hits)
       VALUES (?,?,?,?,?,?)
       ON CONFLICT (user_id, day) DO UPDATE SET
         requests = requests + excluded.requests,
         characters = characters + excluded.characters,
         errors = errors + excluded.errors,
         cache_hits = cache_hits + excluded.cache_hits,
         updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')`,
      [
        input.userId,
        day,
        input.requests ?? 1,
        input.characters ?? 0,
        input.errors ?? 0,
        input.cacheHits ?? 0,
      ],
    );
  }

  /**
   * Quota decision. Unlimited plans still get their parallel/payload caps.
   *
   * The user is re-read from storage rather than trusting the caller's object: a
   * stale snapshot must never be the reason someone keeps unlimited access.
   */
  checkEntitlement(userOrId: User | string, options: { chars?: number; now?: Date } = {}): EntitlementDecision {
    const user = typeof userOrId === 'string' ? this.requireUser(userOrId) : (this.getUser(userOrId.id) ?? userOrId);
    const plan = this.plan(user.planId);
    if (!plan) {
      return {
        allowed: false,
        reason: `unknown plan "${user.planId}"`,
        maxParallel: 1,
        maxCharsPerRequest: 1000,
        priority: 9,
      };
    }
    if (user.status !== 'active') {
      return {
        allowed: false,
        reason: user.status === 'disabled' ? 'account disabled' : `account status "${user.status}"`,
        maxParallel: plan.maxParallel,
        maxCharsPerRequest: plan.maxCharsPerRequest,
        priority: plan.priority,
      };
    }

    const day = AuthRepository.dayOf(options.now);
    const usage = this.getUsage(user.id, day);

    // BYOK spends the user's own provider quota, not ours.
    if (plan.unlimitedProduct) {
      return {
        allowed: true,
        maxParallel: user.maxParallelOverride ?? plan.maxParallel,
        maxCharsPerRequest: plan.maxCharsPerRequest,
        priority: plan.priority,
      };
    }

    const dailyLimit = user.dailyOverride ?? plan.dailyRequests;
    if (dailyLimit !== null && dailyLimit !== undefined && usage.requests >= dailyLimit) {
      return {
        allowed: false,
        reason: `daily quota exhausted (${usage.requests}/${dailyLimit})`,
        limit: dailyLimit,
        used: usage.requests,
        remaining: 0,
        maxParallel: user.maxParallelOverride ?? plan.maxParallel,
        maxCharsPerRequest: plan.maxCharsPerRequest,
        priority: plan.priority,
      };
    }

    const chars = options.chars ?? 0;
    if (chars > plan.maxCharsPerRequest) {
      return {
        allowed: false,
        reason: `request of ${chars} characters exceeds the ${plan.maxCharsPerRequest} character limit`,
        maxParallel: user.maxParallelOverride ?? plan.maxParallel,
        maxCharsPerRequest: plan.maxCharsPerRequest,
        priority: plan.priority,
      };
    }

    return {
      allowed: true,
      ...(dailyLimit === null || dailyLimit === undefined ? {} : { limit: dailyLimit, used: usage.requests, remaining: dailyLimit - usage.requests }),
      maxParallel: user.maxParallelOverride ?? plan.maxParallel,
      maxCharsPerRequest: plan.maxCharsPerRequest,
      priority: plan.priority,
    };
  }

  audit(entry: {
    actor: string;
    action: string;
    subjectType?: string;
    subjectId?: string;
    detail?: Record<string, unknown>;
    ip?: string;
  }): void {
    this.db.run(
      `INSERT INTO audit_logs (actor, action, subject_type, subject_id, detail, ip) VALUES (?,?,?,?,?,?)`,
      [
        entry.actor,
        entry.action,
        entry.subjectType ?? null,
        entry.subjectId ?? null,
        entry.detail ? JSON.stringify(entry.detail) : null,
        entry.ip ?? null,
      ],
    );
  }

  auditTrail(filter: { subjectType?: string; subjectId?: string; limit?: number } = {}): Array<Record<string, unknown>> {
    const conditions: string[] = ['1'];
    const params: unknown[] = [];
    if (filter.subjectType) {
      conditions.push('subject_type = ?');
      params.push(filter.subjectType);
    }
    if (filter.subjectId) {
      conditions.push('subject_id = ?');
      params.push(filter.subjectId);
    }
    params.push(Math.min(filter.limit ?? 100, 1000));
    return this.db.all<Record<string, unknown>>(
      `SELECT * FROM audit_logs WHERE ${conditions.join(' AND ')} ORDER BY created_at DESC LIMIT ?`,
      params,
    );
  }
}

function userRow(row: Record<string, unknown>): User {
  return {
    id: String(row.id),
    ...(row.email ? { email: String(row.email) } : {}),
    ...(row.display_name ? { displayName: String(row.display_name) } : {}),
    planId: String(row.plan_id),
    kind: String(row.kind ?? 'FREE') as UserKind,
    status: String(row.status ?? 'active'),
    ...(row.daily_override === null || row.daily_override === undefined
      ? {}
      : { dailyOverride: Number(row.daily_override) }),
    ...(row.monthly_override === null || row.monthly_override === undefined
      ? {}
      : { monthlyOverride: Number(row.monthly_override) }),
    ...(row.max_parallel_override === null || row.max_parallel_override === undefined
      ? {}
      : { maxParallelOverride: Number(row.max_parallel_override) }),
    createdAt: String(row.created_at),
  };
}

function keyRow(row: Record<string, unknown>): ApiKeyRecord {
  let scopes: string[] = [];
  try {
    const parsed = row.scopes ? (JSON.parse(String(row.scopes)) as unknown) : [];
    if (Array.isArray(parsed)) {
      scopes = parsed.map(String);
    }
  } catch {
    scopes = [];
  }
  return {
    id: Number(row.id),
    userId: String(row.user_id),
    name: String(row.name),
    prefix: String(row.prefix),
    scopes,
    status: String(row.status ?? 'active') as KeyStatus,
    ...(row.daily_limit === null || row.daily_limit === undefined ? {} : { dailyLimit: Number(row.daily_limit) }),
    usageCount: Number(row.usage_count ?? 0),
    ...(row.last_used_at ? { lastUsedAt: String(row.last_used_at) } : {}),
    ...(row.expires_at ? { expiresAt: String(row.expires_at) } : {}),
    createdAt: String(row.created_at),
  };
}

function planRow(row: Record<string, unknown>): PlanDefinition {
  return {
    id: String(row.id),
    name: String(row.name),
    dailyRequests: row.daily_requests === null ? null : Number(row.daily_requests),
    monthlyRequests: row.monthly_requests === null ? null : Number(row.monthly_requests),
    maxParallel: Number(row.max_parallel ?? 2),
    maxCharsPerRequest: Number(row.max_chars_per_request ?? 5000),
    priority: Number(row.priority ?? 5),
    allowsByok: Number(row.allows_byok ?? 0) === 1,
    unlimitedProduct: Number(row.unlimited_product ?? 0) === 1,
    ...(row.notes ? { notes: String(row.notes) } : {}),
  };
}