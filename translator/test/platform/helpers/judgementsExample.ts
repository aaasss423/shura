/**
 * A filled judgement file, for tests only.
 *
 * Lives under `test/` and not under `eval/` on purpose: example verdicts sitting
 * next to the real corpus could be mistaken for a completed human review, and a
 * selection decision rests on that file being real.
 */

import { promises as fs } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export async function writeJudgementFile(contents: unknown, name = 'judgements'): Promise<string> {
  const file = join(tmpdir(), `serving-${name}-${process.pid}-${Math.random().toString(36).slice(2)}.json`);
  await fs.writeFile(file, typeof contents === 'string' ? contents : JSON.stringify(contents, null, 2), 'utf8');
  return file;
}
