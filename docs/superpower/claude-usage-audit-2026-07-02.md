# Claude Code usage audit & team-shared setup revamp — 2026-07-02

Audit of all Claude Code usage in this sandbox (54 sessions, 2026-05-28 → 2026-07-02; 109k
transcript lines, ~24.9k tool calls, ~75.6M output tokens, 11.9B cache-read tokens) plus the full
config surface. Executed the same day with the **shared-team-sandbox constraint**: the team works
in shifts under one account; no per-user commits/branches/PRs; all Claude setup lives in repo
`.claude/`; nothing project-related on the user profile.

## Scorecard

| Area | Grade | Verdict |
|---|---|---|
| Sub-agent architecture | A | verify-runner (152 runs) / backend-explorer (34) pinned to Sonnet with strict output contracts |
| Repo-first knowledge system | A− | SESSION_STATUS + docs/ + PreCompact hook; compactions fell 44→~1/session after adoption |
| Skills | B+ | Strong triggers, real usage (release-workflow 24, angular-ui 23, java-backend 14) |
| Verification culture | A− | 913 preview_evals, plan mode ×26, AskUserQuestion ×247, TaskCreate/Update ×1742 |
| Session hygiene | D | one 366-hour session; 120 compactions; 136 manual /compact; error #1 (×233) = post-compaction file-state loss |
| Model routing | C | 87% of messages on Opus; 61 manual /model switches; searches ran Opus-priced |
| Hooks/automations | C− | 12 of 13 hook scripts orphaned; live graphify hook had a `.js`⊂`.json` substring bug and fired on non-code work |
| Token economy | C+ | ~250 avoidable error round-trips; unused plugin skills loaded every session; 142k-char pasted prompts |

## Key numbers

- Sessions >24h wall-clock: 11 (max 366h, 44 compactions, 66.8MB transcript).
- Macro usage: ENDPOINT ×50 · HANDOFF ×31 · SMOKE ×24 · SHIP-IT ×5 · GAUNTLET ×0 (as prompt word; referenced by other macros).
- Top tool errors: "File has not been read yet" ×233 (compaction side-effect) · preview eval/screenshot timeouts ×69 · "file modified since read" ×15.
- Agents: verify-runner 152 · Explore 101 · backend-explorer 34 · general-purpose 31 · Plan 5.
- Models: opus-4-8 87% · fable-5 8% · sonnet-5/4-6 4%.

## Changes executed (2026-07-02)

1. **New repo skills** (macros promoted from profile memory → shared, deterministic):
   `.claude/skills/handoff/` (shift-change protocol), `.claude/skills/smoke/`,
   `.claude/skills/endpoint/`. `build-verify` now also triggers on **GAUNTLET** and documents the
   full-verify recipe. SHIP-IT stays a personal macro (= release-workflow skill + commit).
2. **Hooks pruned 13 → 3.** Deleted 11 never-wired scripts (token-guard/ghost-scanner/path-guard
   family). Kept `pre-tool-git-release-guard.sh`; wired `stop-session-snapshot.sh` as a **Stop**
   hook (writes `.claude/sessions/snapshot.md` each turn-end for shift handover) and added
   `session-start-context.sh` (repo-first protocol + last snapshot injected at **SessionStart**).
3. **graphify hooks fixed**: scoped to `inspecto*/src` source paths, proper extension matching
   (the old `'.js' in s` check fired on every `.json`), tone downgraded MANDATORY → Advisory.
   Also fixed the stale "graphify not installed" claim in `backend-explorer.md` (it is installed).
4. **Session-per-shift + model-routing rules** added to root `CLAUDE.md` (team sandbox section) and
   `.claude/QUICK_START.md` (human side: `/model opusplan`, delegate searches/builds to subagents,
   reference big specs by path instead of pasting).
5. **Settings cleanup**: `settings.local.json` reset from 64 accumulated one-off entries to empty;
   generic entries promoted to committed `settings.json` (npx vitest/ng, curl localhost, WebSearch,
   preview_start); deleted 71KB `settings.local.json.bak`; removed stale `workflows/milestone-verify.js`.
6. **Profile hygiene**: 13 project-related plan files moved from `~/.claude/plans/` →
   `docs/superpower/plans-archive/`; memory index updated (macros memory now points at repo skills).
7. Removed leftover worktree `angry-golick-0a3ae8`.

## Pending (needs a human)

- **`Bash(git push:*)` allowlist entry** — auto-mode classifier declined Claude adding it; add
  manually to `.claude/settings.json` permissions.allow if wanted (deny on `git push --force` is in place).
- **Worktree `quirky-lalande-a4a696`** — file-locked (open handle?); remove with
  `git worktree remove --force .claude/worktrees/quirky-lalande-a4a696` once nothing holds it.
- ~~Disable unused plugins~~ — done 2026-07-02 via `enabledPlugins: false` entries in
  `.claude/settings.json` (apollo/brand-voice/common-room/human-resources/operations@inline; the
  harness's own `pluginUsage` counters confirmed 0 uses each). Takes effect on new sessions —
  verify the skill list shrank next shift.
- Consider `/model opusplan` as the daily default (Opus plans, Sonnet executes).

## Operating rules going forward (summary)

- **One session per shift.** End with `/handoff`; next shift starts fresh from
  `SESSION_STATUS.local.md` + `.claude/sessions/snapshot.md`. No marathon sessions, no mid-task compaction.
- **Route by task complexity**: architecture/design → strongest model (+ "think hard"); execution →
  Sonnet (opusplan); locating/searching → Explore/backend-explorer (haiku/sonnet); build logs →
  verify-runner only.
- **Everything shared lives in repo `.claude/`**; the user profile carries nothing project-related.
