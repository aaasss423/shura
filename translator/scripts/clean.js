#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const targets = ['dist', '.cache'];

for (const target of targets) {
  const full = path.join(root, target);
  if (fs.existsSync(full)) {
    fs.rmSync(full, { recursive: true, force: true });
    process.stdout.write(`removed ${target}/\n`);
  }
}