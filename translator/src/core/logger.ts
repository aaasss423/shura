/**
 * Minimal structured logger. Intentionally dependency-free so the platform can
 * be embedded in a host application without dragging a logging stack in.
 */

export type LogLevel = 'silent' | 'error' | 'warn' | 'info' | 'debug';

const LEVEL_ORDER: Record<LogLevel, number> = {
  silent: 0,
  error: 1,
  warn: 2,
  info: 3,
  debug: 4,
};

export interface Logger {
  error(message: string, context?: Record<string, unknown>): void;
  warn(message: string, context?: Record<string, unknown>): void;
  info(message: string, context?: Record<string, unknown>): void;
  debug(message: string, context?: Record<string, unknown>): void;
  child(bindings: Record<string, unknown>): Logger;
}

export interface LoggerOptions {
  level?: LogLevel;
  sink?: (line: string) => void;
  bindings?: Record<string, unknown>;
}

export function createLogger(options: LoggerOptions = {}): Logger {
  const level = options.level ?? 'info';
  const sink = options.sink ?? ((line: string) => process.stdout.write(`${line}\n`));
  const bindings = options.bindings ?? {};
  const threshold = LEVEL_ORDER[level];

  function emit(entry: 'error' | 'warn' | 'info' | 'debug', message: string, context?: Record<string, unknown>): void {
    if (LEVEL_ORDER[entry] > threshold) {
      return;
    }
    const payload: Record<string, unknown> = {
      level: entry,
      msg: message,
      ...bindings,
      ...(context ?? {}),
    };
    try {
      sink(JSON.stringify(payload));
    } catch {
      sink(JSON.stringify({ level: entry, msg: message, note: 'context not serializable' }));
    }
  }

  return {
    error: (m, c) => emit('error', m, c),
    warn: (m, c) => emit('warn', m, c),
    info: (m, c) => emit('info', m, c),
    debug: (m, c) => emit('debug', m, c),
    child: (extra) => createLogger({ level, sink, bindings: { ...bindings, ...extra } }),
  };
}

export const silentLogger: Logger = createLogger({ level: 'silent', sink: () => undefined });