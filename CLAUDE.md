## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

---

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

Full policy: [`docs/BRANCHING.md`](docs/BRANCHING.md). Enforced by `.claude/hooks/pre-tool-git-release-guard.sh` on every `git commit` / `git push` / `git tag`. **No matter which branch you are on**, you MUST run the propagation check below and **ask the user to confirm the merge-forward set before pushing**.

**Two axes — never confuse them:**
- **Versions = branches.** Active: `master` (newest/mainline) + the current `N.x` (today **`4.x`**). **Retired/EOL (frozen, no commits/pushes/tags, never a propagation target): `1.x`, `2.x`, `3.x`.**
- **Editions (Personal / Standard / Enterprise) = build flavors** (Maven profiles + `ServiceLoader` modules + `-D` flags). **Editions are NEVER branches** — do not create `personal`/`standard` branches.

**Versioning:** SemVer `vMAJOR.MINOR.PATCH`; **Conventional Commits** (`fix:`→PATCH, `feat:`→MINOR, `feat!:`/`BREAKING CHANGE:`→MAJOR). One version across all editions (artifacts differ by `-personal` / `-standard` classifier).

**Propagation = MERGE-FORWARD (oldest → master).** A `fix:` is committed on the **oldest still-supported branch it affects**, then merged forward up to `master` (e.g. `4.x → master`). A fix may never regress in a newer line. `feat:` goes to `master` only.

**Commit/push checklist (every time, every branch):**
1. Classify the change (Conventional Commit type) → SemVer + target line.
2. Find the oldest supported branch affected. If it's a `fix:` and you're on a newer line, **STOP** and relocate the fix down to that branch first.
3. **Enumerate every supported branch that still needs the change, ask the user to confirm**, then merge-forward to `master`.
4. **Refuse** to commit/push/tag on `1.x` / `2.x` / `3.x` (retired). A security backport to an EOL line needs a human to set `UCC_RELEASE_GUARD_DISABLE=1` and document it — agents must ask, never self-authorize.
5. Tag releases `vX.Y.Z` on the branch they ship from.

---

**Last Updated**: 2026-06-16
**Optimized with**: [Claude Token Optimizer](https://github.com/nadimtuhin/claude-token-optimizer)
