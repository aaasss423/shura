/**
 * Runtime secret storage for engine API keys.
 *
 * Design goals, in priority order:
 *  1. The key never leaves the server process. No endpoint returns it, ever.
 *  2. The key is not stored in the project directory or in Git.
 *  3. The key is not stored in plaintext on disk.
 *  4. The UI can show "Configured / Not configured" without ever seeing the key.
 *
 * Storage location: the user config directory
 * (`$XDG_CONFIG_HOME/translation-platform/secrets.enc`, or
 * `~/.config/translation-platform/secrets.enc`). That is outside any repository
 * and survives only on this machine.
 *
 * Protection at rest: AES-256-GCM with a random 32-byte data key. The data key
 * is wrapped with AES-256-GCM under a key derived by scrypt from a machine-local
 * secret file with 0600 permissions. This is defence in depth: an attacker who
 * can read both files on the same machine and run as the same user can still
 * recover the key. It is honest protection against casual exposure (backup
 * dumps, logs, screenshots, repository copies), not against a local root
 * attacker. On a host with a hardware-backed store (Android Keystore, macOS
 * Keychain, Windows Credential Manager) an adapter should be plugged in here
 * instead — that is what the `SecretStore` interface is for.
 *
 * Environment variables always win: if `DEEPL_API_KEY` is set, it is used and
 * the vault is not consulted.
 */

import * as crypto from 'node:crypto';
import * as fs from 'node:fs/promises';
import * as os from 'node:os';
import * as path from 'node:path';

export interface StoredSecret {
  /** Engine the secret belongs to, e.g. 'deepl'. */
  engine: string;
  /** The secret value. Never logged, never returned by any endpoint. */
  value: string;
}

export interface SecretStatus {
  engine: string;
  configured: boolean;
  /** Where the value came from. */
  source: 'environment' | 'vault' | 'none';
  /** Non-reversible fingerprint, safe to display for verification. */
  fingerprint?: string;
  updatedAt?: string;
}

export interface SecretStore {
  get(engine: string): Promise<string | undefined>;
  set(engine: string, value: string): Promise<void>;
  delete(engine: string): Promise<boolean>;
  status(engine: string): Promise<SecretStatus>;
  path(): string;
}

interface VaultFile {
  version: 1;
  entries: Record<string, { ciphertext: string; iv: string; tag: string; updatedAt: string }>;
}

interface KeyWrapFile {
  version: 1;
  salt: string;
  wrappedKey: string;
  wrapIv: string;
  wrapTag: string;
}

/** Scrypt parameters: interactive-grade, ~50ms on a phone. */
const SCRYPT = { N: 16384, r: 8, p: 1, keylen: 32 } as const;

function deriveWrappingKey(keyFileSalt: Buffer): Buffer {
  return crypto.scryptSync(
    // Machine-local material. Not a password: it protects against file copies
    // and backups, not against someone who already has this account.
    `${os.hostname()}|${os.userInfo().username}|${keyFileSalt.toString('hex')}`,
    keyFileSalt,
    SCRYPT.keylen,
    { N: SCRYPT.N, r: SCRYPT.r, p: SCRYPT.p },
  );
}

function fingerprint(value: string): string {
  return crypto.createHash('sha256').update(value).digest('hex').slice(0, 12);
}

export function defaultVaultDirectory(): string {
  const base = process.env.XDG_CONFIG_HOME ?? path.join(os.homedir(), '.config');
  return path.join(base, 'translation-platform');
}

export class EncryptedFileSecretStore implements SecretStore {
  private readonly directory: string;
  private readonly vaultPath: string;
  private readonly keyPath: string;

  constructor(directory: string = defaultVaultDirectory()) {
    this.directory = directory;
    this.vaultPath = path.join(directory, 'secrets.enc');
    this.keyPath = path.join(directory, 'secrets.key');
  }

  path(): string {
    return this.vaultPath;
  }

  private async readKeyWrap(): Promise<KeyWrapFile> {
    let raw: string;
    try {
      raw = await fs.readFile(this.keyPath, 'utf8');
    } catch {
      return this.createKeyWrap();
    }
    try {
      const parsed = JSON.parse(raw) as KeyWrapFile;
      if (parsed && parsed.version === 1 && typeof parsed.salt === 'string') {
        return parsed;
      }
    } catch {
      // Corrupt key file: treat as absent and re-wrap with a fresh data key.
    }
    return this.createKeyWrap();
  }

  private async createKeyWrap(): Promise<KeyWrapFile> {
    const salt = crypto.randomBytes(16);
    const dataKey = crypto.randomBytes(32);
    const wrappingKey = deriveWrappingKey(salt);
    const wrapIv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', wrappingKey, wrapIv);
    const wrapped = Buffer.concat([cipher.update(dataKey), cipher.final()]);
    const wrapTag = cipher.getAuthTag();

    const file: KeyWrapFile = {
      version: 1,
      salt: salt.toString('base64'),
      wrappedKey: wrapped.toString('base64'),
      wrapIv: wrapIv.toString('base64'),
      wrapTag: wrapTag.toString('base64'),
    };
    await this.writePrivateJson(this.keyPath, file);
    return file;
  }

  private unwrapDataKey(wrap: KeyWrapFile): Buffer {
    const salt = Buffer.from(wrap.salt, 'base64');
    const wrappingKey = deriveWrappingKey(salt);
    const decipher = crypto.createDecipheriv('aes-256-gcm', wrappingKey, Buffer.from(wrap.wrapIv, 'base64'));
    decipher.setAuthTag(Buffer.from(wrap.wrapTag, 'base64'));
    return Buffer.concat([decipher.update(Buffer.from(wrap.wrappedKey, 'base64')), decipher.final()]);
  }

  private encryptValue(dataKey: Buffer, value: string): { ciphertext: string; iv: string; tag: string } {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', dataKey, iv);
    const ciphertext = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
    return {
      ciphertext: ciphertext.toString('base64'),
      iv: iv.toString('base64'),
      tag: cipher.getAuthTag().toString('base64'),
    };
  }

  private decryptValue(dataKey: Buffer, entry: { ciphertext: string; iv: string; tag: string }): string {
    const decipher = crypto.createDecipheriv('aes-256-gcm', dataKey, Buffer.from(entry.iv, 'base64'));
    decipher.setAuthTag(Buffer.from(entry.tag, 'base64'));
    return Buffer.concat([
      decipher.update(Buffer.from(entry.ciphertext, 'base64')),
      decipher.final(),
    ]).toString('utf8');
  }

  private async readVault(): Promise<VaultFile> {
    try {
      const raw = await fs.readFile(this.vaultPath, 'utf8');
      const parsed = JSON.parse(raw) as VaultFile;
      if (parsed && parsed.version === 1 && parsed.entries) {
        return parsed;
      }
    } catch {
      // Missing or unreadable vault: start empty rather than crashing.
    }
    return { version: 1, entries: {} };
  }

  /** Writes with 0600 permissions and an atomic rename. */
  private async writePrivateJson(file: string, payload: unknown): Promise<void> {
    await fs.mkdir(this.directory, { recursive: true, mode: 0o700 });
    const tmp = `${file}.${process.pid}.tmp`;
    await fs.writeFile(tmp, JSON.stringify(payload), { encoding: 'utf8', mode: 0o600 });
    await fs.rename(tmp, file);
    await fs.chmod(file, 0o600);
  }

  async get(engine: string): Promise<string | undefined> {
    const wrap = await this.readKeyWrap();
    const vault = await this.readVault();
    const entry = vault.entries[engine];
    if (!entry) {
      return undefined;
    }
    try {
      return this.decryptValue(this.unwrapDataKey(wrap), entry);
    } catch {
      // Authentication failure: the key file no longer matches the vault.
      return undefined;
    }
  }

  async set(engine: string, value: string): Promise<void> {
    const wrap = await this.readKeyWrap();
    const dataKey = this.unwrapDataKey(wrap);
    const vault = await this.readVault();
    const encrypted = this.encryptValue(dataKey, value);
    vault.entries[engine] = { ...encrypted, updatedAt: new Date().toISOString() };
    await this.writePrivateJson(this.vaultPath, vault);
  }

  async delete(engine: string): Promise<boolean> {
    const vault = await this.readVault();
    if (!vault.entries[engine]) {
      return false;
    }
    delete vault.entries[engine];
    await this.writePrivateJson(this.vaultPath, vault);
    return true;
  }

  async status(engine: string): Promise<SecretStatus> {
    const wrap = await this.readKeyWrap();
    const vault = await this.readVault();
    const entry = vault.entries[engine];
    if (!entry) {
      return { engine, configured: false, source: 'none' };
    }
    let value: string | undefined;
    try {
      value = this.decryptValue(this.unwrapDataKey(wrap), entry);
    } catch {
      value = undefined;
    }
    if (value === undefined) {
      return {
        engine,
        configured: false,
        source: 'vault',
        updatedAt: entry.updatedAt,
      };
    }
    return {
      engine,
      configured: true,
      source: 'vault',
      fingerprint: fingerprint(value),
      updatedAt: entry.updatedAt,
    };
  }
}

/**
 * Environment-first secret resolution.
 *
 * The environment variable always wins, so CI and container deployments keep
 * working exactly as before, while the vault serves desktop/UI usage.
 */
export class EnvironmentFirstSecretStore implements SecretStore {
  private readonly vault: EncryptedFileSecretStore;
  private readonly envMap: Record<string, string | undefined>;

  constructor(options: { vault?: EncryptedFileSecretStore; env?: Record<string, string | undefined> } = {}) {
    this.vault = options.vault ?? new EncryptedFileSecretStore();
    this.envMap = options.env ?? process.env;
  }

  /** Env var name for an engine, e.g. deepl -> DEEPL_API_KEY. */
  static envVarFor(engine: string): string {
    return `${engine.toUpperCase().replace(/[^A-Z0-9]/g, '_')}_API_KEY`;
  }

  path(): string {
    return this.vault.path();
  }

  async get(engine: string): Promise<string | undefined> {
    const fromEnv = this.envMap[EnvironmentFirstSecretStore.envVarFor(engine)];
    if (fromEnv && fromEnv.trim().length > 0) {
      return fromEnv.trim();
    }
    return this.vault.get(engine);
  }

  async set(engine: string, value: string): Promise<void> {
    await this.vault.set(engine, value);
  }

  async delete(engine: string): Promise<boolean> {
    return this.vault.delete(engine);
  }

  async status(engine: string): Promise<SecretStatus> {
    const fromEnv = this.envMap[EnvironmentFirstSecretStore.envVarFor(engine)];
    if (fromEnv && fromEnv.trim().length > 0) {
      return {
        engine,
        configured: true,
        source: 'environment',
        fingerprint: fingerprint(fromEnv.trim()),
      };
    }
    return this.vault.status(engine);
  }
}