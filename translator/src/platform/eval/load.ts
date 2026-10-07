/**
 * Filesystem loader for the corpus.
 *
 * Kept separate from `dataset.ts` so the dataset logic stays pure and testable
 * without touching disk.
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import type { EvalManifest, EvalSourceLanguage, DatasetError } from './dataset';
import { buildDataset, type EvalDataset } from './dataset';

const LANGUAGES: EvalSourceLanguage[] = ['ja', 'zh', 'ko', 'en'];

export function defaultEvalRoot(): string {
  // dist/src/platform/eval -> repository root
  return path.resolve(__dirname, '..', '..', '..', '..', 'eval');
}

export function loadDataset(root: string = defaultEvalRoot()): EvalDataset {
  const manifestPath = path.join(root, 'manifest.json');
  if (!fs.existsSync(manifestPath)) {
    throw new Error(`evaluation manifest not found at ${manifestPath}`);
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) as EvalManifest;

  const rawByLanguage: Partial<Record<EvalSourceLanguage, unknown>> = {};
  for (const language of LANGUAGES) {
    const file = path.join(root, 'corpus', `${language}.json`);
    if (!fs.existsSync(file)) {
      throw new Error(`corpus file missing for ${language}: ${file}`);
    }
    rawByLanguage[language] = JSON.parse(fs.readFileSync(file, 'utf8')) as unknown;
  }

  try {
    return buildDataset({ manifest, rawByLanguage });
  } catch (error) {
    const typed = error as DatasetError;
    throw new Error(`evaluation corpus is invalid: ${typed.message}${typed.itemId ? ` (item ${typed.itemId})` : ''}`);
  }
}
