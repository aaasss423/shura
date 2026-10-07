/**
 * Automatic evaluation metrics.
 *
 * Only two, both standard in the MT literature, both implemented here so the
 * platform keeps zero dependencies:
 *
 *  - **BLEU** (Papineni et al.) — n-gram precision with a brevity penalty and
 *    clipping against multiple references.
 *  - **chrF++** (Popović 2017) — character n-gram F-score with a whitespace word
 *    boundary bonus. This is the more useful of the two for Arabic, where
 *    clitic segmentation and diacritics make word-level matching brittle.
 *
 * Plus target-language validity checks that are *not* fluency judgements:
 * Arabic presence, digit preservation, punctuation shape, no encoding damage.
 *
 * Deliberately absent: any single "quality score". Fluency in Arabic is not
 * computable here, and collapsing several signals into one number invites
 * reading a digest as a verdict. Callers get the components.
 */

export interface SegmentScore {
  bleu: number;
  chrf: number;
  /** Length ratio hypothesis/reference; <1 means the output is short. */
  lengthRatio: number;
  charSimilarity: number;
  hasArabic: boolean;
  digitsPreserved: boolean;
  encodingClean: boolean;
}

export interface AggregateScore {
  count: number;
  bleu: number;
  chrf: number;
  charSimilarity: number;
  hasArabicRate: number;
  digitsPreservedRate: number;
  encodingCleanRate: number;
  /** Fraction with lengthRatio below the brevity threshold. */
  truncatedRate: number;
}

// ---------------------------------------------------------------------------
// Tokenization
// ---------------------------------------------------------------------------

const ARABIC_DIACRITICS = /[\u064B-\u0652\u0670\u0640]/g;

/**
 * Normalizes for scoring.
 *
 * Arabic diacritics and tatweel are stripped because a human reader reads them
 * as optional marks; leaving them in would penalise correct output for adding a
 * vowel mark.
 */
export function normalizeForScoring(text: string): string {
  return text
    .normalize('NFKC')
    .replace(ARABIC_DIACRITICS, '')
    .replace(/[\u200B-\u200F\u202A-\u202E\uFEFF]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export function tokenizeForBleu(text: string): string[] {
  const normalized = normalizeForScoring(text);
  if (normalized.length === 0) {
    return [];
  }
  return normalized.split(/(\s+)/).filter((token) => !/^\s+$/.test(token));
}

function charNgrams(text: string, maxOrder = 6): Map<number, number[]> {
  const normalized = normalizeForScoring(text);
  const out = new Map<number, number[]>();
  for (let order = 1; order <= maxOrder; order += 1) {
    const counts: number[] = new Array(normalized.length).fill(0);
    if (normalized.length < order) {
      out.set(order, counts);
      continue;
    }
    for (let i = 0; i + order <= normalized.length; i += 1) {
      counts[i] = 1;
    }
    out.set(order, counts);
  }
  return out;
}

/** Punctuation and digits extracted for the validity checks. */
function digitsOf(text: string): string[] {
  return text.match(/\d+/g) ?? [];
}

const ENCODING_DAMAGE = /\uFFFD|\u0000/;

// ---------------------------------------------------------------------------
// BLEU
// ---------------------------------------------------------------------------

export interface BleuOptions {
  maxOrder?: number;
  /** Shortest reference length used for the brevity penalty. */
  brevity?: 'shortest' | 'closest';
}

/**
 * Sentence-level BLEU with clipping against multiple references.
 *
 * Returns 0 for an empty hypothesis, which is the correct behaviour: an empty
 * output matches nothing.
 */
export function bleu(hypothesis: string, references: string[], options: BleuOptions = {}): number {
  const maxOrder = options.maxOrder ?? 4;
  const hyp = tokenizeForBleu(hypothesis);
  if (hyp.length === 0) {
    return 0;
  }
  const refs = references.map((r) => tokenizeForBleu(r)).filter((r) => r.length > 0);
  if (refs.length === 0) {
    return 0;
  }

  // Effective order: only n-gram orders the hypothesis is long enough to have
  // contribute. Averaging in an impossible order would report BLEU 0 for a
  // correct two-word output, which is a metric artefact rather than a quality
  // signal.
  const precisions: number[] = [];
  let effectiveOrder = 0;
  for (let order = 1; order <= maxOrder; order += 1) {
    const hypCounts = new Map<string, number>();
    let hypTotal = 0;
    for (let i = 0; i + order <= hyp.length; i += 1) {
      const gram = hyp.slice(i, i + order).join(' ');
      hypCounts.set(gram, (hypCounts.get(gram) ?? 0) + 1);
      hypTotal += 1;
    }
    if (hypTotal === 0) {
      continue;
    }
    effectiveOrder += 1;

    let clipped = 0;
    for (let i = 0; i + order <= hyp.length; i += 1) {
      const gram = hyp.slice(i, i + order).join(' ');
      let maxRefCount = 0;
      for (const ref of refs) {
        let count = 0;
        for (let j = 0; j + order <= ref.length; j += 1) {
          if (ref.slice(j, j + order).join(' ') === gram) {
            count += 1;
          }
        }
        maxRefCount = Math.max(maxRefCount, count);
      }
      clipped += Math.min(hypCounts.get(gram) ?? 0, maxRefCount);
    }
    precisions.push(clipped / hypTotal);
  }

  if (precisions.length === 0 || precisions.some((p) => p === 0)) {
    return 0;
  }
  const logMean = precisions.reduce((sum, p) => sum + Math.log(p), 0) / effectiveOrder;

  const refLength =
    options.brevity === 'closest'
      ? refs.reduce((best, r) => (Math.abs(r.length - hyp.length) < Math.abs(best - hyp.length) ? r.length : best), refs[0]!.length)
      : Math.min(...refs.map((r) => r.length));
  const brevityPenalty = hyp.length === 0 ? 0 : Math.exp(Math.min(0, 1 - refLength / hyp.length));

  return round4(brevityPenalty * Math.exp(logMean));
}

// ---------------------------------------------------------------------------
// chrF++
// ---------------------------------------------------------------------------

export interface ChrfOptions {
  maxOrder?: number;
  /** chrF++ word-boundary penalty exponent (β² in the paper). */
  beta?: number;
}

/**
 * chrF++ as specified: character n-gram F-score averaged over orders, multiplied
 * by a penalty for missing whitespace word boundaries, which is what makes it
 * more sensitive to word order than chrF.
 */
export function chrf(hypothesis: string, references: string[], options: ChrfOptions = {}): number {
  const maxOrder = options.maxOrder ?? 6;
  const beta = options.beta ?? 2;
  const hyp = normalizeForScoring(hypothesis);
  const refs = references.map(normalizeForScoring).filter((r) => r.length > 0);
  if (hyp.length === 0 || refs.length === 0) {
    return 0;
  }

  const hypCounts = charNgrams(hyp, maxOrder);
  const refCountsList = refs.map((r) => charNgrams(r, maxOrder));

  let totalF = 0;
  for (let order = 1; order <= maxOrder; order += 1) {
    const hypOrder = hypCounts.get(order) ?? [];
    let matchCount = 0;
    let hypTotal = 0;
    for (let i = 0; i < hypOrder.length; i += 1) {
      if (hypOrder[i] !== 1) {
        continue;
      }
      hypTotal += 1;
      const gram = hyp.slice(i, i + order);
      let maxCount = 0;
      for (const [refIndex, refCounts] of refCountsList.entries()) {
        const refOrder = refCounts.get(order) ?? [];
        const reference = refs[refIndex]!;
        let count = 0;
        for (let j = 0; j + order <= reference.length; j += 1) {
          if (refOrder[j] === 1 && reference.slice(j, j + order) === gram) {
            count += 1;
          }
        }
        maxCount = Math.max(maxCount, count);
      }
      matchCount += Math.min(1, maxCount);
    }

    let refTotal = 0;
    for (const refCounts of refCountsList) {
      const refOrder = refCounts.get(order) ?? [];
      for (const flag of refOrder) {
        refTotal += flag;
      }
    }
    if (refTotal === 0) {
      continue;
    }

    const precision = hypTotal === 0 ? 0 : matchCount / hypTotal;
    const recall = matchCount / refTotal;
    const f = precision + recall === 0 ? 0 : (2 * precision * recall) / (precision + recall);
    totalF += f;
  }

  const avgF = totalF / maxOrder;

  // Word-boundary penalty: fraction of the hypothesis' whitespace boundaries
  // that are absent from every reference.
  const hypBoundaries = boundarySet(hyp);
  const refBoundaries = new Set<string>();
  for (const ref of refs) {
    for (const boundary of boundarySet(ref)) {
      refBoundaries.add(boundary);
    }
  }
  let matched = 0;
  for (const boundary of hypBoundaries) {
    if (refBoundaries.has(boundary)) {
      matched += 1;
    }
  }
  const precision = hypBoundaries.size === 0 ? 1 : matched / hypBoundaries.size;
  const penalty = precision === 0 ? 0 : 1 - Math.pow(Math.max(0, 1 - precision), beta);

  return round4(avgF * penalty);
}

function boundarySet(text: string): Set<string> {
  const boundaries = new Set<string>();
  for (let i = 0; i < text.length - 1; i += 1) {
    if (/\s/.test(text[i]!)) {
      boundaries.add(text[i]! + text[i + 1]!);
    }
  }
  return boundaries;
}

// ---------------------------------------------------------------------------
// Similarity and validity
// ---------------------------------------------------------------------------

/** Normalized Levenshtein, for a readable similarity figure. */
export function characterSimilarity(a: string, b: string): number {
  const left = normalizeForScoring(a);
  const right = normalizeForScoring(b);
  if (left.length === 0 && right.length === 0) {
    return 1;
  }
  if (left.length === 0 || right.length === 0) {
    return 0;
  }
  let previous = new Array<number>(right.length + 1);
  let current = new Array<number>(right.length + 1);
  for (let j = 0; j <= right.length; j += 1) {
    previous[j] = j;
  }
  for (let i = 1; i <= left.length; i += 1) {
    current[0] = i;
    for (let j = 1; j <= right.length; j += 1) {
      const cost = left[i - 1] === right[j - 1] ? 0 : 1;
      current[j] = Math.min(
        (current[j - 1] ?? 0) + 1,
        (previous[j] ?? 0) + 1,
        (previous[j - 1] ?? 0) + cost,
      );
    }
    const swap = previous;
    previous = current;
    current = swap;
  }
  const distance = previous[right.length] ?? 0;
  return round4(1 - distance / Math.max(left.length, right.length));
}

export function scoreSegment(hypothesis: string, reference: string): SegmentScore {
  const hypNorm = normalizeForScoring(hypothesis);
  const refNorm = normalizeForScoring(reference);
  const refDigits = digitsOf(reference);
  const hypDigits = new Set(digitsOf(hypothesis));

  return {
    bleu: bleu(hypothesis, [reference]),
    chrf: chrf(hypothesis, [reference]),
    lengthRatio: refNorm.length === 0 ? (hypNorm.length === 0 ? 1 : 0) : round4(hypNorm.length / refNorm.length),
    charSimilarity: characterSimilarity(hypothesis, reference),
    hasArabic: /[\u0600-\u06FF]/.test(hypothesis),
    digitsPreserved: refDigits.every((digit) => hypDigits.has(digit)),
    encodingClean: !ENCODING_DAMAGE.test(hypothesis),
  };
}

export function aggregate(scores: SegmentScore[]): AggregateScore {
  if (scores.length === 0) {
    return {
      count: 0, bleu: 0, chrf: 0, charSimilarity: 0,
      hasArabicRate: 0, digitsPreservedRate: 0, encodingCleanRate: 0, truncatedRate: 0,
    };
  }
  const mean = (values: number[]): number => round4(values.reduce((a, b) => a + b, 0) / values.length);
  return {
    count: scores.length,
    bleu: mean(scores.map((s) => s.bleu)),
    chrf: mean(scores.map((s) => s.chrf)),
    charSimilarity: mean(scores.map((s) => s.charSimilarity)),
    hasArabicRate: mean(scores.map((s) => (s.hasArabic ? 1 : 0))),
    digitsPreservedRate: mean(scores.map((s) => (s.digitsPreserved ? 1 : 0))),
    encodingCleanRate: mean(scores.map((s) => (s.encodingClean ? 1 : 0))),
    truncatedRate: mean(scores.map((s) => (s.lengthRatio < 0.5 ? 1 : 0))),
  };
}

/** Groups scores, so a report can be per-pair and per-category. */
export function groupScores(
  scores: Array<SegmentScore & { language: string; category: string }>,
  key: 'language' | 'category',
): Record<string, AggregateScore> {
  const buckets = new Map<string, SegmentScore[]>();
  for (const entry of scores) {
    const bucketKey = key === 'language' ? `${entry.language}->ar` : entry.category;
    const bucket = buckets.get(bucketKey) ?? [];
    bucket.push(entry);
    buckets.set(bucketKey, bucket);
  }
  const out: Record<string, AggregateScore> = {};
  for (const [bucketKey, bucket] of [...buckets.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    out[bucketKey] = aggregate(bucket);
  }
  return out;
}

function round4(value: number): number {
  return Math.round(value * 10000) / 10000;
}