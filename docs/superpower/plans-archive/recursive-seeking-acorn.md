# Documentation reconciliation — dedup, de-conflict, organize, graphify-visualizable

## Context

The repo's docs grew several overlapping layers: deep topic docs (`docs/*.md`), two new OKF bundles
(`docs/okf-backend/`, `inspecto-ui/docs/okf/`), a stale stakeholder snapshot (`docs/consolidated/`, frozen
2026-06-13), a cited archive (`docs/outdated-doc/`), and `roadmap/`. A deep-dive audit (3 Explore agents +
the prior `consolidated/` Archive-Index/Conflict-Report) found the corpus **mostly healthy** — the topic
docs' apparent "overlaps" are deliberate separation of concerns (model/UX/runtime; spec/reference). The
genuine problems are narrow:

1. **183 broken relative links**, almost all in the two **new OKF bundles** I authored (112 `okf-backend/`,
   66 `inspecto-ui/docs/okf/`). They use OKF's bundle-root `/path.md` form, which an OKF-aware resolver
   accepts but **graphify / GitHub / editors read as repo-root → broken**. OKF also permits relative links;
   relative is portable. This blocks "visualized by graphify."
2. **`docs/consolidated/`** is a drifted 15-day-old duplicate of the topic docs + `roadmap/` + OKF; its ADRs
   live permanently in `inspecto-agent/docs/adr/`. → **archive** (user-approved).
3. A few **stale/conflicting facts** in live docs (flow-graph "not yet built" though shipped; `:4200` vs
   `:4204`; Java 24 vs the canonical **Java 25 floor / built-on-26**).
4. The OKF bundles aren't linked from `INDEX.md`, and graphify's graph needs a refresh after the changes.

Outcome: one authoritative home per topic (the topic docs), OKF + INDEX + roadmap as link-only index layers,
no stale parallel snapshot, no broken links, and a refreshed graphify graph + wiki.

## Approach

Target doc architecture (dedup principle = each topic has ONE authoritative home; everything else *links*):
- `INDEX.md` — the human entry map (kept current; gains OKF pointers).
- `docs/*.md` topic docs — authoritative per-topic detail (kept; light conflict fixes only).
- `docs/okf-backend/` + `inspecto-ui/docs/okf/` — structured, graph-friendly OKF index layer that *cites* the
  topic docs (links fixed to relative).
- `docs/roadmap/` — canonical stakeholder/forward narrative (kept).
- `docs/outdated-doc/` — cited archive (kept; gains the archived `consolidated/` snapshot).
- `docs/consolidated/` — **removed** (archived into `outdated-doc/`).

### Task 1 — Fix OKF bundle cross-links (the big mechanical one)
Convert bundle-root `](/path.md)` links to **relative** in concept-file bodies of both bundles. The
transform is depth-dependent: a file at depth D under the bundle root rewrites `/X` → `(../ × D)X` (root-level
files just strip the leading `/`). Scripted regex pass per file (Node), computing D from the file's path
relative to its bundle root. Index files already use relative links — leave them. Rationale to record: we
deliberately choose OKF's *relative* link form over its *recommended* bundle-root form for portability
(graphify/GitHub/editors), still spec-conformant. Representative offenders:
`docs/okf-backend/acquisition/framework.md` (`/engine/ingestion.md`→`../engine/ingestion.md`),
`inspecto-ui/docs/okf/conventions/accessibility.md` (`/design-system/data-table.md`→`../design-system/data-table.md`).

### Task 2 — Archive `docs/consolidated/`
`git mv docs/consolidated docs/outdated-doc/consolidated-2026-06-13/`. Add a one-line note at the top of its
`README.md` ("Frozen 2026-06-13 snapshot; superseded by the topic docs + roadmap/ + OKF bundles; archived
2026-06-28"). Update inbound references (chiefly `INDEX.md` §"Consolidated reference"). Its internal links
stay valid (relative within the moved dir). Pre-existing `%20`-encoded links inside it: leave (archived).

### Task 3 — Fix live-doc conflicts (surgical, verified)
- `docs/flow-graph-design.md:3` — replace the stale "DESIGN / proposal — not yet built" status with
  "Implemented (see `flow-live-execution-plan.md` + `okf-backend/flow-graph/`)"; flow-graph is shipped
  (`FlowGraph`/`PipelineLift`/`FlowValidator`/`FlowExecutor`/`FlowJobRunner` confirmed in source).
- `docs/operator-console.md` (lines ~52, ~234) — `:4200` → `:4204`.
- `docs/ADVANCED_GUIDE.md:36` — "Java 24 (build JDK 26)" → "Java 25 (build JDK 26)".
- `docs/PROJECT_NOTES.md:20` — "Java 26" → "Java 25+ (built on 26)" for consistency with ADR-0007.
- (Light) `docs/parsing-options-reference.md` / `docs/configuration.md` — add a one-line note that the v4.1
  delimited-grammar + native routing shipped, pointing to `delimited-grammar-design.md` (Conflict-Report C10).
- Leave `acquire-controller-service-design.md` "not yet built" (the NiFi controller-service *redesign* is
  genuinely a proposal; underlying connectors exist but the re-modeling isn't built).

### Task 4 — Wire up INDEX.md
Add an "Engineering knowledge bundles (OKF)" section pointing to both bundle `index.md`s; repoint the
"Consolidated reference" entry to the archived snapshot + note `roadmap/`+OKF are the live equivalents; bump
"Last Updated" to 2026-06-28. Add the consolidated snapshot to the Archive section list. Optionally add the
two bundle pointers to `PROJECT_NOTES.md` §2 doc map.

### Task 5 — Refresh graphify + regenerate wiki
After all edits: `graphify update .` (AST-only, free) to re-index the moved/edited docs, then regenerate the
wiki per the graphify skill (e.g. `graphify . --wiki`) so `graphify-out/wiki/` reflects the reconciled,
link-clean docs. (Wiki regen may incur some API cost — user-approved.)

## Verification
- **Link integrity (headline metric):** a repo-wide relative-link check over `docs/**` +
  `inspecto-ui/docs/okf/**` returns **0 broken** (re-run the scratch Node validator with relative-resolution;
  was 183 → target 0). Confirm `INDEX.md` has no dead links after the archive move.
- **OKF conformance preserved:** re-run the OKF validator on both bundles — still 0 errors (frontmatter +
  `type` intact; only link *form* changed).
- **consolidated/ gone from live tree:** `docs/consolidated/` absent; snapshot present under `outdated-doc/`.
- **Conflicts resolved:** grep shows no `:4200` in `operator-console.md`, no "not yet built" in
  `flow-graph-design.md` status, consistent Java-25-floor phrasing.
- **graphify:** `graphify update .` completes; `graphify-out/wiki/index.md` regenerates; spot
  `graphify query "data-table tiers"` / `"flow-graph executor"` returns the reconciled nodes.
- **Build/tests untouched:** docs-only change — no code touched, so UI/engine build+test are unaffected.

## Out of scope
Merging the (clean, intentionally-separated) topic docs; rewriting `consolidated/` content; restructuring the
OKF bundle taxonomy; the `acquire-controller-service-design` status; committing/pushing (separate, on ask).
