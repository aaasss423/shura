/**
 * Engine registry.
 *
 * Engines register by id. Everything above this file addresses engines by id,
 * which is what makes the current engine a plug-in rather than a foundation.
 */

import { ConfigError } from '../core/errors';
import type { TranslationEngine } from './engine';
import { MyMemoryEngine } from './mymemory/engine';
import { DeepLEngine } from './deepl/engine';
import { EchoEngine } from './echo/engine';

export interface EngineFactory {
  (): TranslationEngine;
}

export class EngineRegistry {
  private readonly factories = new Map<string, EngineFactory>();
  private readonly instances = new Map<string, TranslationEngine>();

  register(id: string, factory: EngineFactory): this {
    this.factories.set(id, factory);
    return this;
  }

  has(id: string): boolean {
    return this.factories.has(id);
  }

  /**
   * Drops the cached instance for an engine so the next create() rebuilds it.
   * Needed when a credential changes at runtime: the engine captured the old
   * key at construction time.
   */
  invalidate(id: string): void {
    this.instances.delete(id);
  }

  ids(): string[] {
    return [...this.factories.keys()];
  }

  create(id: string): TranslationEngine {
    const existing = this.instances.get(id);
    if (existing) {
      return existing;
    }
    const factory = this.factories.get(id);
    if (!factory) {
      throw new ConfigError(
        `unknown engine "${id}"; registered engines: ${this.ids().join(', ') || 'none'}`,
      );
    }
    const engine = factory();
    this.instances.set(id, engine);
    return engine;
  }
}

/**
 * Per-engine options.
 *
 * A function is resolved on every engine construction, which matters when the
 * options depend on a value that can change at runtime — for example a
 * credential loaded from the secret store after the Translator was constructed.
 */
export type EngineOptions<T> = T | (() => T);

export interface BuiltInEngineOptions {
  mymemory?: EngineOptions<ConstructorParameters<typeof MyMemoryEngine>[0]>;
  deepl?: EngineOptions<ConstructorParameters<typeof DeepLEngine>[0]>;
  echo?: EngineOptions<ConstructorParameters<typeof EchoEngine>[0]>;
}

function resolveOptions<T>(options: EngineOptions<T> | undefined): T {
  return typeof options === 'function' ? (options as () => T)() : (options ?? ({} as T));
}

/**
 * Registers the built-in engines.
 *
 * DeepL is registered even without an API key so the platform can report it as
 * "configured: false" and route around it, rather than pretending it does not
 * exist. The echo engine stays opt-in.
 */
export function createDefaultRegistry(options: BuiltInEngineOptions = {}): EngineRegistry {
  const registry = new EngineRegistry();
  registry.register('mymemory', () => new MyMemoryEngine(resolveOptions(options.mymemory)));
  registry.register('deepl', () => new DeepLEngine(resolveOptions(options.deepl)));
  registry.register('echo', () => new EchoEngine(resolveOptions(options.echo)));
  return registry;
}

/** Engines that are available for production use without extra opt-in. */
export const PRODUCTION_ENGINE_IDS = ['mymemory', 'deepl'] as const;

export { DeepLEngine, EchoEngine, MyMemoryEngine };