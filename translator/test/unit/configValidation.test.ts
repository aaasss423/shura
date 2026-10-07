import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { loadConfig, describeConfig } from '../../src/config/index';
import { ConfigError } from '../../src/core/errors';
import {
  validateTranslateInput,
  validateDetectInput,
  validateChapterInput,
  assertReasonableSize,
} from '../../src/core/validation';
import { normalizeLanguageCode, isRtlLanguage, getLanguageInfo } from '../../src/language/registry';

describe('configuration', () => {
  it('defaults to the mymemory engine', () => {
    const config = loadConfig({ env: {}, readDotEnv: false, rootDir: '/tmp' });
    assert.equal(config.engine.engine, 'mymemory');
  });

  it('reads numeric values with validation', () => {
    const config = loadConfig({
      env: { SERVER_PORT: '9999', REQUEST_TIMEOUT_MS: '2500', RETRY_MAX_ATTEMPTS: '5' },
      readDotEnv: false,
      rootDir: '/tmp',
    });
    assert.equal(config.server.port, 9999);
    assert.equal(config.timeouts.requestTimeoutMs, 2500);
    assert.equal(config.retry.maxAttempts, 5);
  });

  it('rejects a non-numeric port', () => {
    assert.throws(
      () => loadConfig({ env: { SERVER_PORT: 'abc' }, readDotEnv: false, rootDir: '/tmp' }),
      ConfigError,
    );
  });

  it('rejects out-of-range values', () => {
    assert.throws(
      () => loadConfig({ env: { MYMEMORY_MAX_QUERY_CHARS: '9999' }, readDotEnv: false, rootDir: '/tmp' }),
      ConfigError,
    );
  });

  it('reads booleans', () => {
    const on = loadConfig({ env: { CACHE_ENABLED: 'true' }, readDotEnv: false, rootDir: '/tmp' });
    assert.equal(on.cache.enabled, true);
    const off = loadConfig({ env: { CACHE_ENABLED: '0' }, readDotEnv: false, rootDir: '/tmp' });
    assert.equal(off.cache.enabled, false);
  });

  it('rejects an invalid boolean', () => {
    assert.throws(
      () => loadConfig({ env: { CACHE_ENABLED: 'maybe' }, readDotEnv: false, rootDir: '/tmp' }),
      ConfigError,
    );
  });

  it('validates the log level', () => {
    assert.throws(
      () => loadConfig({ env: { LOG_LEVEL: 'verbose' }, readDotEnv: false, rootDir: '/tmp' }),
      ConfigError,
    );
  });

  it('validates the chapter failure policy', () => {
    assert.throws(
      () => loadConfig({ env: { CHAPTER_FAILURE_POLICY: 'ignore' }, readDotEnv: false, rootDir: '/tmp' }),
      ConfigError,
    );
  });

  it('clamps the MyMemory query limit to the API maximum', () => {
    const config = loadConfig({ env: { MYMEMORY_MAX_QUERY_CHARS: '500' }, readDotEnv: false, rootDir: '/tmp' });
    assert.equal(config.engine.mymemory.maxQueryChars, 500);
  });

  it('keeps credentials out of the described config', () => {
    const config = loadConfig({
      env: { MYMEMORY_CONTACT_EMAIL: 'secret@example.com' },
      readDotEnv: false,
      rootDir: '/tmp',
    });
    assert.equal(config.engine.mymemory.contactEmail, 'secret@example.com');
    const described = JSON.stringify(describeConfig(config));
    assert.ok(!described.includes('secret@example.com'), 'describeConfig must not leak the contact email');
    assert.ok(described.includes('"authenticated":true'));
  });

  it('resolves the cache directory relative to the root', () => {
    const config = loadConfig({ env: { CACHE_DIR: 'var/cache' }, readDotEnv: false, rootDir: '/srv/app' });
    assert.equal(config.cache.directory, '/srv/app/var/cache');
  });

  it('supports switching engines purely via configuration', () => {
    const config = loadConfig({
      env: { TRANSLATION_ENGINE: 'echo', TRANSLATION_ALLOW_ECHO_ENGINE: 'true' },
      readDotEnv: false,
      rootDir: '/tmp',
    });
    assert.equal(config.engine.engine, 'echo');
    assert.equal(config.engine.allowEchoEngine, true);
  });
});

describe('language registry', () => {
  it('normalizes aliases', () => {
    assert.equal(normalizeLanguageCode('zh-CN'), 'zh');
    assert.equal(normalizeLanguageCode('ZH_CN'), 'zh');
    assert.equal(normalizeLanguageCode('jp'), 'ja');
    assert.equal(normalizeLanguageCode('ARA'), 'ar');
    assert.equal(normalizeLanguageCode('en-US'), 'en');
  });

  it('keeps auto as auto', () => {
    assert.equal(normalizeLanguageCode('auto'), 'auto');
  });

  it('returns undefined for unknown codes', () => {
    assert.equal(normalizeLanguageCode('xx'), undefined);
    assert.equal(normalizeLanguageCode(''), undefined);
  });

  it('marks Arabic as RTL', () => {
    assert.equal(isRtlLanguage('ar'), true);
    assert.equal(isRtlLanguage('ar-SA'), true);
    assert.equal(isRtlLanguage('en'), false);
  });

  it('exposes language metadata', () => {
    assert.equal(getLanguageInfo('ja')?.nativeName, '日本語');
    assert.equal(getLanguageInfo('ar')?.direction, 'rtl');
  });
});

describe('validation', () => {
  it('accepts a valid translate request', () => {
    const result = validateTranslateInput({
      text: 'Hello',
      sourceLanguage: 'auto',
      targetLanguage: 'ar',
    });
    assert.equal(result.text, 'Hello');
    assert.equal(result.sourceLanguage, 'auto');
    assert.equal(result.targetLanguage, 'ar');
  });

  it('rejects non-string text', () => {
    assert.throws(() => validateTranslateInput({ text: 42 as never, targetLanguage: 'ar' }), /text must be a string/);
  });

  it('rejects empty text', () => {
    assert.throws(() => validateTranslateInput({ text: '   ', targetLanguage: 'ar' }), /non-empty/);
  });

  it('rejects a missing target language', () => {
    assert.throws(
      () => validateTranslateInput({ text: 'Hello', targetLanguage: undefined as never }),
      /targetLanguage is required/,
    );
  });

  it('rejects identical source and target', () => {
    assert.throws(
      () => validateTranslateInput({ text: 'Hello', sourceLanguage: 'ar', targetLanguage: 'ar' }),
      /must differ/,
    );
  });

  it('rejects auto as a target', () => {
    assert.throws(
      () => validateTranslateInput({ text: 'Hello', targetLanguage: 'auto' }),
      /concrete language/,
    );
  });

  it('normalizes language aliases during validation', () => {
    const result = validateTranslateInput({
      text: 'Hello',
      sourceLanguage: 'zh-CN',
      targetLanguage: 'ARA',
    });
    assert.equal(result.sourceLanguage, 'zh');
    assert.equal(result.targetLanguage, 'ar');
  });

  it('validates detect input', () => {
    assert.equal(validateDetectInput({ text: 'مرحبا' }), 'مرحبا');
    assert.throws(() => validateDetectInput({ text: '' }), /non-whitespace/);
  });

  it('accepts plain string chapter segments', () => {
    const result = validateChapterInput({ segments: ['one', 'two'], targetLanguage: 'ar' });
    assert.equal(result.segments.length, 2);
    assert.equal(result.segments[0]?.text, 'one');
  });

  it('accepts object segments with id and speaker', () => {
    const result = validateChapterInput({
      segments: [{ id: 's1', text: 'Hello', speaker: 'Anna' }],
      targetLanguage: 'ar',
    });
    assert.equal(result.segments[0]?.id, 's1');
    assert.equal(result.segments[0]?.speaker, 'Anna');
  });

  it('rejects an empty segment array', () => {
    assert.throws(() => validateChapterInput({ segments: [], targetLanguage: 'ar' }), /at least one/);
  });

  it('rejects an empty segment text', () => {
    assert.throws(
      () => validateChapterInput({ segments: [{ text: '  ' }], targetLanguage: 'ar' }),
      /non-empty string/,
    );
  });

  it('rejects an oversized chapter', () => {
    assert.throws(
      () => validateChapterInput({ segments: Array.from({ length: 5001 }, () => 'x'), targetLanguage: 'ar' }),
      /too large/,
    );
  });

  it('rejects oversized single text', () => {
    assert.throws(() => assertReasonableSize('x'.repeat(200_001)), /exceeds/);
  });
});