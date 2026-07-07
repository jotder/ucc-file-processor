#!/usr/bin/env node
// Canonical-vocabulary guard for USER-FACING docs (CLAUDE.md "Canonical vocabulary" + docs/GLOSSARY.md).
//
// Makes the glossary enforceable instead of aspirational: fails the build when a user-facing doc uses a
// ⛔ banned synonym or commits a known concept-confusion. Born from the 2026-07-07 USER_GUIDE audit, whose
// worst finding (A2: "Alert Rule watches a *measure* against a threshold") this guard catches automatically.
//
// SCOPE IS DELIBERATELY NARROW. It scans only the curated user-facing docs below — NOT the design/
// architecture/OKF tree, the glossary itself, or the archive. Those legitimately discuss internal names the
// rename program keeps on purpose (the `FlowGraph` IR, the physical `Store`, the observability `Metric`,
// the `flows/` storage dir — see GLOSSARY §13). Banning those words everywhere would be false-positive
// noise, and a noisy guard gets disabled. Add a doc to USER_FACING only once its vocabulary is pristine.
//
// Zero dependencies (pure Node). Run via `node tools/check-vocabulary.mjs`; wired into CI (ci.yml).
// Escape hatch: append `vocab-allow` in a comment on the offending line for a justified exception.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

// The curated set of user-facing docs whose canonical vocabulary must stay pristine.
const USER_FACING = [
    'docs/USER_GUIDE.md',
    'docs/operations.md',
    'docs/troubleshooting.md',
    'docs/configuration.md',
    'docs/integrations.md',
    'docs/plugins.md',
    'docs/performance.md',
    'docs/parsing-options-reference.md',
    'docs/api-stability.md',
];

// Each rule: a per-line matcher returning the matched text (or null), plus a message. Rules run against
// prose only — fenced code blocks, inline `code` spans, ⛔-citation lines, and ~~strikethrough~~ (used to
// show a banned term being retired) are stripped/skipped first, so citing a ban is never itself a
// violation. Extend cautiously: only add a term that is unambiguous in this scanned set.
const RULES = [
    {
        id: 'measure-threshold',
        test: (line) => (/measure/i.test(line) && /threshold/i.test(line) ? 'measure … threshold' : null),
        msg: 'An Alert Rule watches an observability **Metric** against a threshold, never a BI **Measure** (GLOSSARY §4/§8). This is the A2 confusion.',
    },
    {
        id: 'data-store',
        test: (line) => { const m = line.match(/\bdata stores?\b/i); return m ? m[0] : null; },
        msg: '"Data Store" is banned for a relation — use **Dataset** (GLOSSARY §6-B). "Store/Storage" alone is fine for the physical backend.',
    },
    {
        id: 'bare-flow',
        // The authored DAG is a **Pipeline** (⛔ "Flow"). "Workflow" and lowercase "flow" (of data/control)
        // are allowed; only a standalone capitalized "Flow" noun is flagged.
        test: (line) => { const m = line.replace(/workflow/gi, '').match(/\bFlow(s)?\b/); return m ? m[0] : null; },
        msg: 'The authored DAG is a **Pipeline**, never a "Flow" (GLOSSARY §5).',
    },
    {
        id: 'collector-noun',
        test: (line) => { const m = line.match(/\bcollectors?\b/i); return m ? m[0] : null; },
        msg: 'The configured collection task is a **Source** (⛔ "Collector" as a noun; "collect" as a verb is fine — GLOSSARY §2).',
    },
];

function stripInlineCode(s) {
    return s.replace(/`[^`]*`/g, ''); // drop `inline code` spans so backticked terms never trip a rule
}

const violations = [];
for (const rel of USER_FACING) {
    let text;
    try {
        text = readFileSync(join(repoRoot, rel), 'utf8');
    } catch {
        continue; // a listed doc may not exist on every branch — skip silently
    }
    let inFence = false;
    text.split(/\r?\n/).forEach((raw, i) => {
        const fence = raw.trimStart().startsWith('```');
        if (fence) { inFence = !inFence; return; }
        if (inFence) return;                       // inside a ``` code block
        if (raw.includes('vocab-allow')) return;   // per-line escape hatch
        if (raw.includes('⛔')) return;             // a line explicitly citing a ban
        const line = stripInlineCode(raw).replace(/~~[^~]*~~/g, ''); // drop strikethrough (retired terms)
        for (const rule of RULES) {
            const hit = rule.test(line);
            if (hit) violations.push({ rel, line: i + 1, rule: rule.id, msg: rule.msg, hit, src: raw.trim() });
        }
    });
}

if (violations.length) {
    console.error(`\n✖ Vocabulary guard: ${violations.length} violation(s) in user-facing docs\n`);
    for (const v of violations) {
        console.error(`  ${v.rel}:${v.line}  [${v.rule}] ${v.hit}`);
        console.error(`      ${v.src}`);
        console.error(`      → ${v.msg}\n`);
    }
    console.error('Fix by using the canonical term (docs/GLOSSARY.md), or append `vocab-allow` on the line for a justified exception.\n');
    process.exit(1);
}

console.log(`✓ Vocabulary guard: ${USER_FACING.length} user-facing doc(s) clean — no banned synonyms or concept-confusion.`);
