/**
 * Evaluation dataset, metrics and holdout enforcement.
 *
 * The important assertions here are negative ones: a corrupt corpus must not
 * load, a metric must not reward unrelated text, and test-split text must be
 * rejected by the writers that feed the models.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  buildDataset,
  filterDataset,
  pairsIn,
  claimCeiling,
  supportsQualityClaim,
  DatasetError,
  type EvalItem,
  type EvalManifest,
} from '../../src/platform/eval/dataset';
import { loadDataset, defaultEvalRoot } from '../../src/platform/eval/load';
import { HoldoutGuard, HoldoutLeakError, exportForTraining } from '../../src/platform/eval/holdout';
import {
  bleu,
  chrf,
  characterSimilarity,
  normalizeForScoring,
  scoreSegment,
  aggregate,
  groupScores,
} from '../../src/platform/eval/scoring';
import { runEval, renderSummary, loadJudgements, judgementsPath } from '../../src/platform/benchmark/runner';
import { KnowledgeRepository } from '../../src/platform/knowledge/repository';
import { TranslationMemoryRepository } from '../../src/platform/memory/repository';
import { ResearchQueue } from '../../src/platform/research/queue';
import { Prewarmer } from '../../src/platform/prewarm';
import { openMemoryDatabase } from '../../src/platform/db/database';
import { DeterministicEngine } from '../../src/platform/engine/local/engine';
import { makeItem } from './helpers/evalFixtures';

const real = loadDataset(defaultEvalRoot());

function corrupt(mutate: (manifest: EvalManifest, items: Record<string, unknown[]>) => void) {
  const items = JSON.parse(JSON.stringify({
    ja: [makeItem({ id: 'a', sourceText: 'おはよう' })],
    zh: [makeItem({ id: 'b', sourceText: '你好' })],
  })) as Record<string, unknown[]>;
  const manifest = {
    version: 'test',
    created: '2026-01-01',
    description: 'test',
    languages: ['ja', 'zh'],
    target_language: 'ar',
    splits: { dev: 'dev.json', test: 'test.json' },
    reference_provenance: { a: 'ai_drafted', b: 'ai_drafted' },
    categories: ['short'],
    leakage_policy: { holdout_enforcement: 'none', guarantees: [] },
  } as unknown as EvalManifest;
  mutate(manifest, items);
  return () => buildDataset({ manifest, rawByLanguage: items as never });
}

describe('evaluation corpus on disk', () => {
  it('loads and reports balanced languages and splits', () => {
    assert.equal(real.stats.total, 196);
    assert.equal(real.stats.byLanguage.ja, 49);
    assert.equal(real.stats.byLanguage.zh, 49);
    assert.equal(real.stats.byLanguage.ko, 49);
    assert.equal(real.stats.byLanguage.en, 49);
    assert.ok((real.stats.bySplit.dev ?? 0) > 0);
    assert.ok((real.stats.bySplit.test ?? 0) > 0);
  });

  it('has unique ids and non-empty Arabic references everywhere', () => {
    assert.equal(new Set(real.items.map((i) => i.id)).size, real.items.length);
    for (const item of real.items) {
      assert.ok(item.sourceText.trim().length > 0, item.id);
      assert.ok(item.referenceArabic.trim().length > 0, item.id);
      assert.ok(scoreSegment(item.referenceArabic, item.referenceArabic).hasArabic, `${item.id} reference is not Arabic`);
    }
  });

  it('keeps every item in the declared split and category', () => {
    for (const item of real.items) {
      assert.ok(item.split === 'dev' || item.split === 'test', `${item.id} split`);
      assert.ok(real.manifest.categories.includes(item.category), `${item.id} category`);
    }
  });
});

describe('dataset validation', () => {
  it('rejects duplicate ids', () => {
    assert.throws(
      corrupt((manifest, items) => {
        (items.ja![0] as EvalItem).id = 'dup';
        (items.zh![0] as EvalItem).id = 'dup';
        manifest.reference_provenance = { dup: 'ai_drafted' };
      }),
      DatasetError,
    );
  });

  it('rejects an item missing from the manifest provenance map', () => {
    assert.throws(
      corrupt((manifest) => {
        manifest.reference_provenance = {};
      }),
      DatasetError,
    );
  });

  it('rejects a reference with no Arabic script', () => {
    assert.throws(
      corrupt((_manifest, items) => {
        (items.ja![0] as EvalItem).referenceArabic = 'no arabic here';
      }),
      DatasetError,
    );
  });

  it('rejects a blank source', () => {
    assert.throws(
      corrupt((_manifest, items) => {
        (items.ja![0] as EvalItem).sourceText = '   ';
      }),
      DatasetError,
    );
  });

  it('rejects a split value that is neither dev nor test', () => {
    assert.throws(
      corrupt((_manifest, items) => {
        (items.ja![0] as EvalItem).split = 'train' as never;
      }),
      DatasetError,
    );
  });
});

describe('dataset queries', () => {
  it('filters by split and by language', () => {
    assert.ok(filterDataset(real, { split: 'test' }).every((i) => i.split === 'test'));
    assert.ok(filterDataset(real, { language: 'ko' }).every((i) => i.sourceLanguage === 'ko'));
    assert.ok(filterDataset(real, { category: 'idiom' }).every((i) => i.category === 'idiom'));
  });

  it('lists language pairs in canonical order', () => {
    assert.deepEqual(pairsIn(real), ['en->ar', 'ja->ar', 'ko->ar', 'zh->ar']);
  });
});

describe('quality claim ceiling', () => {
  it('refuses quality claims while references are machine-drafted', () => {
    assert.equal(real.stats.humanVerified, 0);
    assert.equal(supportsQualityClaim(real), false);
    assert.match(claimCeiling(real), /NOT human-verified/i);
  });

  it('allows quality claims only when every scored reference is human-verified', () => {
    // The gate is the per-item provenance, not the summary counters: a report
    // must not be unlocked by editing stats.
    const halfVerified = {
      ...real,
      items: real.items.map((item) =>
        item.split === 'test' && item.id.endsWith('0')
          ? ({ ...item, referenceProvenance: 'human_verified' } as EvalItem)
          : item,
      ),
    };
    assert.equal(supportsQualityClaim(halfVerified), false);

    const verified = {
      ...real,
      items: real.items.map((item) =>
        item.split === 'test' ? ({ ...item, referenceProvenance: 'human_verified' } as EvalItem) : item,
      ),
    };
    assert.equal(supportsQualityClaim(verified), true);
    assert.match(claimCeiling(verified), /may be reported/i);
  });
});

describe('scoring metrics', () => {
  it('scores identical text as a perfect match', () => {
    assert.equal(chrf('هذا مستحيل', ['هذا مستحيل']), 1);
    assert.equal(bleu('هذا مستحيل', ['هذا مستحيل']), 1);
    assert.equal(characterSimilarity('هذا مستحيل', 'هذا مستحيل'), 1);
  });

  it('does not reward unrelated text', () => {
    assert.equal(chrf('قطة زرقاء تطير فوق البحر', ['هذا مستحيل']), 0);
    assert.equal(bleu('قطة زرقاء', ['هذا مستحيل']), 0);
    assert.ok(characterSimilarity('قطة زرقاء', 'هذا مستحيل') <= 0.1);
  });

  it('gives partial credit for a paraphrase and ranks it above noise', () => {
    const partial = chrf('هذا ليس ممكناً', ['هذا مستحيل']);
    assert.ok(partial > 0, 'paraphrase should score above zero');
    assert.ok(partial < 1, 'paraphrase is not a perfect match');
  });

  it('returns zero rather than throwing on an empty hypothesis', () => {
    assert.equal(chrf('', ['هذا مستحيل']), 0);
    assert.equal(bleu('', ['هذا مستحيل']), 0);
    assert.equal(scoreSegment('', 'هذا مستحيل').chrf, 0);
  });

  it('treats Arabic diacritics and tatweel as scoring noise', () => {
    assert.equal(normalizeForScoring('مُحَمَّدٌ'), normalizeForScoring('محمد'));
    assert.equal(normalizeForScoring('مصــــر'), normalizeForScoring('مصر'));
  });

  it('detects digit loss between output and reference', () => {
    assert.equal(scoreSegment('الطول 165 سم', 'الطول 165 سم').digitsPreserved, true);
    assert.equal(scoreSegment('الطول كبير', 'الطول 165 سم').digitsPreserved, false);
  });

  it('flags output that is not Arabic', () => {
    assert.equal(scoreSegment('This is impossible', 'هذا مستحيل').hasArabic, false);
    assert.equal(scoreSegment('هذا مستحيل', 'هذا مستحيل').hasArabic, true);
  });

  it('flags output that is much shorter than the reference', () => {
    const short = scoreSegment('هذا', 'كان أبي بخير حتى الأمس');
    assert.ok(short.lengthRatio < 0.5);
    assert.equal(aggregate([short]).truncatedRate, 1);
    assert.equal(aggregate([scoreSegment('هذا مستحيل', 'هذا مستحيل')]).truncatedRate, 0);
  });

  it('averages an aggregate without inventing precision', () => {
    const agg = aggregate([
      scoreSegment('هذا مستحيل', 'هذا مستحيل'),
      scoreSegment('قطة زرقاء', 'هذا مستحيل'),
    ]);
    assert.equal(agg.count, 2);
    assert.equal(agg.chrf, 0.5);
    assert.equal(agg.hasArabicRate, 1);
  });

  it('groups segment scores by language and by category', () => {
    const scored = [
      { ...scoreSegment('هذا مستحيل', 'هذا مستحيل'), language: 'ja', category: 'short' },
      { ...scoreSegment('هذا مستحيل', 'هذا مستحيل'), language: 'zh', category: 'idiom' },
    ];
    const byLanguage = groupScores(scored, 'language');
    assert.equal(byLanguage['ja->ar']?.count, 1);
    assert.equal(byLanguage['zh->ar']?.count, 1);
    const byCategory = groupScores(scored, 'category');
    assert.equal(byCategory.short?.count, 1);
    assert.equal(byCategory.idiom?.count, 1);
  });
});

describe('holdout guard', () => {
  const guard = new HoldoutGuard(real);

  it('blocks writes containing test-split source text', () => {
    const target = real.items.find((i) => i.split === 'test')!;
    const verdict = guard.checkWrite({ term: target.sourceText });
    assert.equal(verdict.allowed, false);
    assert.ok(verdict.reason);
  });

  it('blocks writes containing test-split reference text', () => {
    const target = real.items.find((i) => i.split === 'test')!;
    const verdict = guard.checkWrite({ term: 'a fresh unrelated search', translation: target.referenceArabic });
    assert.equal(verdict.allowed, false);
  });

  it('allows dev-split text', () => {
    const target = real.items.find((i) => i.split === 'dev')!;
    assert.equal(guard.checkWrite({ term: target.sourceText }).allowed, true);
  });

  it('ignores bidi controls and spacing differences', () => {
    const target = real.items.find((i) => i.split === 'test')!;
    const padded = `\u202b${target.sourceText}  \u202c`;
    assert.equal(guard.checkWrite({ term: padded }).allowed, false);
  });

  it('exports only dev items for training, by default', () => {
    const exported = exportForTraining(real);
    assert.ok(exported.items.length > 0);
    assert.ok(exported.items.every((i) => i.provenance !== undefined));
    assert.equal(exported.excludedHoldout, filterDataset(real, { split: 'test' }).length);
    const testIds = new Set(filterDataset(real, { split: 'test' }).map((i) => i.id));
    assert.ok(exported.items.every((i) => !testIds.has(i.id)));
  });

  it('records a deliberate holdout release instead of hiding it', () => {
    const released = exportForTraining(real, { allowHoldout: true });
    assert.equal(released.allowHoldout, true);
    assert.equal(released.items.length, real.items.length);
  });

  it('reports what a dataset export must exclude', () => {
    const stats = guard.exportStats(real);
    assert.equal(stats.excluded, stats.excludedIds.length);
    assert.equal(stats.exported + stats.excluded, real.items.length);
  });
});

describe('writers reject holdout leakage', () => {
  const target = real.items.find((i) => i.split === 'test')!;
  const guard = new HoldoutGuard(real);

  it('knowledge repository refuses a term derived from test text', () => {
    const repo = new KnowledgeRepository(openMemoryDatabase(), guard);
    assert.throws(
      () => repo.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term', term: target.sourceText, translation: 'شيء ما' }),
      HoldoutLeakError,
    );
  });

  it('knowledge repository refuses publishing the test reference', () => {
    const repo = new KnowledgeRepository(openMemoryDatabase(), guard);
    assert.throws(
      () => repo.upsert({ sourceLanguage: 'ja', targetLanguage: 'ar', category: 'term', term: 'كلمة جديدة', translation: target.referenceArabic }),
      HoldoutLeakError,
    );
  });

  it('translation memory refuses a pair whose target is the test reference', () => {
    const repo = new TranslationMemoryRepository(openMemoryDatabase(), guard);
    assert.throws(
      () => repo.store({ sourceLanguage: 'ja', targetLanguage: 'ar', sourceText: 'مصطلح جديد', targetText: target.referenceArabic }),
      HoldoutLeakError,
    );
  });

  it('translation memory refuses storing test source text', () => {
    const repo = new TranslationMemoryRepository(openMemoryDatabase(), guard);
    assert.throws(
      () => repo.store({ sourceLanguage: 'ja', targetLanguage: 'ar', sourceText: target.sourceText, targetText: 'ترجمة' }),
      HoldoutLeakError,
    );
  });

  it('research queue refuses a queued lookup for test text', () => {
    const queue = new ResearchQueue(openMemoryDatabase(), guard);
    assert.throws(
      () => queue.enqueue({ sourceLanguage: 'ja', targetLanguage: 'ar', term: target.sourceText }),
      HoldoutLeakError,
    );
  });

  it('prewarm is covered transitively through the research queue', () => {
    const db = openMemoryDatabase();
    const queue = new ResearchQueue(db, guard);
    const prewarmer = new Prewarmer(db, queue);
    assert.throws(
      () => prewarmer.createBatch({
        sourceLanguage: 'ja',
        targetLanguage: 'ar',
        sources: [{ category: 'term', terms: [target.sourceText] }],
      }),
      HoldoutLeakError,
    );
  });

  it('still accepts ordinary dev-derived content', () => {
    const db = openMemoryDatabase();
    const repo = new KnowledgeRepository(db, guard);
    const stored = repo.upsert({
      sourceLanguage: 'ja',
      targetLanguage: 'ar',
      category: 'term',
      term: '太陽',
      translation: 'الشمس',
    });
    assert.ok(stored.id > 0);

    const tm = new TranslationMemoryRepository(db, guard);
    const entry = tm.store({ sourceLanguage: 'ja', targetLanguage: 'ar', sourceText: '月の光', targetText: 'ضوء القمر' });
    assert.ok(entry.id > 0);
    assert.equal(tm.lookup('ja', 'ar', '月の光')?.targetText, 'ضوء القمر');
  });
});

describe('benchmark runner', () => {
  const items = filterDataset(real, { split: 'test', language: 'ja' }).slice(0, 5);

  it('runs every item and scores it', async () => {
    const report = await runEval({
      engine: new DeterministicEngine(),
      items,
      datasetVersion: real.manifest.version,
      split: 'test',
    });
    assert.equal(report.totals.items, items.length);
    assert.equal(report.totals.failed, 0);
    assert.equal(report.latency.count, items.length);
    assert.equal(report.supportsQualityClaim, false);
    assert.match(report.claimCeiling, /NOT human-verified/i);
    assert.ok(Object.keys(report.byPair).length > 0);
  });

  it('isolates a failing item instead of aborting the run', async () => {
    const flaky = new DeterministicEngine();
    let calls = 0;
    const wrapped = Object.create(flaky) as DeterministicEngine;
    wrapped.translate = async (request) => {
      calls += 1;
      if (calls === 2) {
        throw new Error('synthetic failure');
      }
      return flaky.translate(request);
    };
    const report = await runEval({
      engine: wrapped,
      items,
      datasetVersion: real.manifest.version,
      split: 'test',
    });
    assert.equal(report.totals.failed, 1);
    assert.equal(report.totals.ok, items.length - 1);
  });

  it('counts human verdicts when judgements exist, and zero when they do not', async () => {
    const withoutJudgements = await runEval({
      engine: new DeterministicEngine(),
      items,
      datasetVersion: real.manifest.version,
      split: 'test',
    });
    assert.equal(withoutJudgements.human.reviewed, 0);
    assert.match(renderSummary([withoutJudgements]), /human review not performed/i);

    const judged = await runEval({
      engine: new DeterministicEngine(),
      items,
      datasetVersion: real.manifest.version,
      split: 'test',
      judgements: [
        { itemId: items[0]!.id, verdict: 'win', note: 'clearer', reviewer: 'tester' },
        { itemId: items[1]!.id, verdict: 'loss', note: 'worse', reviewer: 'tester' },
      ],
    });
    assert.equal(judged.human.reviewed, 2);
    assert.equal(judged.human.byVerdict.win, 1);
    assert.equal(judged.human.byVerdict.loss, 1);
  });

  it('renders a summary that states the claim ceiling', async () => {
    const report = await runEval({
      engine: new DeterministicEngine(),
      items,
      datasetVersion: real.manifest.version,
      split: 'test',
    });
    const text = renderSummary([report]);
    assert.match(text, /claim ceiling/i);
    assert.match(text, /human review not performed/i);
  });
});

describe('human judgement files', () => {
  it('returns an empty list when no judgement file exists', () => {
    assert.deepEqual(loadJudgements(judgementsPath(defaultEvalRoot(), 'test')), []);
  });

  it('ignores an unreadable judgement file rather than crashing the run', () => {
    assert.deepEqual(loadJudgements('/nonexistent/judgements-test.json'), []);
  });
});
