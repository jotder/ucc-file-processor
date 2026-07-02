---
name: handoff
description: >
  Shift-change / session-end handoff for the shared Inspecto sandbox. Trigger on "HANDOFF",
  "shift change", "hand over", "end of shift/session", or when the operator says they are done
  for the day. Rewrites SESSION_STATUS.local.md (state, commit chain, test baseline, pending,
  guardrails, next steps), refreshes affected repo docs and the memory index, then tells the
  operator to END the session so the next shift starts fresh — never resume a stale context.
---

# /handoff — shift-change protocol

This sandbox is shared by a team working in shifts. The next operator resumes from the **repo**,
not from this conversation. A handoff is complete when a fresh session can continue the work with
zero questions.

## Steps

1. **Rewrite `SESSION_STATUS.local.md`** (replace, don't append). Sections:
   - **Objective** — what this stretch of work is trying to achieve.
   - **Current state** — commit chain with SHAs, branch, test baseline (e.g. `mvn -o clean test`
     counts, `npm run test:ci` counts), what is verified vs. merely written.
   - **In-flight / uncommitted** — exact files and why they are uncommitted.
   - **Next steps** — ordered, concrete, with the first command to run.
   - **Blockers / gotchas** — anything the next shift would trip over.
   - Absolute dates only (no "today"/"yesterday"). Never commit this file (gitignored).
2. **Refresh affected living docs** — `docs/PROJECT_NOTES.md`, the active plan under
   `docs/superpower/`. Refactor/dedup in place; don't append changelogs.
3. **Update the memory index breadcrumb** only if a milestone shipped (repo stays the source of
   truth; memory is a thin pointer).
4. **Report to the operator**: one paragraph of what was handed off, then recommend ending this
   session. Session-per-shift is the rule — mid-task compaction is the failure mode this skill
   exists to prevent.
