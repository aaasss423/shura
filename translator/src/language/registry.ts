/**
 * Language registry: canonical codes, names, direction, and normalization of
 * the many aliases engines and clients use. Kept separate from detection so the
 * registry can be extended without touching detection heuristics.
 */

import type { LanguageCode, LanguageInfo } from '../core/types';

interface LanguageDefinition extends LanguageInfo {
  aliases: string[];
  /** Source languages this language can be detected from. */
  category: 'source' | 'target';
}

const DEFINITIONS: LanguageDefinition[] = [
  {
    code: 'en',
    name: 'English',
    nativeName: 'English',
    direction: 'ltr',
    category: 'source',
    aliases: ['eng', 'en-us', 'en-gb', 'en_us', 'en_gb'],
  },
  {
    code: 'ja',
    name: 'Japanese',
    nativeName: '日本語',
    direction: 'ltr',
    category: 'source',
    aliases: ['jp', 'jpn', 'ja-jp', 'japanese'],
  },
  {
    code: 'zh',
    name: 'Chinese',
    nativeName: '中文',
    direction: 'ltr',
    category: 'source',
    aliases: ['zh-cn', 'zh_cn', 'zh-hans', 'zh-hant', 'zh-tw', 'chi', 'chinese', 'cmn', 'zho'],
  },
  {
    code: 'ko',
    name: 'Korean',
    nativeName: '한국어',
    direction: 'ltr',
    category: 'source',
    aliases: ['kr', 'kor', 'ko-kr', 'korean'],
  },
  {
    code: 'ar',
    name: 'Arabic',
    nativeName: 'العربية',
    direction: 'rtl',
    category: 'target',
    aliases: ['ara', 'ar-sa', 'ar_sa', 'arabic', 'msa', 'ar-eg'],
  },
  {
    code: 'fr',
    name: 'French',
    nativeName: 'Français',
    direction: 'ltr',
    category: 'source',
    aliases: ['fra', 'fre', 'fr-fr', 'french'],
  },
  {
    code: 'de',
    name: 'German',
    nativeName: 'Deutsch',
    direction: 'ltr',
    category: 'source',
    aliases: ['ger', 'deu', 'de-de', 'german'],
  },
  {
    code: 'es',
    name: 'Spanish',
    nativeName: 'Español',
    direction: 'ltr',
    category: 'source',
    aliases: ['spa', 'es-es', 'spanish'],
  },
  {
    code: 'ru',
    name: 'Russian',
    nativeName: 'Русский',
    direction: 'ltr',
    category: 'source',
    aliases: ['rus', 'ru-ru', 'russian'],
  },
  {
    code: 'pt',
    name: 'Portuguese',
    nativeName: 'Português',
    direction: 'ltr',
    category: 'source',
    aliases: ['por', 'pt-br', 'pt-pt', 'portuguese'],
  },
  {
    code: 'it',
    name: 'Italian',
    nativeName: 'Italiano',
    direction: 'ltr',
    category: 'source',
    aliases: ['ita', 'it-it', 'italian'],
  },
  {
    code: 'tr',
    name: 'Turkish',
    nativeName: 'Türkçe',
    direction: 'ltr',
    category: 'source',
    aliases: ['tur', 'tr-tr', 'turkish'],
  },
];

const BY_CODE = new Map<string, LanguageDefinition>();
const BY_ALIAS = new Map<string, LanguageDefinition>();

for (const definition of DEFINITIONS) {
  BY_CODE.set(definition.code, definition);
  BY_ALIAS.set(definition.code, definition);
  for (const alias of definition.aliases) {
    BY_ALIAS.set(alias.toLowerCase(), definition);
  }
}

export function normalizeLanguageCode(input: string | undefined | null): LanguageCode | undefined {
  if (!input) {
    return undefined;
  }
  const trimmed = input.trim().toLowerCase();
  if (trimmed === 'auto') {
    return 'auto';
  }
  const direct = BY_ALIAS.get(trimmed);
  if (direct) {
    return direct.code;
  }
  // Fall back to the primary subtag, e.g. "ar-SA-x-foo" -> "ar".
  const primary = trimmed.split(/[-_]/)[0];
  return primary ? BY_ALIAS.get(primary)?.code : undefined;
}

export function getLanguageInfo(code: string): LanguageInfo | undefined {
  const normalized = normalizeLanguageCode(code);
  return normalized && normalized !== 'auto' ? BY_CODE.get(normalized) : undefined;
}

export function isKnownLanguage(code: string): boolean {
  return getLanguageInfo(code) !== undefined;
}

export function isRtlLanguage(code: string): boolean {
  return getLanguageInfo(code)?.direction === 'rtl';
}

export function listSourceLanguages(): LanguageInfo[] {
  return DEFINITIONS.filter((d) => d.category === 'source').map(toPublic);
}

export function listTargetLanguages(): LanguageInfo[] {
  return DEFINITIONS.filter((d) => d.category === 'target').map(toPublic);
}

export function listAllLanguages(): LanguageInfo[] {
  return DEFINITIONS.map(toPublic);
}

function toPublic(definition: LanguageDefinition): LanguageInfo {
  const info: LanguageInfo = {
    code: definition.code,
    name: definition.name,
    direction: definition.direction,
  };
  if (definition.nativeName) {
    info.nativeName = definition.nativeName;
  }
  return info;
}