/* Translation Platform UI.
 * Talks to the real REST API only; there is no mock data path.
 * Aborting a request uses AbortController, which the server turns into a
 * genuine cancellation of the in-flight engine request.
 */
'use strict';

const $ = (id) => document.getElementById(id);

const state = {
  mode: 'single',
  inFlight: null,
  lastRequest: null,
  lastChapter: null,
  lastChapterBody: null,
  languages: { source: [], target: [] },
  engineInfo: {},
};

/* ---------- bootstrap ---------- */

async function boot() {
  bindTabs();
  bindSingle();
  bindChapter();
  bindSettings();
  await loadMeta();
}

async function loadMeta() {
  try {
    const [langs, caps] = await Promise.all([
      apiGet('/languages'),
      apiGet('/engine/capabilities'),
    ]);
    state.languages = langs;
    state.engineInfo = caps;
    $('engineBadge').textContent = `engine: ${caps.engine}`;
    $('engineBadge').classList.add('ok');
    $('limitBadge').textContent = `limit: ${caps.maxCharsPerRequest} chars/request`;
    populateLanguageSelects();
    checkHealth();
  } catch (error) {
    $('engineBadge').textContent = `engine: unavailable (${error.message})`;
    $('engineBadge').classList.add('err');
  }
}

async function checkHealth() {
  try {
    const health = await apiGet('/engine/health');
    $('healthBadge').textContent = `health: ${health.healthy ? 'ok' : 'down'}${health.latencyMs ? ` (${health.latencyMs}ms)` : ''}`;
    $('healthBadge').classList.add(health.healthy ? 'ok' : 'err');
  } catch {
    $('healthBadge').textContent = 'health: unreachable';
    $('healthBadge').classList.add('err');
  }
}

function populateLanguageSelects() {
  const fill = (element, languages, includeAuto) => {
    element.innerHTML = '';
    if (includeAuto) {
      element.appendChild(new Option('Auto detect', 'auto'));
    }
    for (const lang of languages) {
      element.appendChild(new Option(`${lang.name} (${lang.code})`, lang.code));
    }
  };
  fill($('sourceLang'), state.languages.source, true);
  fill($('targetLang'), state.languages.target, false);
  fill($('cSourceLang'), state.languages.source, true);
  fill($('cTargetLang'), state.languages.target, false);
  $('sourceLang').value = 'auto';
  $('targetLang').value = 'ar';
  $('cSourceLang').value = 'auto';
  $('cTargetLang').value = 'ar';
}

/* ---------- api helpers ---------- */

async function apiGet(path) {
  const response = await fetch(path);
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error?.message || `HTTP ${response.status}`);
  }
  return response.json();
}

async function apiPut(path, payload) {
  const response = await fetch(path, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.error?.message || `HTTP ${response.status}`);
    error.code = body.error?.code;
    throw error;
  }
  return body;
}

async function apiDelete(path) {
  const response = await fetch(path, { method: 'DELETE' });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.error?.message || `HTTP ${response.status}`);
  }
  return body;
}

async function apiPost(path, payload, signal) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
    signal,
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.error?.message || `HTTP ${response.status}`);
    error.code = body.error?.code;
    error.status = response.status;
    throw error;
  }
  return body;
}

/* ---------- tabs ---------- */

function bindTabs() {
  for (const tab of document.querySelectorAll('.tab')) {
    tab.addEventListener('click', () => {
      state.mode = tab.dataset.mode;
      for (const other of document.querySelectorAll('.tab')) {
        other.classList.toggle('active', other === tab);
      }
      $('singleMode').classList.toggle('hidden', state.mode !== 'single');
      $('chapterMode').classList.toggle('hidden', state.mode !== 'chapter');
      $('settingsMode').classList.toggle('hidden', state.mode !== 'settings');
      if (state.mode === 'settings') {
        refreshEngines();
        refreshSecretStatus();
      }
    });
  }
}

/* ---------- single text ---------- */

function bindSingle() {
  $('translateBtn').addEventListener('click', translate);
  $('detectBtn').addEventListener('click', detectLanguage);
  $('clearBtn').addEventListener('click', () => {
    $('text').value = '';
    $('singleResult').classList.add('hidden');
    $('singleError').innerHTML = '';
    $('qualityBox').innerHTML = '';
    state.lastRequest = null;
  });
  $('copyBtn').addEventListener('click', () => copy($('resultText').textContent));
  $('retryBtn').addEventListener('click', () => {
    if (state.lastRequest) {
      // A retry must actually re-run the translation, not replay a cached value.
      translate({ noCache: true });
    }
  });
}

async function translate(overrides = {}) {
  const text = $('text').value;
  if (!text.trim()) {
    showError($('singleError'), 'Enter some text first.');
    return;
  }
  if (state.inFlight) {
    state.inFlight.abort();
  }
  const controller = new AbortController();
  state.inFlight = controller;

  const payload = {
    text,
    sourceLanguage: $('sourceLang').value,
    targetLanguage: $('targetLang').value,
    timeoutMs: Number($('timeoutMs').value) || undefined,
    noCache: Boolean($('noCache').checked) || Boolean(overrides.noCache),
  };
  state.lastRequest = payload;

  setLoading(true);
  showError($('singleError'), '');

  try {
    const result = await apiPost('/translate', payload, controller.signal);
    state.inFlight = null;
    setLoading(false);
    renderTranslation(result);
  } catch (error) {
    state.inFlight = null;
    setLoading(false);
    if (error.name === 'AbortError') {
      showError($('singleError'), 'Request cancelled.', 'warn');
      return;
    }
    showError($('singleError'), `${error.code ? error.code + ': ' : ''}${error.message}`);
  }
}

async function detectLanguage() {
  const text = $('text').value;
  if (!text.trim()) {
    showError($('singleError'), 'Enter some text first.');
    return;
  }
  setLoading(true);
  try {
    const result = await apiPost('/detect-language', { text });
    showError(
      $('singleError'),
      `Detected: ${result.language} (confidence ${result.confidence}, direction ${result.direction})`,
      'info',
    );
  } catch (error) {
    showError($('singleError'), error.message);
  } finally {
    setLoading(false);
  }
}

function renderTranslation(result) {
  const box = $('singleResult');
  box.classList.remove('hidden');
  const textEl = $('resultText');
  textEl.textContent = result.text;
  textEl.dir = result.direction === 'rtl' ? 'rtl' : 'ltr';

  $('resultMeta').innerHTML = [
    `engine: ${result.engine}`,
    `from cache: ${result.fromCache}`,
    `segments: ${result.segments}`,
    `${result.elapsedMs}ms`,
    result.detectedLanguage ? `detected: ${result.detectedLanguage}` : null,
  ]
    .filter(Boolean)
    .map((item) => `<span>${item}</span>`)
    .join('');

  const quality = result.quality;
  const qBox = $('qualityBox');
  if (!quality || quality.score === undefined) {
    qBox.innerHTML = '';
  } else if (quality.issues && quality.issues.length > 0) {
    qBox.innerHTML = `<div class="alert warn"><strong>Quality score ${quality.score}</strong><ul>${
      quality.issues.map((i) => `<li>${i.severity}: ${i.message}</li>`).join('')
    }</ul></div>`;
  } else {
    qBox.innerHTML = `<div class="alert info">Quality score ${quality.score} — no issues detected.</div>`;
  }
}

function setLoading(loading) {
  $('translateBtn').disabled = loading;
  $('detectBtn').disabled = loading;
  $('translateBtn').innerHTML = loading ? '<span class="spinner"></span> Translating…' : 'Translate';
}

/* ---------- chapter ---------- */

function bindChapter() {
  $('chapterBtn').addEventListener('click', () => translateChapter());
  $('chapterRetryBtn').addEventListener('click', () => translateChapter({ retry: true }));
  $('chapterClearBtn').addEventListener('click', () => {
    $('chapterText').value = '';
    $('chapterResult').classList.add('hidden');
    $('chapterError').innerHTML = '';
    $('chapterProgress').classList.add('hidden');
    $('chapterRetryBtn').disabled = true;
    state.lastChapter = null;
  });
  $('chapterCopyBtn').addEventListener('click', () => copy($('chapterSegments').dataset.fullText || ''));
}

function chapterSegmentsFromText() {
  return $('chapterText')
    .value.split(/\n{2,}/)
    .map((block) => block.replace(/\n/g, ' ').trim())
    .filter((block) => block.length > 0)
    .map((block) => ({ text: block }));
}

async function translateChapter(options = {}) {
  let segments = chapterSegmentsFromText();
  if (options.retry) {
    if (!state.lastChapter || !state.lastChapterBody) {
      showError($('chapterError'), 'Nothing to retry yet.');
      return;
    }
    // Re-run only the segments that failed, merged back into the full result.
    const failedIndexes = state.lastChapter.segments
      .map((segment, index) => (segment.error ? index : -1))
      .filter((index) => index >= 0);
    if (failedIndexes.length === 0) {
      showError($('chapterError'), 'No failed segments to retry.', 'info');
      return;
    }
    const all = chapterSegmentsFromText();
    segments = failedIndexes.map((index) => all[index]);
    $('chapterProgressLabel').textContent = `retrying ${segments.length} failed segment(s)…`;
  } else if (segments.length === 0) {
    showError($('chapterError'), 'Enter chapter text first.');
    return;
  }

  state.lastChapterBody = {
    segments,
    sourceLanguage: $('cSourceLang').value,
    targetLanguage: $('cTargetLang').value,
    concurrency: Number($('concurrency').value) || 3,
  };

  const progress = $('chapterProgress');
  progress.classList.remove('hidden');
  $('chapterProgressFill').style.width = '0%';
  $('chapterProgressLabel').textContent = 'translating…';
  $('chapterBtn').disabled = true;
  $('chapterRetryBtn').disabled = true;
  showError($('chapterError'), '');

  try {
    const result = await apiPost('/translate/chapter', state.lastChapterBody);
    const merged = options.retry ? mergeRetry(state.lastChapter, result) : result;
    state.lastChapter = merged;
    renderChapter(merged);
    $('chapterProgressFill').style.width = '100%';
    const failed = merged.progress.failedSegments || 0;
    $('chapterProgressLabel').textContent = failed
      ? `${merged.progress.completedSegments}/${merged.progress.totalSegments} segments, ${failed} failed`
      : `${merged.progress.totalSegments}/${merged.progress.totalSegments} segments translated`;
  } catch (error) {
    showError($('chapterError'), `${error.code ? error.code + ': ' : ''}${error.message}`);
    $('chapterProgressLabel').textContent = 'failed';
  } finally {
    $('chapterBtn').disabled = false;
    $('chapterRetryBtn').disabled = !state.lastChapter?.segments?.some((s) => s.error);
  }
}

/** Merges a retry response (subset of segments) back into the previous result. */
function mergeRetry(previous, retry) {
  const merged = previous.segments.map((s) => ({ ...s }));
  retry.segments.forEach((replacement) => {
    const index = previous.segments.findIndex((s) => s.id === replacement.id);
    if (index >= 0) {
      merged[index] = { ...merged[index], ...replacement };
    }
  });
  const text = merged.map((s) => s.translated).join('\n');
  const failed = merged.filter((s) => s.error).length;
  return {
    ...previous,
    text,
    segments: merged,
    degraded: merged.some((s) => s.fallback || s.error),
    progress: {
      totalSegments: merged.length,
      completedSegments: merged.length - failed,
      failedSegments: failed,
      cachedSegments: merged.filter((s) => s.fromCache).length,
      state: failed ? 'failed' : 'completed',
    },
  };
}

function renderChapter(result) {
  $('chapterResult').classList.remove('hidden');
  $('chapterMeta').innerHTML = [
    `target: ${result.targetLanguage}`,
    `direction: ${result.direction}`,
    `${result.elapsedMs}ms`,
    `segments: ${result.progress.totalSegments}`,
    `cached: ${result.progress.cachedSegments}`,
    `failed: ${result.progress.failedSegments}`,
  ]
    .map((item) => `<span>${item}</span>`)
    .join('');

  const degraded = $('chapterDegraded');
  if (result.degraded) {
    degraded.classList.remove('hidden');
    degraded.textContent =
      'Some segments failed and kept their original text. Nothing was lost; retry the failed segments.';
  } else {
    degraded.classList.add('hidden');
  }

  const list = $('chapterSegments');
  list.dataset.fullText = result.text;
  list.innerHTML = result.segments
    .map((segment) => {
      const dir = result.direction === 'rtl' ? 'rtl' : 'ltr';
      const state = segment.error ? 'failed' : segment.fallback ? 'failed' : '';
      return `<div class="segment ${state}">
        <div class="segment-source">#${segment.index} ${escapeHtml(segment.source)}</div>
        <div class="segment-translated" dir="${dir}">${escapeHtml(segment.translated)}</div>
        <div class="segment-meta">${segment.fromCache ? 'cached' : 'translated'}${
          segment.error ? ` · error: ${escapeHtml(segment.error.code)}` : ''
        }${segment.fallback ? ' · original kept' : ''} · ${segment.elapsedMs}ms</div>
      </div>`;
    })
    .join('');
}

/* ---------- settings: engines and the API key ---------- */

function bindSettings() {
  const input = $('deeplKey');
  $('toggleKeyBtn').addEventListener('click', () => {
    // Password by default; the value is never rendered elsewhere.
    const revealed = input.type === 'password';
    input.type = revealed ? 'text' : 'password';
    $('toggleKeyBtn').textContent = revealed ? 'Hide' : 'Show';
    $('toggleKeyBtn').setAttribute('aria-pressed', String(revealed));
  });

  $('saveKeyBtn').addEventListener('click', saveKey);
  $('deleteKeyBtn').addEventListener('click', deleteKey);
  $('testKeyBtn').addEventListener('click', testKey);
}

async function refreshEngines() {
  try {
    const { engines, routing } = await apiGet('/engines');
    $('engineList').innerHTML = engines
      .map(
        (engine) => `<div class="engine-row">
          <span class="badge ${engine.available ? 'ok' : 'err'}">${engine.available ? 'available' : 'unavailable'}</span>
          <span class="engine-id">${escapeHtml(engine.id)}</span>
          ${engine.maxCharsPerRequest ? `<span class="muted">${engine.maxCharsPerRequest} chars/request</span>` : ''}
          ${engine.reason ? `<span class="engine-reason">${escapeHtml(engine.reason)}</span>` : ''}
        </div>`,
      )
      .join('');
    $('routingMeta').innerHTML = [
      `default: ${escapeHtml(routing.default)}`,
      routing.rules.length ? `routes: ${routing.rules.map((r) => `${r.source}→${r.engine}`).join(', ')}` : null,
      routing.fallbacks.length ? `fallbacks: ${routing.fallbacks.join(', ')}` : null,
    ]
      .filter(Boolean)
      .map((item) => `<span>${item}</span>`)
      .join('');
  } catch (error) {
    $('engineList').innerHTML = `<span class="muted">could not load engines: ${escapeHtml(error.message)}</span>`;
  }
}

/** Reads status only. The stored key is never returned by the API. */
async function refreshSecretStatus() {
  try {
    const { secrets } = await apiGet('/secrets');
    const status = secrets.find((s) => s.engine === 'deepl');
    if (!status) {
      $('secretBadge').textContent = 'key: unknown';
      return;
    }
    $('secretBadge').textContent = status.configured
      ? `key: configured (${status.source})`
      : 'key: not configured';
    $('secretBadge').classList.toggle('ok', status.configured);
    $('secretBadge').classList.toggle('err', !status.configured);
    $('secretDetail').textContent = status.configured
      ? `fingerprint ${status.fingerprint}${status.updatedAt ? ` · updated ${new Date(status.updatedAt).toLocaleString()}` : ''}`
      : 'Add a key to enable the DeepL engine.';
    $('deleteKeyBtn').disabled = !status.configured;
  } catch (error) {
    $('secretBadge').textContent = 'key: status unavailable';
    $('secretDetail').textContent = error.message;
  }
}

async function saveKey() {
  const input = $('deeplKey');
  const value = input.value.trim();
  if (!value) {
    showError($('secretMessage'), 'Paste a key first.', 'warn');
    return;
  }
  $('saveKeyBtn').disabled = true;
  try {
    // Sent once over the API, then cleared from the field. The response
    // contains status only, never the key.
    await apiPut('/secrets/deepl', { apiKey: value });
    input.value = '';
    input.type = 'password';
    $('toggleKeyBtn').textContent = 'Show';
    $('toggleKeyBtn').setAttribute('aria-pressed', 'false');
    showError($('secretMessage'), 'Key saved.', 'info');
    await refreshSecretStatus();
    await refreshEngines();
  } catch (error) {
    showError($('secretMessage'), `${error.code ? error.code + ': ' : ''}${error.message}`);
  } finally {
    $('saveKeyBtn').disabled = false;
  }
}

async function deleteKey() {
  if (!window.confirm('Delete the stored DeepL API key? The engine will be disabled.')) {
    return;
  }
  try {
    await apiDelete('/secrets/deepl');
    $('deeplKey').value = '';
    showError($('secretMessage'), 'Key deleted.', 'info');
    await refreshSecretStatus();
    await refreshEngines();
  } catch (error) {
    showError($('secretMessage'), error.message);
  }
}

async function testKey() {
  $('testKeyBtn').disabled = true;
  try {
    // Health probe for the engine itself; no key material is involved.
    const response = await fetch('/engine/health?engine=deepl');
    const health = await response.json();
    if (health.healthy) {
      showError($('secretMessage'), `DeepL is reachable (${health.latencyMs}ms).`, 'info');
    } else {
      showError($('secretMessage'), `DeepL is not usable: ${health.detail || 'unknown reason'}`, 'warn');
    }
    await refreshEngines();
  } catch (error) {
    showError($('secretMessage'), error.message);
  } finally {
    $('testKeyBtn').disabled = false;
  }
}

/* ---------- misc ---------- */

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[c]);
}

function showError(element, message, kind = 'error') {
  if (!message) {
    element.innerHTML = '';
    return;
  }
  element.innerHTML = `<div class="alert ${kind}">${escapeHtml(message)}</div>`;
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    // Clipboard API can be unavailable over plain http; fall back to a textarea.
    const area = document.createElement('textarea');
    area.value = text;
    document.body.appendChild(area);
    area.select();
    document.execCommand('copy');
    area.remove();
  }
}

boot();