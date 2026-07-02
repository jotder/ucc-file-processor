
# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

## Canonical vocabulary (binding)

**`docs/GLOSSARY.md` is the single source of truth for what every concept is called.** Words must never be
confusing or ambiguous. In all UI text, model/field names, API routes, config keys, docs, and conversation:

- Use the **canonical term**; never a banned synonym. The hard bans: ⛔ *Flow* → **Pipeline** · ⛔ *Data Store*
  (relation) → **Dataset** · ⛔ *Issue* → **Incident** · ⛔ bare *Rule* → **Expectation / Alert Rule / Decision
  Rule** · ⛔ *Metric* (BI) → **Measure** · ⛔ *Collector* (noun) → **Source**.
- **One concept → one word; one word → one concept.** Always distinguish **Type** (template) from **Instance**
  (e.g. *Visualization Type* → *Widget*).
- The rename rolls out **UI → model → backend** (map in `docs/GLOSSARY.md` §13). When you touch any of these
  layers, conform to the canonical term and update the touchpoint table.

## Working artifacts stay in the repo

**Never put work artifacts on the user profile.** Plans, specs, designs, hand-offs, notes — anything
produced for this project — must live **under the repo**, so they are IDE-readable and committable:

- **Plans / designs / specs** → `docs/superpower/` (or the right `docs/` file). Do **not** leave them in
  `~/.claude/plans/` or anywhere under the user profile. When a plan is approved, persist it in-repo.
- **Live working state / hand-off** → `SESSION_STATUS.local.md` (gitignored but in-repo).
- The user's auto-memory index is a thin pointer only; durable project knowledge belongs in the repo.

## Shared team sandbox — shifts & handover

This checkout is shared by a team working in shifts under one account. **All Claude Code setup —
skills, agents, hooks, settings — lives in repo `.claude/`, never in the user profile**, so every
shift gets the identical environment.

- **Session-per-shift.** Resume from `SESSION_STATUS.local.md` and `.claude/sessions/snapshot.md`
  (auto-written on every stop), not from old conversations. At shift end apply the `handoff` skill
  and end the session. Mid-task compaction is the failure mode — externalize state instead.
- Commits use the shared identity; work lands on `master` per the `release-workflow` skill — no
  per-user branches or PRs.

## Model & effort routing (token economy)

- **Delegate, don't read raw:** broad code searches → `Explore` (pass `model: "haiku"` for pure
  locating) or `backend-explorer`; builds/tests → `verify-runner`. Never parse full Maven/npm logs
  in the main thread.
- Reserve the strongest model + extended thinking ("think hard") for architecture and design
  decisions; routine edits and mechanical refactors don't need it.
- Big specs/docs go into `docs/` files and are referenced by path — never pasted inline into prompts.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
