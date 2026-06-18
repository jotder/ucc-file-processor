## graphify

A knowledge graph lives at `graphify-out/` (god nodes, communities, cross-file relationships).

- **The `graphify` CLI is NOT currently installed** (the npm package of that name is an unrelated graphing
  library, not this tool). The PreToolUse hooks that mandate `graphify query` are **gated on the binary
  being on PATH**, so they stay silent until a real graphify is installed — no token waste.
- Until then, use **Grep/Glob/Read** directly, or delegate code exploration to the **backend-explorer**
  agent. `graphify-out/GRAPH_REPORT.md` is a static (large) fallback map for broad orientation.
- If a genuine `graphify` CLI is installed later, the hooks reactivate automatically: prefer
  `graphify query/explain/path` over grep, and run `graphify update .` after code changes.

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
