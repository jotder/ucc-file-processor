#!/usr/bin/env node
// Design-system governance guard (UI/UX audit — Long-term #1).
//
// Fails the build when inspecto-authored source hardcodes colors or re-introduces a
// hand-rolled status/level→color helper, instead of using the shared design system:
//   - Status/level/severity colors  -> inspecto/components/status-badge.component.ts
//   - Chart/canvas colors (the ONE sanctioned place to hardcode) -> inspecto/theme/chart-tokens.ts
//   - Everything else                -> gamma `--gamma-*` CSS vars / Tailwind utility classes
//
// Zero dependencies (pure Node). Run via `npm run lint:tokens`; wired into the UI CI workflow.
// Escape hatch: append `ds-allow` in a comment on the offending line for an intentional exception.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative, sep } from 'node:path';

const uiRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const toPosix = (p) => p.split(sep).join('/');

// Directories that hold inspecto-authored, design-system-consuming code.
// (Vendored Fuse scaffolding under modules/auth/** and src/@gamma/** is intentionally out of scope.)
const ROOTS = ['src/app/inspecto', 'src/app/modules/admin'];

// The only files permitted to hardcode colors / own the status-color mapping. These are the
// sanctioned design-system color owners: the chart palette, the status pill, the inline alert
// banner, and the app-wide connectivity banner. Everything else consumes them.
const ALLOWLIST = new Set([
    'src/app/inspecto/theme/chart-tokens.ts',
    'src/app/inspecto/components/status-badge.component.ts',
    'src/app/inspecto/components/alert.component.ts',
    'src/app/inspecto/components/connectivity-banner.component.ts',
]);

const EXTS = new Set(['.ts', '.html', '.scss', '.css']);

// Rules. Each: a per-line matcher returning the matched text (or null), plus a message.
const HTML_NUM_ENTITY = /&#\d+;/g; // e.g. &#123; (= "{") — strip so it isn't read as a hex color
const HEX = /#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3}(?:[0-9a-fA-F]{2})?)?\b/;
const RGB_LITERAL = /\brgba?\(\s*\d/i; // literal channels; allows rgba(var(--gamma-…), .12)
const FORBIDDEN_HELPER = /\b(levelClass|severityClass|toneClass|statusColorClass)\b/;
// Status-tinted background FILLS (the tell-tale of a hand-rolled status pill/banner). Restricted to the
// unambiguous status/severity tones — blue/indigo/sky/etc. are intentionally excluded since they double
// as brand/accent surfaces. `text-*`/`border-*` tones are NOT flagged (legit inline emphasis / required
// asterisks). Reach for <inspecto-status-badge> (pills) or <inspecto-alert> (banners) instead.
const STATUS_BG_FILL = /\bbg-(red|orange|amber|yellow|lime|green|emerald|teal|rose)-\d/;

const RULES = [
    {
        id: 'hardcoded-hex',
        test: (line) => {
            const m = line.replace(HTML_NUM_ENTITY, '').match(HEX);
            return m ? m[0] : null;
        },
        msg: 'hardcoded hex color — use a `--gamma-*` var / Tailwind class, or chart-tokens.ts for canvas',
    },
    {
        id: 'hardcoded-rgb',
        test: (line) => {
            const m = line.match(RGB_LITERAL);
            return m ? m[0] : null;
        },
        msg: 'literal rgb()/rgba() color — use a `--gamma-*` var, or rgba(var(--gamma-…),a) / chart-tokens.ts',
    },
    {
        id: 'status-color-helper',
        tsOnly: true,
        test: (line) => {
            const m = line.match(FORBIDDEN_HELPER);
            return m ? m[0] : null;
        },
        msg: 'hand-rolled status/level color helper — use <inspecto-status-badge> / statusBadgeHtml() / statusTone',
    },
    {
        id: 'status-bg-fill',
        test: (line) => {
            const m = line.match(STATUS_BG_FILL);
            return m ? m[0] : null;
        },
        msg: 'hand-rolled status-tinted background — use <inspecto-status-badge> (pill) or <inspecto-alert> (banner)',
    },
];

function* walk(dir) {
    let entries;
    try {
        entries = readdirSync(dir);
    } catch {
        return;
    }
    for (const name of entries) {
        const full = join(dir, name);
        if (statSync(full).isDirectory()) {
            yield* walk(full);
        } else {
            yield full;
        }
    }
}

const violations = [];
for (const root of ROOTS) {
    for (const file of walk(join(uiRoot, root))) {
        const rel = toPosix(relative(uiRoot, file));
        if (ALLOWLIST.has(rel)) continue;
        const ext = rel.slice(rel.lastIndexOf('.'));
        if (!EXTS.has(ext)) continue;

        const lines = readFileSync(file, 'utf8').split(/\r?\n/);
        lines.forEach((line, i) => {
            if (line.includes('ds-allow')) return;
            for (const rule of RULES) {
                if (rule.tsOnly && ext !== '.ts') continue;
                const hit = rule.test(line);
                if (hit) {
                    violations.push({ rel, line: i + 1, rule: rule.id, msg: rule.msg, hit, src: line.trim() });
                }
            }
        });
    }
}

if (violations.length) {
    console.error(`\n✖ Design-system guard: ${violations.length} violation(s)\n`);
    for (const v of violations) {
        console.error(`  ${v.rel}:${v.line}  [${v.rule}] ${v.hit}`);
        console.error(`      ${v.src}`);
        console.error(`      → ${v.msg}\n`);
    }
    console.error('Fix by using the shared design system, or append `ds-allow` on the line for a justified exception.\n');
    process.exit(1);
}

console.log('✓ Design-system guard: no hardcoded colors or status-color helpers in inspecto-authored source.');
