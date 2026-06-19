## graphify

A persistent knowledge graph of this repo lives at `graphify-out/` (god nodes, communities,
cross-file relationships). The **`graphify` CLI is installed and is the REQUIRED first step** for code
exploration here — PreToolUse hooks enforce a query-first workflow.

- **The tool** is the Python **`graphifyy`** package (PyPI), CLI command `graphify` — **NOT** the
  unrelated npm package of the same name. Don't `npm install graphify`.
- **Per-user setup (run once):** `pwsh scripts/setup-graphify.ps1` (Windows) or
  `bash scripts/setup-graphify.sh` (POSIX/Git-Bash). It installs/upgrades the CLI onto **your** PATH and
  refreshes the graph. `graphify-out/` and `/.claude/` are **gitignored** (generated graph / per-user
  config) but shared on-disk in this sandbox, so the graph + enforcement hooks already apply to everyone
  here; only the CLI itself is per-user (each OS user has their own Python/PATH), hence the one-time setup.
- **Query-first workflow (hook-enforced):** before grepping or reading source, orient with
  `graphify query "<question>"`, `graphify explain "<node>"`, or `graphify path "<A>" "<B>"`. Read/grep raw
  files only after graphify has oriented you, or to modify/debug specific lines. `graphify-out/GRAPH_REPORT.md`
  is a static fallback map for broad orientation.
- **Keep it fresh:** run `graphify update .` (deterministic, **no LLM cost**) after code changes — or re-run
  the setup script. A first-time full build uses the `/graphify` skill (one LLM pass).

## Skills & agents (token-economical)

- **Skills** (invoke on demand, not auto-loaded): `java-backend` (backend/engine/control-plane changes),
  `angular-ui` (any inspecto-ui change), `build-verify` (build/test/package/run), `release-workflow`
  (before any git commit/push/tag).
- **Subagents** (isolate work in their own context): `backend-explorer` (read-only Java code location/
  explanation), `verify-runner` (runs build/test, reports verdict only). Delegate heavy exploration or
  builds to keep the main thread small.

---

## Living docs ⚠️ keep current

- **[`docs/ADVANCED_GUIDE.md`](docs/ADVANCED_GUIDE.md) is the production-investigation source of truth** (per-component
  process, events, metrics, attributes, persisted state, `-D` flags, Control API, troubleshooting). It is a **living
  document**: when a change touches a component's behaviour, an `EventType`, a `MetricRegistry` metric, a persisted
  artifact, a `System.getProperty` flag, or a `ControlApi` route, **update the matching section in the same change**
  (its §0 has the map). Don't let it drift — nobody can remember all of it.

## Session Start Protocol ⚡

**MANDATORY** at start of each session:

```bash
# Load essential docs (~800 tokens - 2 min read)
✓ .claude/COMMON_MISTAKES.md      # ⚠️ CRITICAL - Read FIRST
✓ .claude/QUICK_START.md          # Essential commands
✓ .claude/ARCHITECTURE_MAP.md     # File locations
```

**At task completion:**
- Create completion doc in `.claude/completions/YYYY-MM-DD-task-name.md`
- Move session file to `.claude/sessions/archive/` (if created)

**⚠️ NEVER auto-load:**
- Files in `.claude/completions/` (0 token cost)
- Files in `.claude/sessions/` (0 token cost)
- Files in `docs/archive/` (0 token cost)

---

## Branch & Release Strategy ⚠️ MANDATORY

**Before any `git commit` / `git push` / `git tag`, apply the `release-workflow` skill.** Full detail
there and in [`docs/BRANCHING.md`](docs/BRANCHING.md); enforced by `.claude/hooks/pre-tool-git-release-guard.sh`,
`.githooks/pre-push`, and CI. Non-negotiable guardrails (the rest is in the skill):

- **Versions = branches; editions = build flavors (NEVER branches).** Active: `master` + current `N.x`
  (today **`4.x`**). **Retired/EOL — no commits/pushes/tags ever: `1.x`, `2.x`, `3.x`.**
- **`fix:`** lands on the **oldest supported affected branch**, then **MERGE-FORWARD** to `master`;
  **`feat:`** → `master` only. SemVer + Conventional Commits.
- **Always ask the user to confirm the merge-forward set before pushing.** Never self-authorize
  `UCC_RELEASE_GUARD_DISABLE=1` (human-only EOL override).

---

**Last Updated**: 2026-06-18
**Optimized with**: [Claude Token Optimizer](https://github.com/nadimtuhin/claude-token-optimizer)
