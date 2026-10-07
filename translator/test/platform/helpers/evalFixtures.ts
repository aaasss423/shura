import type { EvalItem } from '../../../src/platform/eval/dataset';

export function makeItem(overrides: Partial<EvalItem> = {}): EvalItem {
  return {
    id: 'fixture-1',
    sourceLanguage: 'ja',
    sourceText: 'おはようございます',
    referenceArabic: 'صباح الخير',
    category: 'greeting',
    split: 'dev',
    referenceProvenance: 'ai_drafted',
    notes: '',
    ...overrides,
  } as EvalItem;
}
