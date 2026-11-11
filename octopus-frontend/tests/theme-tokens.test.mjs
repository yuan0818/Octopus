import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { palette, statusColor, severityColor } from '../src/theme.ts';

const kebab = (k) => k.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());

test('tokens.css mirrors theme.ts palette', () => {
  const css = readFileSync(new URL('../src/styles/tokens.css', import.meta.url), 'utf8');
  for (const [key, value] of Object.entries(palette)) {
    assert.match(css, new RegExp(`--oct-${kebab(key)}: ${value};`), `missing or different --oct-${kebab(key)}`);
  }
});

test('semantic maps only use palette colors', () => {
  const values = new Set(Object.values(palette));
  for (const color of [...Object.values(statusColor), ...Object.values(severityColor)]) assert.ok(values.has(color), color);
});

test('no hard-coded hex colors in TSX (use palette from src/theme.ts)', () => {
  const offenders = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) walk(path);
      else if (name.endsWith('.tsx')) {
        readFileSync(path, 'utf8').split('\n').forEach((line, i) => {
          if (/#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}\b/.test(line.replace(/\/\/.*$/, ''))) offenders.push(`${path}:${i + 1}`);
        });
      }
    }
  };
  walk(new URL('../src', import.meta.url).pathname);
  assert.deepEqual(offenders, []);
});
