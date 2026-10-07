/**
 * Feature flags (ADR 0010).
 *
 * One shape, resolved once, and each flag guards exactly one capability. Flags
 * only ever *remove* capability: a disabled flag returns an explicit
 * "disabled" error rather than silently doing nothing, so a missing feature is
 * never mistaken for a passing one.
 */

export const FLAG_NAMES = [
  'ENABLE_LOCAL_ENGINE',
  'ENABLE_DEEPL',
  'ENABLE_MYMEMORY',
  'ENABLE_BYOK',
  'ENABLE_RESEARCH_AGENT',
  'ENABLE_PREWARM',
  'ENABLE_ASYNC_TRANSLATION',
  'ENABLE_MODEL_ROUTING',
  'ENABLE_ADVANCED_QUALITY',
  'ENABLE_TRAINING_PIPELINE',
  'ENABLE_AUTH',
  'ENABLE_ENTITLEMENTS',
  'ENABLE_KNOWLEDGE',
  'ENABLE_TRANSLATION_MEMORY',
  'ENABLE_GLOSSARY',
] as const;

export type FlagName = (typeof FLAG_NAMES)[number];

/** Deliberately conservative: providers are off unless asked for. */
export const FLAG_DEFAULTS: Record<FlagName, boolean> = {
  ENABLE_LOCAL_ENGINE: true,
  ENABLE_DEEPL: false,
  ENABLE_MYMEMORY: false,
  ENABLE_BYOK: false,
  ENABLE_RESEARCH_AGENT: false,
  ENABLE_PREWARM: false,
  ENABLE_ASYNC_TRANSLATION: false,
  ENABLE_MODEL_ROUTING: false,
  ENABLE_ADVANCED_QUALITY: false,
  ENABLE_TRAINING_PIPELINE: false,
  ENABLE_AUTH: true,
  ENABLE_ENTITLEMENTS: true,
  ENABLE_KNOWLEDGE: true,
  ENABLE_TRANSLATION_MEMORY: true,
  ENABLE_GLOSSARY: true,
};

/** Capabilities that must work with every optional flag off. */
export const CORE_FLAGS: FlagName[] = ['ENABLE_LOCAL_ENGINE'];

export class FeatureDisabledError extends Error {
  readonly code = 'FEATURE_DISABLED';
  readonly flag: FlagName;
  readonly status = 501;

  constructor(flag: FlagName) {
    super(`feature is disabled: ${flag}`);
    this.name = 'FeatureDisabledError';
    this.flag = flag;
  }
}

export class FeatureFlags {
  private readonly values: Record<FlagName, boolean>;

  constructor(values: Partial<Record<FlagName, boolean>> = {}) {
    this.values = { ...FLAG_DEFAULTS };
    for (const [key, value] of Object.entries(values)) {
      this.values[key as FlagName] = Boolean(value);
    }
  }

  static fromEnv(env: Record<string, string | undefined> = process.env): FeatureFlags {
    const overrides: Partial<Record<FlagName, boolean>> = {};
    for (const name of FLAG_NAMES) {
      const raw = env[name];
      if (raw === undefined || raw.trim() === '') {
        continue;
      }
      const normalized = raw.trim().toLowerCase();
      if (['1', 'true', 'yes', 'on'].includes(normalized)) {
        overrides[name] = true;
      } else if (['0', 'false', 'no', 'off'].includes(normalized)) {
        overrides[name] = false;
      } else {
        throw new Error(`${name} must be a boolean, received "${raw}"`);
      }
    }
    return new FeatureFlags(overrides);
  }

  isEnabled(flag: FlagName): boolean {
    return this.values[flag] === true;
  }

  /** Throws an explicit error when a capability is off. */
  require(flag: FlagName): void {
    if (!this.isEnabled(flag)) {
      throw new FeatureDisabledError(flag);
    }
  }

  /** Throws only when the flag is on — for capabilities that must be *used*. */
  requireIfEnabled(flag: FlagName): void {
    if (this.isEnabled(flag)) {
      throw new FeatureDisabledError(flag);
    }
  }

  all(): Record<FlagName, boolean> {
    return { ...this.values };
  }

  /** The set of enabled optional flags, for diagnostics. */
  enabledFlags(): FlagName[] {
    return FLAG_NAMES.filter((name) => this.values[name]);
  }

  withCoreOnly(): FeatureFlags {
    return new FeatureFlags(
      Object.fromEntries(FLAG_NAMES.map((name) => [name, CORE_FLAGS.includes(name)])) as Partial<
        Record<FlagName, boolean>
      >,
    );
  }
}