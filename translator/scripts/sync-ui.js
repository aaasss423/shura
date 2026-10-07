#!/usr/bin/env node
'use strict';

/**
 * Copies the static UI (ui/) into dist/src/ui so the compiled server can serve
 * it without depending on the repository layout at runtime.
 */

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const src = path.join(root, 'ui');
const dest = path.join(root, 'dist', 'src', 'ui');

function copyRecursive(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
    const s = path.join(from, entry.name);
    const d = path.join(to, entry.name);
    if (entry.isDirectory()) {
      copyRecursive(s, d);
    } else if (entry.isFile()) {
      fs.copyFileSync(s, d);
    }
  }
}

if (!fs.existsSync(src)) {
  process.stderr.write(`ui source directory not found: ${src}\n`);
  process.exit(1);
}

fs.rmSync(dest, { recursive: true, force: true });
copyRecursive(src, dest);
process.stdout.write(`synced ui/ -> dist/src/ui\n`);