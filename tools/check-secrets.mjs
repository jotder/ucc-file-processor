#!/usr/bin/env node
// Committed-secret guard (SEC-INCIDENT-1 remediation — docs/BACKLOG.md §5).
//
// Born from the 2026-07-25 incident: five OAuth client secrets sat in
// inspecto-ui/src/environments/*.ts and were pushed to a PUBLIC remote for six weeks. Removing
// them from HEAD remediated nothing (history keeps the values), so the only durable win left is
// making REINTRODUCTION fail the build instead of shipping quietly.
//
// WHAT IT FLAGS: a secret-ish key assigned to a long literal string — `clientSecret: '<32 hex>'`,
// `password = "…"`, `client_secret=…` in a URL or form body. That is the exact shape of the
// incident, and it is what a config-file copy/paste looks like.
//
// WHAT IT DELIBERATELY DOES NOT FLAG, because a noisy guard gets disabled (the lesson recorded in
// tools/check-vocabulary.mjs):
//   - Empty values, `${ENV:…}`, `process.env.X`, `<placeholder>`, changeme/example/dummy/test —
//     the sanctioned ways to NOT hold a secret. `${ENV:…}` is the bundle contract (BACKLOG D2).
//   - Prose. Only assignment syntax matches, so a comment saying "no client secret here" is fine,
//     as are the BACKLOG rows that name the leaked keys.
//   - Short values (< MIN_SECRET_LEN). Real credentials are long and high-entropy; test fixtures
//     using `password: "test"` are not the risk this guard exists for.
//   - `token`-suffixed keys. `tokenEndpoint`/`tokenUrl` are URLs, and after D15 they are REQUIRED
//     config — flagging them would fire on correct deployments.
//
// Zero dependencies (pure Node). Run via `node tools/check-secrets.mjs`; wired into CI (ci.yml).
// Escape hatch: append `secret-allow` in a comment on the offending line for a justified exception.
//
// NOTE ON BRANCHES: this guard now runs on BOTH `master` and `4.x`. It was master-only until
// 2026-07-25 because `4.x` carried the live values in its own environments/*.ts; PKCE P0+P1
// (`481a68d5`, `89cb3cce`, `8c3a7654`) removed `appClientSecret` from `4.x` entirely, so the guard
// is green there and was brought forward. Keep the two copies IDENTICAL — a divergence means one
// branch is guarded by weaker rules than the other, which is exactly how the incident recurs.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative, sep } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const toPosix = (p) => p.split(sep).join('/');

// Directories never worth scanning: dependencies, build output, generated artifacts, and the
// unmaintained archive. `.claude/worktrees/` is gitignored scratch — it held unversioned copies of
// all five leaked secrets, which is why it is cleaned separately rather than guarded here.
const SKIP_DIRS = new Set([
    'node_modules', '.git', 'target', 'dist', 'out', '.angular', '.mvn',
    'graphify-out', 'worktrees', 'archived-documents', 'coverage',
]);

const EXTS = new Set([
    '.ts', '.js', '.mjs', '.cjs', '.java', '.json', '.yml', '.yaml',
    '.toon', '.properties', '.xml', '.ps1', '.sh', '.bat', '.env',
]);

const SKIP_FILES = new Set(['package-lock.json', 'tools/check-secrets.mjs']);

// A credential-bearing key. `token` is excluded on purpose (see header).
const SECRET_KEY = '[A-Za-z0-9_.-]*(?:secret|password|passwd|credential|api[_-]?key|access[_-]?key|private[_-]?key)[A-Za-z0-9_.-]*';

// Keys that NAME or LOCATE a credential rather than hold one: `apiKeyRef: 'ANTHROPIC_API_KEY'`,
// `passwordFile: /run/secrets/db`. The whole point of these is to keep the value out of the repo.
const INDIRECT_KEY = /(?:ref|name|env|var|file|path|alias)$/i;

// `key: 'value'` / `key = "value"` / `key: value` (unquoted, e.g. TOON / .properties).
const QUOTED = new RegExp(`\\b(${SECRET_KEY})\\s*[:=]\\s*(['"\`])([^'"\`]*)\\2`, 'i');
// `client_secret=…` in a URL query or form body — no quotes, terminated by & or whitespace.
const URL_PARAM = new RegExp(`\\b(${SECRET_KEY})=([^&\\s'"\`<>]+)`, 'i');

// Values that are explicitly NOT a secret: env indirection, placeholders, obvious fixtures.
const PLACEHOLDER = [
    /^\s*$/,                                  // blank — the sanctioned "left empty" state
    /\$\{/,                                   // ${ENV:…}, ${VAR}, Maven/Spring interpolation
    /^\$[A-Za-z_]/,                           // $VAR
    /^%[A-Za-z_][A-Za-z0-9_]*%$/,             // %HTTPS_KEYSTORE_PASSWORD% (cmd/batch indirection)
    /example/i,                               // AWS's published SigV4 vectors (AKIDEXAMPLE,
                                              // wJalrXUtnFEMI/…EXAMPLEKEY) and doc placeholders
                                              // universally embed "EXAMPLE" by convention
    /x{6,}/i,                                 // sk-xxxxxxxxxxxx — a redacted sample key
    /process\.env|System\.getenv|System\.getProperty|import\.meta\.env/,
    /^<.*>$/,                                 // <your-secret-here>
    /^(?:your|my)[_-]?/i,
    /^(?:changeme|change[_-]?me|placeholder|redacted|masked|example|dummy|sample|fake|none|null|undefined|test|testing|secret|password)$/i,
    /^\*+$/,                                  // ****
    /^x+$/i,                                  // xxxx
    /^(?:TODO|FIXME)/i,
];

// Real credentials are long. Below this, the false-positive rate dwarfs the signal.
const MIN_SECRET_LEN = 16;

function isPlaceholder(value) {
    return PLACEHOLDER.some((re) => re.test(value));
}

function* walk(dir) {
    let entries;
    try {
        entries = readdirSync(dir);
    } catch {
        return;
    }
    for (const name of entries) {
        const full = join(dir, name);
        let st;
        try {
            st = statSync(full);
        } catch {
            continue;
        }
        if (st.isDirectory()) {
            if (SKIP_DIRS.has(name)) continue;
            yield* walk(full);
        } else {
            yield full;
        }
    }
}

// A COMMITTED-secret guard must scan what is committed. Walking the filesystem also read gitignored
// build output — `file-processor-deploy/ui/chunk-*.js` produced four hits on a clean tree (minified
// `withCredentials`/`apiKey` property assignments), so every shift that built the bundle then met a red
// security gate on its own machine while CI, which has no such directory, stayed green. A guard that
// cries wolf locally is a guard people learn to ignore. Falls back to the filesystem walk outside a
// git checkout (a tarball export), where scanning too much beats scanning nothing.
function* trackedFiles() {
    let listed;
    try {
        listed = execFileSync('git', ['-C', repoRoot, 'ls-files', '-z'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    } catch {
        yield* walk(repoRoot);
        return;
    }
    for (const rel of listed.split('\0')) {
        if (rel) yield join(repoRoot, rel);
    }
}

const violations = [];
for (const file of trackedFiles()) {
    const rel = toPosix(relative(repoRoot, file));
    if (SKIP_FILES.has(rel) || SKIP_FILES.has(rel.slice(rel.lastIndexOf('/') + 1))) continue;
    // walk() skips these by never descending; the tracked-file list has to filter them by path.
    if (rel.split('/').slice(0, -1).some((seg) => SKIP_DIRS.has(seg))) continue;
    const dot = rel.lastIndexOf('.');
    if (dot < 0 || !EXTS.has(rel.slice(dot))) continue;

    let lines;
    try {
        lines = readFileSync(file, 'utf8').split(/\r?\n/);
    } catch {
        continue;
    }

    lines.forEach((line, i) => {
        if (line.includes('secret-allow')) return;
        for (const re of [QUOTED, URL_PARAM]) {
            const m = line.match(re);
            if (!m) continue;
            const key = m[1];
            const value = re === QUOTED ? m[3] : m[2];
            if (INDIRECT_KEY.test(key)) continue;
            if (isPlaceholder(value) || value.length < MIN_SECRET_LEN) continue;
            violations.push({ rel, line: i + 1, key, len: value.length });
            break;
        }
    });
}

if (violations.length) {
    console.error(`\n✖ Committed-secret guard: ${violations.length} probable secret(s)\n`);
    for (const v of violations) {
        // Never echo the value — CI logs are themselves a disclosure surface.
        console.error(`  ${v.rel}:${v.line}  ${v.key} = <${v.len} chars, not shown>`);
    }
    console.error(`
A credential must never be committed. Move it to deployment config the code reads at runtime
(\`\${ENV:…}\` / an env var / a secret manager) and leave the checked-in value empty.

A browser bundle CANNOT hold a confidential secret — anything in inspecto-ui/src/environments/ is
public by construction. Use a public PKCE client, or exchange the token server-side.

If this really is not a secret, append \`secret-allow\` on the line with a reason.

If a real secret was already pushed, deletion is NOT remediation — rotate it at the issuer.
See docs/BACKLOG.md §5 (SEC-INCIDENT-1).
`);
    process.exit(1);
}

console.log('✓ Committed-secret guard: no probable secrets in committed source or config.');
